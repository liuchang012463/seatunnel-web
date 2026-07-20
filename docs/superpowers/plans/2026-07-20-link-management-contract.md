# 采集引接链路统一管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有离线/实时同步能力上，以尽量少改代码完成合同指标 #12「采集引接链路统一管理」的可验收能力（链路管理、引接链路配置、动态调度、健康/负载、MySQL-CDC 修复）。

**Architecture:** 不改路由与业务 API。前端对 `batch-link-up` / `stream-link-up` 做合同语义化；Env 扩展带宽与优先级；后端扩展 `JobEnvConfig` + `EnvConfigBuilder` 将 `read_limit.*` 写入 HOCON；调度 UI 增加分钟级 Quartz cron；修复 MySQL-CDC pluginName 与 SPI 注册以支撑实时负载。

**Tech Stack:** React/Umi (seatunnel-web-ui)、Java/Spring Boot (seatunnel-web-spi/core/datasource-plugins)、Quartz cron、SeaTunnel HOCON env。

**Spec:** `docs/superpowers/specs/2026-07-20-link-management-contract-design.md`

**前置注意（本 worktree）：** 当前 git 索引可能存在大量误标 `D`（deleted）的已跟踪文件，但工作区仍有源码。实现前先执行：

```powershell
git status -sb | Select-Object -First 5
git restore --source=HEAD --staged --worktree -- . 2>$null
# 若 restore 会抹掉已提交的 spec/plan，先 stash 或仅 restore 代码路径；保留 docs/superpowers/**
```

确保 `seatunnel-web-ui` 与 Java 源码可读写后再开功能分支。

---

## File map（将创建/修改）

| 文件 | 职责 |
| --- | --- |
| `seatunnel-web-ui/src/locales/zh-CN/menu.ts` | 菜单：引接链路（离线/实时） |
| `seatunnel-web-ui/src/locales/zh-CN/pages.ts` | 列表标题/列名合同化 |
| `seatunnel-web-ui/src/pages/batch-link-up/detail/components/PageHeader.tsx` | 物理路由页头文案 |
| `seatunnel-web-ui/src/pages/stream-link-up/detail/components/PageHeader.tsx` | 同上（实时） |
| `seatunnel-web-ui/src/pages/batch-link-up/detail/components/ModeSection.tsx` | 物理路由分区说明 |
| `seatunnel-web-ui/src/pages/stream-link-up/detail/components/ModeSection.tsx` | 同上 |
| `seatunnel-web-ui/src/pages/batch-link-up/workflow/EnvConfigContent.tsx` | 带宽+优先级 UI |
| `seatunnel-web-ui/src/pages/stream-link-up/workflow/EnvConfigContent.tsx` | 同上（保留 checkpoint） |
| `seatunnel-web-ui/src/pages/batch-link-up/workflow/components/ScheduleConfigContent/types.ts` | EnvConfig/ScheduleType 扩展 |
| `seatunnel-web-ui/src/pages/stream-link-up/workflow/components/ScheduleConfigContent/types.ts` | 同上 |
| `.../ScheduleTimeSection/types.ts`（batch+stream） | `minute` 类型 |
| `.../ScheduleTimeSection/utils.ts`（batch+stream） | 分钟 cron |
| `.../ScheduleTimeSection/index.tsx`（batch+stream） | 分钟 UI |
| `.../ScheduleTimeSection/components/MinuteScheduleFields.tsx`（batch+stream） | 分钟字段 |
| `seatunnel-web-ui/src/pages/stream-link-up/components/RealtimeMetricsTrend.tsx` | 负载情况文案 |
| `seatunnel-web-ui/src/pages/batch-link-up/workflow/MappingConfigContent.tsx` | 逻辑关系文案（若有标题） |
| `seatunnel-web-ui/src/pages/stream-link-up/workflow/MappingConfigContent.tsx` | 同上 |
| `seatunnel-web-spi/.../JobEnvConfig.java` | DTO：带宽+priority |
| `seatunnel-web-core/.../EnvConfigBuilder.java` | HOCON 写入 read_limit |
| `seatunnel-web-core/src/test/java/.../EnvConfigBuilderTest.java` | 单测 |
| `seatunnel-web-datasource-plugins/.../mysql/cdc/builder/MysqlCdcSourceBuilder.java` | pluginName=`MYSQL-CDC` |
| mysql-cdc `META-INF/services/**` + dist 打包 | SPI 可加载 |
| `seatunnel-web-ui` 可选 vitest/jest 或手测清单 | 前端校验 |

---

### Task 1: 创建功能分支并恢复工作区

**Files:** none（git only）

- [ ] **Step 1: 确认 clean 代码树可读**

```powershell
Test-Path "seatunnel-web-ui\src\pages\batch-link-up\workflow\EnvConfigContent.tsx"
Test-Path "seatunnel-web-spi\src\main\java\org\apache\seatunnel\web\spi\bean\dto\config\JobEnvConfig.java"
```

Expected: both `True`

- [ ] **Step 2: 若索引异常，从 HEAD 恢复代码（保留 docs/superpowers）**

```powershell
git stash push -u -m "wip-docs" -- docs/superpowers 2>$null
git restore --source=HEAD --staged --worktree -- seatunnel-web-ui seatunnel-web-spi seatunnel-web-core seatunnel-web-datasource-plugins seatunnel-web-api 2>$null
git stash pop 2>$null
```

- [ ] **Step 3: 创建分支**

```powershell
git checkout -b feature/link-management-contract
```

Expected: on `feature/link-management-contract`

- [ ] **Step 4: Commit（若有 stash 恢复的 docs 变更则一并保留；本步可空提交跳过）**

无代码变更可不 commit。

---

### Task 2: 菜单与列表 i18n 合同化

**Files:**
- Modify: `seatunnel-web-ui/src/locales/zh-CN/menu.ts`
- Modify: `seatunnel-web-ui/src/locales/zh-CN/pages.ts`

- [ ] **Step 1: 改菜单文案**

将 `menu.ts` 中：

```ts
'menu.data-sync.batch': '离线同步',
'menu.data-sync.stream': '实时同步',
```

改为：

```ts
'menu.data-sync.batch': '引接链路（离线）',
'menu.data-sync.stream': '引接链路（实时）',
```

- [ ] **Step 2: 改列表标题与列名**

在 `pages.ts` 修改/新增：

```ts
'pages.datasync.header.title': '链路管理（离线）',
'pages.datasync.header.subtitle': '统一管理采集引接链路：配置、调度与健康状态监测',

'pages.job.table.col.name': '链路名称/ID',
'pages.job.table.col.status': '健康状态',
'pages.job.table.col.schedule': '链路动态调度',
```

若实时列表有独立 i18n key（搜索 `实时同步` / `stream` header），同步改为「链路管理（实时）」口径；没有则改 `stream-link-up` 页内硬编码标题（Task 3）。

- [ ] **Step 3: 手动确认（或启动 UI）**

Run（可选）:

```powershell
cd seatunnel-web-ui; yarn start
```

Expected: 侧栏显示「引接链路（离线/实时）」；离线列表标题为链路管理口径。

- [ ] **Step 4: Commit**

```powershell
git add seatunnel-web-ui/src/locales/zh-CN/menu.ts seatunnel-web-ui/src/locales/zh-CN/pages.ts
git commit -m "feat(ui): contractize menu and list i18n for link management"
```

---

### Task 3: 列表/实时负载展示文案

**Files:**
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/components/RealtimeMetricsTrend.tsx`
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/index.tsx`（页头/硬编码标题若有）
- Modify: `seatunnel-web-ui/src/pages/batch-link-up/index.tsx` 或 `components/DataSyncHeader`（若标题非纯 i18n）
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/components/RealtimeTaskTable.tsx`（列 title 硬编码时）

- [ ] **Step 1: 搜索硬编码中文**

```powershell
rg -n "离线同步|实时同步|任务名称|状态|趋势|负载" seatunnel-web-ui/src/pages/batch-link-up seatunnel-web-ui/src/pages/stream-link-up --glob "*.tsx"
```

- [ ] **Step 2: 将实时趋势组件文案改为负载情况**

在 `RealtimeMetricsTrend.tsx` 中，所有用户可见「指标/趋势」类标题改为「负载情况」（保留图表逻辑与 `recentMetrics` 数据源不变）。

- [ ] **Step 3: 离线列表无趋势列时保持「—」或不动执行概况列**

不新增假数据；仅确保「健康状态」列已通过 i18n 映射 `pages.job.table.col.status`。

- [ ] **Step 4: Commit**

```powershell
git add seatunnel-web-ui/src/pages/stream-link-up seatunnel-web-ui/src/pages/batch-link-up
git commit -m "feat(ui): map health and load labels on link list views"
```

---

### Task 4: 物理路由 + 逻辑关系文案（detail / single）

**Files:**
- Modify: `seatunnel-web-ui/src/pages/batch-link-up/detail/components/PageHeader.tsx`
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/detail/components/PageHeader.tsx`
- Modify: `seatunnel-web-ui/src/pages/batch-link-up/detail/components/ModeSection.tsx`
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/detail/components/ModeSection.tsx`
- Modify: `seatunnel-web-ui/src/pages/batch-link-up/detail/components/BaseInfoSection.tsx`（分区标题若有）
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/detail/components/BaseInfoSection.tsx`
- Modify: `seatunnel-web-ui/src/pages/batch-link-up/workflow/MappingConfigContent.tsx`
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/workflow/MappingConfigContent.tsx`
- Modify: `seatunnel-web-ui/src/pages/batch-link-up/workflow/configDefinition.tsx`（配置面板 tab 标题）
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/workflow/configDefinition.tsx`

- [ ] **Step 1: detail PageHeader 合同化（batch 示例）**

```tsx
<div className="text-[22px] font-semibold leading-8 text-[#101828]">
  物理路由配置
</div>
<div className="mt-1 text-[14px] leading-6 text-[#667085]">
  配置引接链路的物理路由：数据源、目标端与执行客户端等接入路径。
</div>
```

stream 侧同步，可用「创建实时引接链路 · 物理路由配置」区分。

- [ ] **Step 2: ModeSection 说明**

将「配置模式」副标题改为体现物理路由下一步进入逻辑关系配置，例如：

```tsx
选择配置方式后，进入逻辑关系配置（表/字段映射）与环境参数设置。
```

- [ ] **Step 3: 逻辑关系（Mapping / configDefinition）**

在 single 工作流右侧或映射区标题使用「逻辑关系配置」；说明：定义源与目标之间的表/字段逻辑映射。不改保存/发布 API。

- [ ] **Step 4: Commit**

```powershell
git add seatunnel-web-ui/src/pages/batch-link-up/detail seatunnel-web-ui/src/pages/stream-link-up/detail seatunnel-web-ui/src/pages/batch-link-up/workflow seatunnel-web-ui/src/pages/stream-link-up/workflow
git commit -m "feat(ui): contractize physical route and logical relation copy"
```

---

### Task 5: 前端 Env — 带宽配额 + 传输优先级

**Files:**
- Modify: `seatunnel-web-ui/src/pages/batch-link-up/workflow/components/ScheduleConfigContent/types.ts`
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/workflow/components/ScheduleConfigContent/types.ts`（若独立 EnvConfig）
- Modify: `seatunnel-web-ui/src/pages/batch-link-up/workflow/EnvConfigContent.tsx`
- Modify: `seatunnel-web-ui/src/pages/stream-link-up/workflow/EnvConfigContent.tsx`

- [ ] **Step 1: 扩展 EnvConfig 类型（batch types.ts）**

```ts
export type LinkPriority = "HIGH" | "MEDIUM" | "LOW";

export interface EnvConfig {
  jobMode: "BATCH" | "STREAMING";
  parallelism: number;
  /** 每线程最大读取字节/秒；空=不限速 */
  readLimitBytesPerSecond?: number | null;
  /** 每线程最大读取行/秒；空=不限速 */
  readLimitRowsPerSecond?: number | null;
  /** 仅存储，不参与调度/执行 */
  priority?: LinkPriority;
}

export const defaultEnvConfig: EnvConfig = {
  jobMode: "BATCH",
  parallelism: 1,
  readLimitBytesPerSecond: null,
  readLimitRowsPerSecond: null,
  priority: "MEDIUM",
};
```

stream 侧 `jobMode: "STREAMING"`，并保留已有 `checkpointInterval` 字段（若在 EnvConfig 中）。

- [ ] **Step 2: EnvConfigContent 增加表单（batch）**

在并行度字段后增加：

```tsx
const PRIORITY_OPTIONS = [
  { label: "高", value: "HIGH" },
  { label: "中", value: "MEDIUM" },
  { label: "低", value: "LOW" },
];

// 带宽：bytes
<div>
  <div className="mb-1 text-[12px] text-slate-400">带宽限额（字节/秒·每线程）</div>
  <InputNumber
    min={0}
    precision={0}
    placeholder="空表示不限速"
    value={value?.readLimitBytesPerSecond ?? null}
    onChange={(v) => handleFieldChange("readLimitBytesPerSecond", v)}
    className="w-full"
  />
</div>

// 带宽：rows
<div>
  <div className="mb-1 text-[12px] text-slate-400">带宽限额（行/秒·每线程）</div>
  <InputNumber
    min={0}
    precision={0}
    placeholder="空表示不限速"
    value={value?.readLimitRowsPerSecond ?? null}
    onChange={(v) => handleFieldChange("readLimitRowsPerSecond", v)}
    className="w-full"
  />
</div>

// 优先级
<div>
  <div className="mb-1 text-[12px] text-slate-400">传输优先级</div>
  <Select
    value={value?.priority ?? "MEDIUM"}
    options={PRIORITY_OPTIONS}
    onChange={(v) => handleFieldChange("priority", v)}
    className="w-full"
  />
  <div className="mt-1.5 text-[11px] text-slate-400">
    当前版本仅存储，不参与调度与执行。
  </div>
</div>
```

stream `EnvConfigContent.tsx` 同样增加三块，且**保留** checkpoint 字段。

- [ ] **Step 3: 确认保存链路已透传 env**

已有 `env: envConfig` 写入保存 payload（`workflow/index.tsx` / multi hooks）。无需改 API；确认新字段未在序列化时被手动白名单丢弃。若某处 `pick(['jobMode','parallelism'])`，扩展白名单。

```powershell
rg -n "jobMode|parallelism|envConfig|defaultEnvConfig" seatunnel-web-ui/src/pages/batch-link-up seatunnel-web-ui/src/pages/stream-link-up --glob "*.{ts,tsx}"
```

- [ ] **Step 4: Commit**

```powershell
git add seatunnel-web-ui/src/pages/batch-link-up/workflow seatunnel-web-ui/src/pages/stream-link-up/workflow
git commit -m "feat(ui): add bandwidth quota and priority fields to env panel"
```

---

### Task 6: 后端 JobEnvConfig + EnvConfigBuilder（带宽进 HOCON）

**Files:**
- Modify: `seatunnel-web-spi/src/main/java/org/apache/seatunnel/web/spi/bean/dto/config/JobEnvConfig.java`
- Modify: `seatunnel-web-core/src/main/java/org/apache/seatunnel/web/core/builder/EnvConfigBuilder.java`
- Create: `seatunnel-web-core/src/test/java/org/apache/seatunnel/web/core/builder/EnvConfigBuilderTest.java`

- [ ] **Step 1: 写失败单测**

创建 `EnvConfigBuilderTest.java`：

```java
package org.apache.seatunnel.web.core.builder;

import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.spi.bean.dto.config.BatchJobEnvConfig;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvConfigBuilderTest {

    @Test
    void shouldRenderReadLimitIntoEnvHocon() {
        EnvConfigBuilder builder = new EnvConfigBuilder(Collections.emptyList());
        BatchJobEnvConfig env = new BatchJobEnvConfig();
        env.setJobMode(JobMode.BATCH);
        env.setParallelism(2);
        env.setReadLimitBytesPerSecond(1048576L);
        env.setReadLimitRowsPerSecond(1000L);
        env.setPriority("MEDIUM");

        String hocon = builder.build(env);

        assertTrue(hocon.contains("read_limit.bytes_per_second") || hocon.contains("\"read_limit.bytes_per_second\""));
        assertTrue(hocon.contains("1048576"));
        assertTrue(hocon.contains("1000"));
        // priority must NOT be required in engine env
    }

    @Test
    void shouldOmitReadLimitWhenNull() {
        EnvConfigBuilder builder = new EnvConfigBuilder(Collections.emptyList());
        BatchJobEnvConfig env = new BatchJobEnvConfig();
        env.setJobMode(JobMode.BATCH);
        env.setParallelism(1);

        String hocon = builder.build(env);
        assertTrue(!hocon.contains("read_limit"));
    }
}
```

> 若 `JobMode` 枚举 API 不同，按项目实际 setter 调整。`BatchJobEnvConfig` 继承 `JobEnvConfig` 即可。

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\mvnw.cmd -pl seatunnel-web-core -am test -Dtest=EnvConfigBuilderTest
```

Expected: FAIL（字段/逻辑不存在）

- [ ] **Step 3: 扩展 JobEnvConfig**

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobEnvConfig {

    private JobMode jobMode;
    private Integer parallelism;

    /** SeaTunnel env: read_limit.bytes_per_second */
    private Long readLimitBytesPerSecond;

    /** SeaTunnel env: read_limit.rows_per_second */
    private Long readLimitRowsPerSecond;

    /**
     * HIGH / MEDIUM / LOW — stored on job env JSON only; not applied by engine.
     */
    private String priority;
}
```

Jackson 默认会按属性名反序列化前端的 `readLimitBytesPerSecond` / `priority`。若前端改为 snake 键，再加 `@JsonProperty`。

- [ ] **Step 4: EnvConfigBuilder.fillCommonConfig 写入带宽**

在 `fillCommonConfig` 末尾：

```java
if (envConfig.getReadLimitBytesPerSecond() != null
        && envConfig.getReadLimitBytesPerSecond() > 0) {
    envMap.put("read_limit.bytes_per_second", envConfig.getReadLimitBytesPerSecond());
}
if (envConfig.getReadLimitRowsPerSecond() != null
        && envConfig.getReadLimitRowsPerSecond() > 0) {
    envMap.put("read_limit.rows_per_second", envConfig.getReadLimitRowsPerSecond());
}
// do NOT put priority into engine envMap
```

确认 `fillExtConfig` 调用的方法名与 `EnvConfigExtender` 接口一致（`fill`，不是错误的 `fills`）。

- [ ] **Step 5: 跑通测试**

```powershell
.\mvnw.cmd -pl seatunnel-web-core -am test -Dtest=EnvConfigBuilderTest
```

Expected: PASS

- [ ] **Step 6: Commit**

```powershell
git add seatunnel-web-spi/src/main/java/org/apache/seatunnel/web/spi/bean/dto/config/JobEnvConfig.java seatunnel-web-core/src/main/java/org/apache/seatunnel/web/core/builder/EnvConfigBuilder.java seatunnel-web-core/src/test/java/org/apache/seatunnel/web/core/builder/EnvConfigBuilderTest.java
git commit -m "feat(env): persist bandwidth limits into SeaTunnel env HOCON"
```

---

### Task 7: 分钟级链路动态调度

**Files:**
- Modify: `seatunnel-web-ui/src/pages/batch-link-up/workflow/components/ScheduleConfigContent/types.ts`（`scheduleType`）
- Modify: `.../ScheduleTimeSection/types.ts`（batch + stream）
- Modify: `.../ScheduleTimeSection/utils.ts`（batch + stream）
- Modify: `.../ScheduleTimeSection/index.tsx`（batch + stream）
- Create: `.../ScheduleTimeSection/components/MinuteScheduleFields.tsx`（batch + stream 各一份，或抽 common；YAGNI 可复制）

- [ ] **Step 1: 扩展类型**

```ts
export type ScheduleType = "minute" | "hour" | "day" | "week";

// ScheduleConfig / ScheduleTimeValue 中 scheduleType 同步
// 新增：
minuteValue?: {
  intervalMinute: number; // 1-59
};
```

- [ ] **Step 2: buildCron 增加 minute 分支**

```ts
export const defaultMinuteValue = { intervalMinute: 5 };

export const buildCron = (
  scheduleType: ScheduleType,
  hourMode: HourMode,
  hourlyRange: HourlyRangeModeValue,
  hourlyAppoint: HourlyAppointModeValue,
  daily: DailyModeValue,
  weekly: WeeklyModeValue,
  minuteValue: { intervalMinute: number } = defaultMinuteValue
) => {
  if (scheduleType === "minute") {
    const n = Math.min(59, Math.max(1, Number(minuteValue?.intervalMinute) || 5));
    // Quartz: second minute hour day-of-month month day-of-week
    return `0 0/${n} * * * ?`;
  }
  // ... existing hour/day/week unchanged
};
```

- [ ] **Step 3: MinuteScheduleFields.tsx**

```tsx
import { Form, InputNumber } from "antd";
import { formItemStyle, labelNodeStyle } from "../constants";

interface Props {
  minuteValue: { intervalMinute: number };
  onChange: (patch: any) => void;
}

const MinuteScheduleFields: React.FC<Props> = ({ minuteValue, onChange }) => (
  <Form.Item
    style={formItemStyle}
    label={<span style={labelNodeStyle}>每隔（分钟）</span>}
    required
  >
    <InputNumber
      min={1}
      max={59}
      precision={0}
      value={minuteValue?.intervalMinute ?? 5}
      onChange={(v) =>
        onChange({
          minuteValue: { intervalMinute: Number(v) || 5 },
        })
      }
    />
  </Form.Item>
);

export default MinuteScheduleFields;
```

- [ ] **Step 4: ScheduleTimeSection 接入**

- options 增加 `{ label: "分钟", value: "minute" }`
- `useMemo` 的 `buildCron` 传入 `minuteValue`
- `scheduleType === "minute"` 时渲染 `MinuteScheduleFields`

- [ ] **Step 5: stream 侧同等修改**

路径镜像：`seatunnel-web-ui/src/pages/stream-link-up/workflow/components/ScheduleConfigContent/...`

- [ ] **Step 6: 本地快速校验 cron 字符串**

在浏览器控制台或临时 node：

```js
// 期望 interval=5 → "0 0/5 * * * ?"
```

保存任务后用现有 CronPreview 看最近 5 次执行时间。

- [ ] **Step 7: Commit**

```powershell
git add seatunnel-web-ui/src/pages/batch-link-up/workflow/components/ScheduleConfigContent seatunnel-web-ui/src/pages/stream-link-up/workflow/components/ScheduleConfigContent
git commit -m "feat(schedule): support minute-level cron for link dynamic scheduling"
```

---

### Task 8: MySQL-CDC pluginName + SPI 修复

**Files:**
- Modify: `seatunnel-web-datasource-plugins/seatunnel-web-datasource-mysql-cdc/src/main/java/org/apache/seatunnel/plugin/datasource/mysql/cdc/builder/MysqlCdcSourceBuilder.java`
- Modify/Create: `seatunnel-web-datasource-plugins/seatunnel-web-datasource-mysql-cdc/src/main/resources/META-INF/services/org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder`（以仓库现有 SPI 接口全名为准）
- 对照其他插件 SPI：`seatunnel-web-datasource-mysql` 的 `META-INF/services/*`
- 检查: `seatunnel-web-dist` / `Dockerfile` / spring-boot repackage 是否合并 SPI

- [ ] **Step 1: 读取现有 builder 与 SPI 约定**

```powershell
rg -n "pluginName|class MysqlCdc" seatunnel-web-datasource-plugins/seatunnel-web-datasource-mysql-cdc -g "*.java"
Get-ChildItem -Recurse seatunnel-web-datasource-plugins/seatunnel-web-datasource-mysql/src/main/resources
Get-ChildItem -Recurse seatunnel-web-datasource-plugins/seatunnel-web-datasource-mysql-cdc/src/main/resources -ErrorAction SilentlyContinue
```

- [ ] **Step 2: 修复 pluginName**

`MysqlCdcSourceBuilder.pluginName()` 返回：

```java
@Override
public String pluginName() {
    return "MYSQL-CDC";
}
```

（与 `DataSourceSourceBuilder.getRequiredPluginName()` 的 `toUpperCase()` 结果一致。）

若 `MySQLCDCSourceOptionRule.pluginName()` 也参与 BUILDER_MAP，同步改为 `MYSQL-CDC`。

- [ ] **Step 3: 确保 SPI 文件存在**

参考 mysql 模块 services 文件格式，为 mysql-cdc 增加：

`META-INF/services/<DataSourceHoconBuilder 全限定名>`

内容一行：

```
org.apache.seatunnel.plugin.datasource.mysql.cdc.builder.MysqlCdcSourceBuilder
```

若工厂加载的是 `SourceOptionRule` 或其他接口，按 `ServiceLoader.load(...)` 实际类型创建对应 services 文件（可同时检查 `DataSourceProcessor` SPI）。

- [ ] **Step 4: nested jar / 打包**

若运行在 Spring Boot fat jar 且仍缺 SPI：

1. 确认 `seatunnel-web-datasource-all` 依赖包含 mysql-cdc
2. 检查 shade/spring-boot-maven-plugin 是否使用 `ServicesResourceTransformer` / `AppendingTransformer` 合并 `META-INF/services`
3. 最小修复优先：保证插件类与 SPI 落在应用 ClassLoader 可见路径（与其它已工作 datasource 一致）

- [ ] **Step 5: 验证 builder 可解析**

```powershell
.\mvnw.cmd -pl seatunnel-web-datasource-plugins/seatunnel-web-datasource-mysql-cdc,seatunnel-web-core -am test -Dtest=*Cdc* 
```

或启动应用后创建实时 MySQL-CDC 任务，确认不再出现 `No builder found for plugin: MYSQL-CDC` / NPE。

- [ ] **Step 6: Commit**

```powershell
git add seatunnel-web-datasource-plugins/seatunnel-web-datasource-mysql-cdc
# 若改了 dist 打包，一并 add
git commit -m "fix(cdc): align MYSQL-CDC pluginName and SPI registration"
```

---

### Task 9: 联调验收与收尾

**Files:** none（验证）

- [ ] **Step 1: 按成功标准走查**

| # | 检查项 | 通过标准 |
| --- | --- | --- |
| 1 | 菜单 | 引接链路（离线/实时） |
| 2 | 列表 | 链路名称、健康状态、负载情况语义 |
| 3 | detail | 物理路由配置文案 |
| 4 | single | 逻辑关系配置文案 |
| 5 | Env | 带宽+优先级可存可回显；优先级提示仅存储 |
| 6 | HOCON | 有带宽时 env 含 read_limit.* |
| 7 | 调度 | 分钟级 cron 可预览/保存 |
| 8 | CDC | MySQL-CDC 配置不再因 builder 缺失失败 |

- [ ] **Step 2: 跑后端相关测试**

```powershell
.\mvnw.cmd -pl seatunnel-web-core -am test -Dtest=EnvConfigBuilderTest
```

- [ ] **Step 3: 最终 commit（若有遗漏修复）**

```powershell
git status -sb
git add -u
git commit -m "chore: polish link management contract acceptance fixes"
```

---

## Spec coverage checklist

| Spec 要求 | Task |
| --- | --- |
| 链路管理菜单+列表语义化 | Task 2, 3 |
| 物理路由 detail | Task 4 |
| 逻辑关系 single | Task 4 |
| 带宽 env + HOCON | Task 5, 6 |
| 优先级仅存储 | Task 5, 6 |
| 分钟级调度 | Task 7 |
| 健康状态映射 | Task 2, 3 |
| 负载情况 + CDC | Task 3, 8 |
| 尽量少改 / 不改路由 API | 全程 |
| 功能分支 | Task 1 |

---

## Plan self-review

1. **Coverage:** 指标 #12 子项均有 Task；非目标（合并菜单、优先级生效、新服务）未写入实现步骤。  
2. **Placeholders:** 无 TBD；SPI 全名要求实现时以仓库 `ServiceLoader` 实际接口为准（Step 内已写明对照方法）。  
3. **Types:** 前端 `readLimitBytesPerSecond` / `priority: HIGH|MEDIUM|LOW` 与后端 `JobEnvConfig` 字段一致；cron 为 Quartz 6 域。  
4. **Worktree risk:** Task 1 处理索引异常，避免在半删除树上改代码。
