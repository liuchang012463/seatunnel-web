# 数据中台引接分系统 · Design System

> 全站设计基线。任何新样式只允许引用 §3 令牌；令牌名与取值以 `seatunnel-web-ui/src/design-system.less` 的 `:root` 为准，§3 与该文件保持同步。

## 1. 方向与硬边界

**值班仪表台，不是游戏 HUD**：深浅分层 + 发丝线分区；信息靠排版与等宽数字；唯一亮色是青色强调与状态语义色；胆量只花在**状态优先的高密度数据表**上。

- 海军蓝（页面 `#000E24`、面板 `#000F23` + 青色强调）；禁止霓虹辉光、赛博朋克、大面积渐变、Card 套 Card、通用 AI 仪表盘。
- 高密度一等公民：默认紧凑表，一屏看更多行。
- 状态永远「色 + 文」双通道。
- 等宽仅用于 IP / JDBC URL / 任务 ID / 指标数字（`tabular-nums`）。
- 动效仅 hover / focus / 展开，`120–160ms`；尊重 `prefers-reduced-motion`。
- 英文眉题仅三栏工作台区域标识（CATALOG / INSPECTOR）；标题靠字号与字重，不用变色/斜体。

## 2. 原则

1. **状态先行**：第一层「健康吗」，第二层才是配置；失败可从行内直达原因。
2. **结构靠留白与发丝线**：同屏最多一层边框；禁止 bordered panel 套 bordered table。
3. **一屏一主操作**：每页一个主按钮；行内 ≤2 可见 +「更多」；危险动作进「更多」并二次确认。
4. **数字即界面**：指标右对齐、等宽、带单位；时间 `YYYY-MM-DD HH:mm`（表内可压成 `MM-DD HH:mm`，悬浮给全年）；代码类值等宽、不截断语义。
5. **说人话**：菜单 = 页头 = 面包屑同词；不暴露内部码；按钮「动词+宾语」，toast 同词；失败 = 什么坏了 + 下一步。

## 3. Design Tokens（全站唯一来源）

### 3.1 表面（由深到浅）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--st-color-bg-app` | `#000E24` | 应用基底（`--st-color-bg-primary` 为同值别名） |
| `--st-color-bg-page` | `#000E24` | 内容画布 |
| `--st-color-bg-panel` | `#000F23` | 一级面板 / 表格容器（`--st-color-bg-secondary` 为同值别名） |
| `--st-color-bg-elevated` | `#001630` | 浮层背景；交互轨道 `--st-color-bg-interactive` 为 `#092741` |
| `--st-color-bg-control` | `#00122C` | 输入框、表内控件、行内代码底 |
| `--st-color-bg-hover` | `#0A2D48` | 行/项 hover（旧名 `--st-color-hover`） |
| `--st-color-bg-selected` | `#093D7E` | 选中背景（旧名 `--st-color-selected`） |

### 3.2 文字

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--st-color-text-primary` | `#E1EAF3` | 标题、名称、关键数值 |
| `--st-color-text-secondary` | `#C4D1DE` | 正文、表格内容 |
| `--st-color-text-muted` | `#8B9FB5` | 辅助、占位、单位 |
| `--st-color-on-primary` | `#FFFFFF` | primary 上反白 |

### 3.3 强调与语义

| 令牌 | 值 | 语义 |
| --- | --- | --- |
| `--st-color-accent` | `#00C4E5` | 链接、焦点、主操作、进行中 |
| `--st-color-accent-strong` | `#E1EAF3` | hover |
| `--st-color-primary` | `#126681` | 主按钮填充；表头与弹窗标题使用独立 `--st-color-header: #0E5C78` |
| `--st-color-primary-active` | `#093D7E` | 主按钮按下 |
| `--st-color-success` | `#21D36A` | 正常 / 完成 / CONSISTENT |
| `--st-color-warning` | `#E2C626` | 降级 / 漂移 / 待处理（稀缺；「未做过」用 neutral） |
| `--st-color-error` | `#EB5058` | 失败 / MISSING / 危险动作 |
| `--st-color-neutral` | `#9CACBF` | 未启用 / 未检测 / UNBOUND |
| 语义底 | 同色 12% 透明 | StatusChip 背景 |
| `--st-color-line` | `#124769` | 发丝线（旧名 `--st-color-divider`；更浅一级 `--st-color-divider-subtle`） |
| `--st-color-line-strong` | `#2187A8` | 分区线、表头底线（旧名 `--st-color-border`） |
| `--st-color-focus` | `#00C4E5` | 键盘焦点环颜色，按 `box-shadow: 0 0 0 2px var(--st-color-focus)` 使用 |
| `--st-color-modal-mask` | `#000714B8` | 弹层遮罩 |
| `--st-panel-shadow` | `0 8px 24px rgba(0,0,0,.32)` | 面板悬浮阴影 |

状态映射（StatusChip 唯一实现）：

| 状态族 | success | warning | error | processing | neutral |
| --- | --- | --- | --- | --- | --- |
| 连通 | CONNECTED_SUCCESS | — | CONNECTED_FAILED | CONNECTING | CONNECTED_NONE |
| 生命周期 | ENABLED | — | — | — | DISABLED/REVOKED |
| 探查 | SUCCESS | QUEUED | FAILED | RUNNING | 未探查 |
| 资源(入湖) | READY | — | ERROR/MISSING | CREATING/DELETING | DELETED/UNBOUND |
| 一致性 | CONSISTENT | DRIFT | MISSING | — | UNKNOWN |
| 任务健康 | 已完成/健康 | 低速/延迟 | 失败/终止 | 运行中 | 未运行 |

### 3.4 字体

| 令牌 | 值 |
| --- | --- |
| `--st-font-family` | `"Microsoft YaHei",Helvetica,Arial,sans-serif` |
| `--st-font-digital` | `"DS-Digital","Microsoft YaHei",sans-serif` |

| 令牌 | 字号/行高 |
| --- | --- |
| `--st-type-large-title-size/line` | 20/28 |
| `--st-type-title-size/line` | 20/28（页标题） |
| `--st-type-title-content-size/line` | 16/24（面板 / 弹层标题） |
| `--st-type-content-lg-size/line` | 16/24 |
| `--st-type-content-md-size/line` | 14/22（表单、说明） |
| `--st-type-content-sm-size/line` | 14/20（表格、列表默认） |
| `--st-type-caption-size/line` | 14/20（辅助信息） |

标题在实现中统一 `font-weight: 400`：层级靠字号与颜色区分，不加粗。仓库没有 `--st-mono`，等宽场景使用 `--st-font-digital` 并配 `tabular-nums`。

### 3.5 几何与间距

| 项 | 值 |
| --- | --- |
| 圆角 | `--st-radius-sm: 3px` · `--st-radius-md: 6px` · `--st-radius-lg: 10px` |
| 控件高 | `--st-field-height: 30px`（输入）· `--st-control-height: 32px`（按钮）· 大档 `38px` |
| 边框 | `--st-border-width: 1px`；同屏只允许面板一层 |
| 表头 / 表行 | 均 `38px`（`design-system.less` 统一；`density` 切换尚未实现，见 §6 P1） |
| 间距 | `4/8/12/16/20/24`；页 gutter `20`；面板间距 `12`；面板内边距 `16 20` |
| 侧栏 | 展开 `208` / 收起 `56`（默认展开，记忆） |
| 顶栏 | `48px` |
| 动效 | `120ms/160ms ease-out` |

## 4. 核心组件

### 4.1 StatusChip

`● 文本（·附加值）`：8px 圆点 + 14px 文本 + 可选等宽附加；背景 12% 同色，圆角 2px，高 22px；Tooltip 写判定时间/原因。禁止纯彩字、禁止同屏第三种状态样式。

### 4.2 DenseTable

- 表头：底色 `--st-color-header`（`#0E5C78`），14px/600，与弹窗标题栏同源。
- 行：空闲行按面板/浮层底交替；悬停 `#0A2D48`、选中 `#093D7E` 优先，固定列同色。
- 列型：名称（主+14px muted 副行）· 状态（StatusChip）· 指标（右对齐等宽+单位）· 代码（等宽+tooltip）· 时间（紧凑格式）· 操作（≤2 + ⋯）。
- 动作：1 主动作 + 1 次动作 +「更多」；删除/注销仅在「更多」，红字 + 影响确认。
- 四态：Loading=Skeleton×3 · Empty=说明+主操作 · Error=Alert+重试 · Partial=可显示部分+UNKNOWN。
- `density="compact|default"`，页头可切换并记忆。

### 4.3 FilterToolbar

单行：`[搜索 240px] [下拉…] [分类 Segmented(含计数)] —— [视图] [主按钮]`。无 label 冒号；二级筛选取 Popover；排序只在列头；即时生效，无「搜索/重置」对。

### 4.4 PageShell

- 侧栏：分组 caption + 图标 + 文字 + 计数；收起带 Tooltip。
- 顶栏：产品名 · 环境徽标 · 当前页标题 · 健康摘要（`运行中 n · 失败 n`）· 主题 · 用户。
- 水印默认关（仅审计）；深链空页必须有壳 + 返回。

### 4.5 Workbench（探查三栏）

比例 `240 / 1fr / 304`，`--st-line` 分隔无外框；资源为行式列表（图标 + 名称 + 类型·连接串 mono muted + 归属 + StatusChip），禁止 card-in-card。

### 4.6 BatchBar

选中 >0 才出现：`已选 n ｜ 主动作 ≤3 ｜ 更多▾ ｜ 取消`。

### 4.7 图表

色阶 `['#00C4E5','#41859A','#2187A8','#126681','#9CACBF']`；轴字 muted、轴线 `--st-line`；柱无渐变无描边；KPI 图标一律 accent 单色。

## 5. 页面要点

**数据源**：列表默认、卡片可选；连通/生命周期/探查三个 StatusChip（正常弱化、失败醒目）；行内 `检测`+`探查`+⋯；新建弹层同主题。

**探查**：资源改行式；Inspector 给列画像（名/类型/空值率/分布）；空环改「暂无数据 + 发起探查」；紧凑行 36px。

**引接**：健康列提前 + StatusChip（失败原因 tooltip + 行内看日志）；链路 `源 → 目标` mono 单行截断；执行概况为行内指标组（耗时｜行数｜QPS），无 label 冒号。

## 6. 工程路径

1. **令牌**：`design-system.less` 是 `--st-*` 令牌的唯一实现，§3 与其同步；新增或改值先落 less，再更新 §3。
2. **antd**：`styles/theme-tokens.ts` 为构建与运行时提供同一套实际颜色；`antd-theme.ts` 映射官方组件 Token，`antd-components.less` 处理强覆盖与结构适配。
3. **Tailwind**：按域删类名映射层与内联劫持。
4. **浅色**：修复完成前隐藏切换；v2 用 `defaultAlgorithm` 重做。
5. **ECharts**：注册 `echarts-theme-st`，逐页切 theme。

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 地基 | 令牌 v2、antd 正规化、StatusChip / DenseTable / FilterToolbar、壳层、浅色冻结 | 截图回归；intl 刷屏清零 |
| P1 高频 | 数据源、引接列表、任务概览、新建弹层、术语统一 | 密度↑；动作 ≤2+更多；失败行内可达 |
| P2 深层 | 探查 Inspector、详情壳+返回、四态、水印 | 深链无裸空页 |
| P3 收尾 | 入湖/运维套组件、浅色 v2（可选） | 全站一套令牌；`!important` 大幅下降 |
