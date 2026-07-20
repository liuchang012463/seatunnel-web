# 采集引接链路统一管理 — 合同指标二次开发设计

**日期:** 2026-07-20  
**分支策略:** 基于现有代码新建功能分支进行二次开发  
**原则:** 尽量少改代码；路由与业务 API 路径不变  
**方案:** 方案 A — 合同映射 + 最小后端补齐  
**对应指标:** 《数据采集引接软件指标》功能指标 #12「采集引接链路统一管理」

---

## 1. 目标与范围

### 1.1 目标

在现有 SeaTunnel Web（离线同步 `batch-link-up`、实时同步 `stream-link-up`）之上，完成合同口径「采集引接链路统一管理」的可验收能力：

| 合同功能项 | 映射现有能力 | 本次改动类型 |
| --- | --- | --- |
| 链路管理 | 离线/实时同步任务列表 | 前端菜单 + 列表字段语义化 |
| 物理路由配置 | 任务 detail 页 | 前端文案/分区标题合同化 |
| 逻辑关系配置 | 单表配置工作流页 | 前端文案合同化 |
| 带宽配额配置 | 任务 Env | 前端 Env 字段 + 后端 env→HOCON |
| 传输优先级配置 | 任务 Env | 前端 Env 字段 + 存储；不生效 |
| 链路动态调度 | 现有调度配置 | 增加分钟级 cron 生成 |
| 健康状态监测 | 任务状态 | 文案映射，不新建监控服务 |
| 负载情况监测 | 实时任务趋势 | 文案映射 + 修复 CDC 使指标可产出 |

### 1.2 非目标（本次不做）

- 不合并离线/实时为单一菜单或新路由前缀
- 不新建独立「引接链路」后端服务或聚合 API
- 不实现传输优先级对调度/执行的真实影响
- 不深改多表（multi）/脚本（script）交互（仅必要文案时可顺带）
- 不做性能优化算法与自动链路切换
- 不覆盖指标 #12 以外的其他合同功能点

### 1.3 成功标准

1. 菜单与列表可用合同用语演示「链路管理」（含健康状态、负载情况语义）。
2. 创建/编辑链路时可演示：物理路由（detail）、逻辑关系（single config）、带宽配额与传输优先级（Env）。
3. 带宽参数写入任务 env，生成 HOCON 的 env 段包含 `read_limit.bytes_per_second` / `read_limit.rows_per_second`（有值时）。
4. 优先级高/中/低可保存与回显，UI 标明当前版本仅存储。
5. 离线任务调度支持分钟级，生成合法 Quartz cron 并可保存。
6. MySQL-CDC 相关 pluginName 与 SPI 注册修复后，实时链路可配置/构建，负载趋势可依赖真实运行数据。

---

## 2. 架构与映射

### 2.1 现有结构（保持）

```
/sync/batch-link-up              → 离线链路列表（链路管理-离线）
/sync/batch-link-up/:id/detail   → 物理路由配置
/sync/batch-link-up/:id/config/single → 逻辑关系配置（单表）
/sync/stream-link-up             → 实时链路列表（链路管理-实时）
/sync/stream-link-up/:id/detail  → 物理路由配置
/sync/stream-link-up/:id/config/single → 逻辑关系配置（单表）
```

业务 API、任务定义存储、调度 API 路径均不变更。

### 2.2 改动分层

| 层 | 模块 | 改动 |
| --- | --- | --- |
| UI | `seatunnel-web-ui` locales、列表、detail、Env、调度 UI、实时趋势文案 | 合同化展示 + 新表单字段 |
| SPI/DTO | `JobEnvConfig` | 扩展带宽、优先级字段 |
| Core | `EnvConfigBuilder`（及必要 extender） | 将带宽写入 HOCON env |
| Datasource | mysql-cdc builder + SPI 加载 | pluginName 对齐 + 注册修复 |
| 调度 | 前端 cron 生成；后端沿用 cron 解析/校验 | 分钟级 scheduleType |

### 2.3 数据流（带宽/优先级）

1. 用户在 Env 面板填写带宽、优先级。
2. 前端随任务保存 payload 的 `env` 提交。
3. 后端反序列化为 `JobEnvConfig`（扩展后字段不丢失）。
4. 发布/构建 HOCON 时，`EnvConfigBuilder` 写入：
   - `job.mode`、`parallelism`（已有）
   - `read_limit.bytes_per_second`、`read_limit.rows_per_second`（有值时）
   - `priority`：**仅存于任务 env JSON**；是否写入 HOCON 可选，默认**不写入引擎配置**（避免无意义配置干扰），但必须可 round-trip 保存回显。

> 说明：SeaTunnel 引擎对「任务优先级」无对应能力；合同要求「配置」即可，明确不生效。

---

## 3. 链路管理（前端合同化）

### 3.1 菜单与 i18n

| 键（示例） | 现文案 | 新文案 |
| --- | --- | --- |
| `menu.data-sync.batch` | 离线同步 | 引接链路（离线） |
| `menu.data-sync.stream` | 实时同步 | 引接链路（实时） |
| 列表页标题（pages 相关） | 离线同步任务 / 实时同步… | 链路管理（离线/实时）口径 |

路由 `name`、`path` 不变。

### 3.2 列表字段语义映射

| 合同用语 | 现有字段/组件 | 展示策略 |
| --- | --- | --- |
| 链路名称 | 任务名 | 列标题改为「链路名称」 |
| 健康状态 | 任务状态（运行中/失败/停止等） | 列标题改为「健康状态」；状态值文案可保留原状态枚举含义 |
| 负载情况 | 实时 `RealtimeMetricsTrend` / `recentMetrics` | 列/区块标题改为「负载情况」；离线列表无趋势时显示「—」或最近实例摘要（优先「—」以少改） |

筛选、搜索、操作列（运行/停止/编辑/日志）逻辑不变，筛选项文案对齐合同用语。

### 3.3 明确不改

- 不改 URL
- 不改列表查询 API 与分页参数
- 不合并双列表

---

## 4. 引接链路配置

### 4.1 物理路由配置（detail）

**页面:**  
- `seatunnel-web-ui/src/pages/batch-link-up/detail/**`  
- `seatunnel-web-ui/src/pages/stream-link-up/detail/**`

**改造:**

- 页头/分区标题合同化，例如：
  - 「基础信息 / 客户端与数据源」→ 体现「物理路由配置」
  - 说明文案：定义数据从数据源到目标端的物理接入路径（数据源、目标库、执行客户端等）
- 表单字段与提交逻辑不变（数据源、模式、客户端等）

### 4.2 逻辑关系配置（config/single）

**页面:**  
- `.../config/single` 及共享 workflow 文案（源表、目标表、字段映射等）

**改造:**

- 将「同步配置 / 映射」等文案对齐「逻辑关系配置」
- 说明：定义源与目标之间的表/字段逻辑映射关系
- 画布、校验、发布流程不变
- multi/script：本次不深改交互；若共享组件标题会被 single 引用，可一并微调共享文案

### 4.3 带宽配额配置（Env）

**前端:**

- `EnvConfig` 类型扩展（batch 与 stream 对齐）：
  - `readLimitBytesPerSecond?: number | null`
  - `readLimitRowsPerSecond?: number | null`
  - （序列化到后端时使用引擎约定键名，见下）
- `EnvConfigContent`（及 stream 侧对应组件）增加两个数值输入：
  - 标签：带宽限额 — 每线程最大读取字节/秒、每线程最大读取行/秒
  - 空值 = 不限速
  - 校验：≥ 0 的整数；非法禁止提交

**后端 env 键（写入 HOCON env 段）:**

```
read_limit.bytes_per_second
read_limit.rows_per_second
```

**DTO:** `JobEnvConfig` 增加对应字段（命名可用 Java 属性 + Jackson 映射，保证与前端 JSON 一致）。  
**EnvConfigBuilder.fillCommonConfig / extender:** 非空时写入 `envMap`。

### 4.4 传输优先级配置（Env）

- UI：Select 高 / 中 / 低；默认「中」
- 存储键建议：`priority`，取值 `HIGH` | `MEDIUM` | `LOW`（或 `high/medium/low`，实现时统一一种并文档固定）
- 写入任务 env JSON，保存与回显
- UI 提示：「当前版本仅存储，不参与调度与执行」
- 不进入调度器比较逻辑；默认不写入 SeaTunnel HOCON env（避免伪配置）

### 4.5 默认值

| 字段 | 默认 |
| --- | --- |
| jobMode / parallelism | 保持现有 |
| 带宽两项 | 空（不限速） |
| priority | MEDIUM |

---

## 5. 链路动态调度

### 5.1 问题

当前 `scheduleType` 仅为 `"hour" | "day" | "week"`，无法配置分钟级周期。

### 5.2 设计

- 扩展 `scheduleType` 增加 `"minute"`
- `ScheduleTimeSection` 增加分钟调度 UI（例如：每隔 N 分钟，N 范围建议 1–59）
- `utils` 生成 Quartz cron，例如每 5 分钟：`0 0/5 * * * ?`（最终格式与现有 hour/day/week 生成器及后端 `CronUtils` QUARTZ 定义一致）
- 保存仍走现有 schedule API（`cronExpression` 字段）
- 预览组件 `CronPreview` 继续调用现有「最近 5 次执行时间」接口

### 5.3 范围

- **离线任务（batch）为主**：调度面板完整支持分钟级
- **实时任务**：多为常驻 STREAMING；若 UI 共用调度组件则一并具备能力，但不强制实时场景使用

### 5.4 后端

- 不新增强制 API；依赖已有 cron 校验与调度执行
- 若发现后端对 cron 周期枚举写死 hour/day/week，仅做**最小兼容**（以 cron 字符串为准，而非枚举）

---

## 6. 健康状态与负载情况

### 6.1 健康状态监测

- 映射现有任务/实例状态展示
- 列名与说明改为「健康状态」
- 不新增健康探针服务、不新增独立告警链路（告警模块若已有则不动）

### 6.2 负载情况监测

- 实时列表/详情中的 metrics 趋势组件文案改为「负载情况」
- 数据源仍为 `recentMetrics`（read/write QPS、行数等）
- 依赖 CDC/实时任务可正常运行以产生真实点；修复见第 7 节

---

## 7. MySQL-CDC 修复（支撑实时负载）

### 7.1 问题 1：pluginName 大小写不匹配

- `DataSourceSourceBuilder.getRequiredPluginName()` 对 pluginName 执行 `toUpperCase()`
- 若 `MysqlCdcSourceBuilder.pluginName()` 返回 `MySQL-CDC`，查找 builder 时键为 `MYSQL-CDC` 可能 miss（取决于 BUILDER_MAP 注册键）

**修复:**

- `MysqlCdcSourceBuilder.pluginName()` 返回与查找约定一致的名称：`MYSQL-CDC`  
  （或在注册/查找两侧统一大小写策略；优先改 builder 返回值，改动最小）
- 前端若写死 `MySQL-CDC`，因后端 `toUpperCase()` 可兼容；前后端展示可继续用 `MySQL-CDC` 文案

### 7.2 问题 2：SPI / nested jar 未加载

- ServiceLoader 未加载到 mysql-cdc 的 SPI 文件时，builder 不会进入注册表

**修复策略（按优先级）:**

1. 确认 `META-INF/services/...` 存在于 `seatunnel-web-datasource-mysql-cdc` 模块且内容正确  
2. 确认 dist/fat-jar/打包方式包含该 SPI（maven shade/spring-boot repackage 的 transformers）  
3. 若 Spring Boot nested jar + LaunchedClassLoader 导致 getResources 漏读：采用项目内最小可行修复（例如确保插件打入可被应用 ClassLoader 扫描的路径，或补充显式注册兼容路径）  
4. 增加启动或单元测试：断言 `MYSQL-CDC` builder 可被 `getQueryBuilder` / 工厂解析

### 7.3 验收

- 实时任务选择 MySQL-CDC 源时可成功构建/保存配置（不再因 builder null 失败）
- 日志可确认 SPI 注册数量符合预期（或测试覆盖）

---

## 8. 错误处理

| 场景 | 处理 |
| --- | --- |
| 带宽输入非数字/负数 | 前端校验拦截 |
| cron 非法 | 沿用后端 `CronUtils` / 现有保存校验 |
| CDC builder 缺失 | 明确异常信息（含 pluginName），便于运维 |
| 优先级未填 | 默认 MEDIUM |

---

## 9. 测试计划

### 9.1 前端

- 菜单文案：离线/实时为「引接链路（离线/实时）」
- 列表列：链路名称、健康状态、负载情况
- detail / single 页出现物理路由、逻辑关系相关说明
- Env：填写带宽与优先级 → 保存 → 重新打开回显
- 调度：选择分钟级 → cron 预览合理 → 保存成功

### 9.2 后端

- `EnvConfigBuilder`：含带宽字段时 HOCON env 出现 `read_limit.*`
- 无带宽字段时行为与现网一致
- `priority` 保存在 env JSON 且不破坏反序列化
- CDC：`MYSQL-CDC` 可解析到 HoconBuilder

### 9.3 手工联调

- 离线链路：创建 → 物理路由 → 逻辑关系 → Env 限速 → 分钟调度 → 运行
- 实时链路：MySQL-CDC 源任务可配置；有运行数据时负载趋势有点

---

## 10. 实现任务拆分（供后续 plan）

1. **分支:** 从当前基线新建功能分支（如 `feature/link-management-contract`）
2. **i18n + 列表合同化**（batch/stream）
3. **detail / single 文案合同化**
4. **Env UI 字段**（batch + stream 对齐）
5. **JobEnvConfig + EnvConfigBuilder** 带宽落地；priority 存储
6. **分钟级调度 UI + cron 生成**
7. **CDC pluginName + SPI 修复 + 验证**
8. **联调与回归**

---

## 11. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| JobEnvConfig 强类型导致未知字段丢失 | 明确扩展 DTO 字段，前后端键名一致 |
| SeaTunnel 对 read_limit 键名/层级与预期不一致 | 对照引擎文档与现有任务配置样例确认键路径；以写入 env 段可演示为准 |
| SPI 在打包产物中仍丢失 | 以 dist 实际 ClassLoader 验证，不只在 IDE 验证 |
| 合同验收要求「优先级生效」 | 规格已约定仅存储；演示话术与 UI 标注一致 |

---

## 12. 已确认决策摘要

- 交付口径：按用户映射逐项落地（方案 A）
- 链路管理：菜单 + 列表字段语义化（不合并菜单、不改路由）
- 配置：离线+实时 detail/single + Env
- 带宽与优先级：均写入任务 env；带宽进 HOCON；优先级仅存储
- 调度：前端 + 真 cron 分钟级
- 健康/负载：映射现有；修 CDC 两问题

---

**审批状态:** 设计分节已获用户确认，待用户审阅本文件后进入 implementation plan。
