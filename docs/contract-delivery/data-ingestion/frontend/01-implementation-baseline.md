# 数据采集引接前端实施基线

> 适用范围：在 `codex/data-ingestion-menu-prototype` 已经生成菜单原型的基础上，开始正式前端开发。
> 本文对应交付阶段：M1 / M2 中的前端页面实现。
> 不在本文范围：OpenMetadata 集成、TDuck 集成、SSO 接入、性能与安全专题。

## 1. 基线状态

- 基线日期：2026-07-27
- 仓库：`D:\Code\workspace_huo\seatunnel-web`
- 基线分支：`codex/data-ingestion-frontend-impl`（基于 `codex/data-ingestion-menu-prototype` 创建）
- 上游原型提交：`027ecdc8`
- 当前菜单与页面归属：`docs/contract-delivery/data-ingestion/menu-design.md`
- 当前交付基线：`docs/contract-delivery/data-ingestion/delivery-baseline.md`
- 当前规划评审：`docs/contract-delivery/data-ingestion/review-checklist.md`

本基线假定：

1. 一、二级菜单名称、路由、技术模块、复用关系与原型保持一致；
2. SSO、OpenMetadata、TDuck 接入不属于本任务；
3. 不重写 `develop` 中已经稳定的代码路径；
4. 严格遵循"一个需求、一组测试、一个提交"的提交节奏；
5. 不在分支内删除 `docs/rocket/` 下用户原始未跟踪资料。

## 2. 本轮前端实现总表

| 一级菜单 | 二级菜单 | 路由 | 原型状态 | 本轮目标 | 数据来源 |
| --- | --- | --- | --- | --- | --- |
| 引接资源 | 数据源管理 | `/data-source` | REUSE | 保留现有页面，确认与新菜单同名 | 真实接口 |
| 引接资源 | 引擎管理 | `/client` | ADAPT | 保留现有页面，确认菜单/字段语义 | 真实接口 |
| 数据引接 | 离线引接任务管理 | `/sync/batch-link-up` | REUSE | 保留现有页面 | 真实接口 |
| 数据引接 | 实时引接任务管理 | `/sync/stream-link-up` | ADAPT | 保留现有页面 | 真实接口 |
| 运行运维 | 引接态势 | `/bi` | ADAPT | 保留现有页面 | 真实接口 |
| 运行运维 | 运行监控 | `/metrics` | REUSE | 保留现有页面 | 真实接口 |
| 运行运维 | 告警管理 | `/alarm` | REUSE | 保留现有页面 | 真实接口 |
| 系统管理 | 参数与知识 | `/knowledge-management` | REUSE | 保留现有页面 | 真实接口 |
| 系统管理 | 开放接口 | `/open-api` | REUSE | 保留现有页面 | 真实接口 |
| 数据采报 | 采集报告管理 | `/reporting/reports` | BUILD | 新建页面 + Mock | Mock（评估后真实化） |
| 数据引接 | 引接链路管理 | `/sync/links` | BUILD | 新建页面 + Mock | Mock（评估后真实化） |
| 数据引接 | 云边协同任务管理 | `/sync/cloud-edge-tasks` | LIMITED | 新建页面 + Mock | Mock |
| 数据引接 | 边缘接入任务管理 | `/sync/edge-access-tasks` | LIMITED | 新建页面 + Mock | Mock |
| 运行运维 | 故障辅助 | `/operations/diagnostics` | LIMITED | 新建页面 + Mock | Mock |
| 入湖管理 | 入湖资源管理 | `/lake/resources` | BUILD | 新建页面 + Mock | Mock（评估后真实化） |
| 入湖管理 | 数据生命周期管理 | `/lake/lifecycle` | BUILD | 新建页面 + Mock | Mock（评估后真实化） |
| 入湖管理 | 逻辑入湖管理 | `/lake/logical-access` | LIMITED | 新建页面 + Mock | Mock |

不在本轮范围（已排除）：

- `/reporting/forms`（数据采报管理）—— 集成 TDuck，本任务不涉及；
- `/resources/data-discovery`（数据探查）—— 集成 OpenMetadata，本任务不涉及；
- `/sync/topology`（数据拓扑管理）—— 集成 OpenMetadata 血缘，本任务不涉及；
- 任何用户、角色、权限管理菜单 —— 由 SSO 统一提供，不在前端构建。

## 3. 实施原则

### 3.1 复用与新增原则

1. 复用页面：仅做路由与菜单对齐核对，不重写。复用页面的 `service` 继续通过 `HttpUtils` 调用真实后端。
2. 新增页面：本轮只交付**列表 + 详情 + 关键状态切换**最小闭环，不实现复杂配置向导。
3. 每个新页面提供 `mockData.ts`，在 `REACT_APP_PROTOTYPE=1` 时通过 `HttpUtils` 内置 prototype transport 自动回放 Mock；关闭原型开关时仍走真实接口。
4. 新增页面不复制 `CapabilityPage` 的视觉；遵循现有真实页面的样式系统（antd + tailwind utility）。

### 3.2 Mock 数据约定

1. Mock 数据在浏览器 `localStorage` 中持久化，键格式 `seatunnel-mock:<page-id>`。
2. 任意页面右上提供"清空 Mock"按钮，恢复预置数据；仅在原型模式下可见。
3. 新页面的 service 调用约定使用 `HttpUtils.{get,post,put,delete}`，**禁止**绕过 HttpUtils 直接调 axios。
4. 真实接口就绪时，仅需把 service 中的 mock 分支移除或关闭 `isPrototypeMode`；UI 层不动。

### 3.3 路由与菜单

1. 一级菜单只通过 `prototypeMenuData`（`src/prototype/menuData.tsx`）展示；
2. 新建真实页面后，将其替换 `config/routes.ts` 中对应路由的 component 占位；
3. 原型模式（`REACT_APP_PROTOTYPE=1`）下保持 CapabilityPage 与指标标注条不变；
4. 隐藏子路由（详情、配置）继续放在 `hiddenRoutes` 中，不进入菜单。

### 3.4 测试与质量

1. 每个新页面至少一组 `__tests__/*.test.ts`：mock 数据生成、URL → handler 映射、关键 reducer/过滤；
2. 不修复存量 tsc 错误，本轮验收以新增文件错误过滤为基准；
3. `npm run build` 在普通模式与原型模式两种构建都必须通过；
4. `npm run prototype:generate` 仍然通过 55 条指标 / 20 个页面。

## 4. 切片与交付顺序

按"一个需求、一组测试、一个提交"原则，每个新页面拆为独立切片。

| 切片 | 内容 | 文件范围 | 验收 |
| --- | --- | --- | --- |
| F-IMPL-01 | 实施基线文档 + 路由从 CapabilityPage 切到独立目录占位 | docs/contract-delivery/data-ingestion/frontend/01-implementation-baseline.md；config/routes.ts | 文档渲染；路由表正确；`npm run build` 通过 |
| F-IMPL-02 | `/reporting/reports` 采集报告管理 | src/pages/reporting-reports/**；tests | 列表、生成报告、预览 |
| F-IMPL-03 | `/sync/links` 引接链路管理 | src/pages/sync-links/**；tests | 列表、健康详情、跳转任务 |
| F-IMPL-04 | `/lake/resources` 入湖资源管理 | src/pages/lake-resources/**；tests | 列表、登记、连接测试 |
| F-IMPL-05 | `/lake/lifecycle` 数据生命周期管理 | src/pages/lake-lifecycle/**；tests | 列表、新建策略、执行记录 |
| F-IMPL-06 | `/sync/cloud-edge-tasks` 云边协同任务管理 | src/pages/sync-cloud-edge/**；tests | 列表、下发、断网/恢复状态 |
| F-IMPL-07 | `/sync/edge-access-tasks` 边缘接入任务管理 | src/pages/sync-edge-access/**；tests | 列表、协议选择、连通测试 |
| F-IMPL-08 | `/operations/diagnostics` 故障辅助 | src/pages/operations-diagnostics/**；tests | 故障时间线、证据抽屉、安全重试 |
| F-IMPL-09 | `/lake/logical-access` 逻辑入湖管理 | src/pages/lake-logical-access/**；tests | 映射列表、查询预览 |

复用页面（REUSE/ADAPT）在 F-IMPL-01 中完成路由对齐核对，不拆单独切片。

## 5. 风险与边界

1. 真实后端未就绪：当前复用页面调用真实接口会失败。本任务不修复后端，只确保前端代码与后端契约保持一致（按现有 controller 路径）。
2. 新建 Mock 必须与页面所属技术模块（MOD-001/MOD-006/MOD-008/MOD-009）的指标口径保持一致，参考 `docs/contract-delivery/data-ingestion/traceability.csv`。
3. 新建页面不得在菜单文案中混入"用户管理""角色管理"等被 SSO 覆盖的能力。
4. 边缘接入、云边协同、故障辅助、逻辑入湖为 LIMITED 实现，UI 必须显示"有限实现"标签，禁止虚假宣称完整能力。
5. F-IMPL-02~09 任一切片失败时，回退到 F-IMPL-01 路由表骨架，不影响 develop 与 prototype 主干。

## 6. 验收清单

- [ ] 基线文档（本文件）已提交；
- [ ] 8 个新页面均已建立目录、组件、Mock、测试；
- [ ] `config/routes.ts` 在非 prototype 模式下指向真实组件，prototype 模式保持 CapabilityPage；
- [ ] `npm run prototype:generate` 校验仍 PASS；
- [ ] `npm run build` 普通模式与原型模式均 PASS；
- [ ] 每个新页面的测试 PASS；
- [ ] 现有复用页面路由核对通过，未引入新菜单文案差异。
