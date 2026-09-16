# SeaTunnel Web 已完成功能页面 UI/UX 审计报告

**审计日期：** 2026-09-16  
**审计范围：** 桌面 Web，Chrome，1440 × 1000 CSS px；不包含移动端  
**审计对象：** `seatunnel-web-ui` 已接入业务页面、创建/配置向导、详情页、空态、错误态和主题切换

## 1. 结论摘要

当前产品已经形成了可识别的深色数据中台视觉基线：深青蓝表面、青色强调、状态标签和数据工作台结构都比较稳定。数据探查三栏工作台、数据源创建向导、物理入湖详情和 OpenAPI 页面，是现阶段最完整的页面模式。

但全站还处在“新设计系统覆盖旧页面实现”的过渡期。主要问题不是缺少装饰，而是：同一任务需要重复确认、表格信息在桌面视口内被压缩或截断、深浅主题使用两套几何语言、技术状态与用户动作没有完全对齐。结果是页面看起来像同一个产品，但操作路径仍然需要用户不断猜测下一步。

### 总体判断

| 维度 | 评价 | 主要原因 |
| --- | --- | --- |
| 视觉统一性 | 6/10 | 深色主壳稳定，但知识管理、任务工作流、告警/抽屉仍保留白色/紫色/大圆角语言 |
| 任务效率 | 5/10 | 筛选、行内动作和创建向导信息量偏大；源/目标配置存在重复确认 |
| 状态与错误引导 | 6/10 | 空态和前置条件提示已有基础，但深链、权限/失败原因和重试路径不够一致 |
| 桌面可用性 | 6/10 | 1440px 下主体可用，但任务表横向滚动、操作列截断和密集控件影响扫描与点击 |
| 可访问性证据 | 5/10 | 有全局 focus-visible 和 aria-label 基础；仍发现表单字段缺少 id/name，尚未完成键盘/对比度全量验证 |

## 2. 证据与限制

本轮先运行前端并实际进入页面、创建流程和深层路由，再保存并逐张检查截图。共归档 41 张桌面 Web 截图，位置为 [`tmp/ui-audit-2026-09/`](../tmp/ui-audit-2026-09/)。代表性证据：

- [数据源列表](../tmp/ui-audit-2026-09/01-data-source-list.png)、[卡片视图](../tmp/ui-audit-2026-09/01b-data-source-card.png)、[创建类型选择](../tmp/ui-audit-2026-09/31-data-source-create-modal.png)、[连接表单](../tmp/ui-audit-2026-09/32-data-source-form.png)
- [探查概览](../tmp/ui-audit-2026-09/03-exploration-overview.png)、[探查任务](../tmp/ui-audit-2026-09/04-exploration-tasks.png)、[探查结果工作台](../tmp/ui-audit-2026-09/05-exploration-results.png)
- [批量引接列表](../tmp/ui-audit-2026-09/39-batch-list-metrics.png)、[任务基础配置](../tmp/ui-audit-2026-09/24-batch-create-step1.png)、[连接校验](../tmp/ui-audit-2026-09/25-batch-client-connect-step2.png)、[单表配置](../tmp/ui-audit-2026-09/26-batch-single-config-real.png)、[多表配置](../tmp/ui-audit-2026-09/27-batch-multi-config-real.png)、[脚本配置](../tmp/ui-audit-2026-09/28-batch-script-config-real.png)
- [知识管理深色主题](../tmp/ui-audit-2026-09/20-knowledge-management.png)、[知识管理浅色主题](../tmp/ui-audit-2026-09/34-knowledge-light.png)、[主数据业务系统页](../tmp/ui-audit-2026-09/40-master-data-business-system.png)
- [物理入湖详情](../tmp/ui-audit-2026-09/41-physical-resource-detail.png)、[OpenMetadata 配置](../tmp/ui-audit-2026-09/35-metadata-engine-config.png)、[Doris 配置](../tmp/ui-audit-2026-09/36-warehouse-config.png)、[OpenAPI](../tmp/ui-audit-2026-09/38-open-api-loaded.png)

前后端启动条件影响了真实数据验证：前端以 mock 数据运行；后端构建成功，但连接 `.env` 中 MySQL 时被当前沙箱的网络权限拦截，未启动真实后端服务。因此本报告可以确认页面布局、交互结构、主题、空态和前端错误处理，但不能据此确认真实权限、接口成功/失败文案、保存结果或生产数据状态。未执行 Compose、部署或重启容器。

OpenAPI 页面首次等待时仍显示 loading，等待约 8 秒后成功加载 42 个 Controller、247 个接口；因此本轮把它记录为“首屏等待偏长的体验风险”，没有判定为永久 skeleton 缺陷。

## 3. 页面覆盖与健康度

### 已完成功能页面

| 页面/功能族 | 覆盖页面 | 一般健康度 | 关键证据 |
| --- | --- | --- | --- |
| 全局壳与导航 | 顶栏、侧栏、主题切换、页面返回 | 一般 | [`33`](../tmp/ui-audit-2026-09/33-data-source-light.png)、[`20`](../tmp/ui-audit-2026-09/20-knowledge-management.png) |
| 数据源与主数据 | 数据源列表/卡片/新建、单位、业务系统 | 一般 | [`01`](../tmp/ui-audit-2026-09/01-data-source-list.png)、[`32`](../tmp/ui-audit-2026-09/32-data-source-form.png)、[`40`](../tmp/ui-audit-2026-09/40-master-data-business-system.png) |
| 数据探查 | 概览、任务配置、结果工作台 | 较好 | [`03`](../tmp/ui-audit-2026-09/03-exploration-overview.png)、[`05`](../tmp/ui-audit-2026-09/05-exploration-results.png) |
| 任务监控与引接 | 任务洞察、批量/实时/文件引接、文件资源 | 一般 | [`06`](../tmp/ui-audit-2026-09/06-metrics.png)、[`11`](../tmp/ui-audit-2026-09/11-file-ingest.png)、[`13`](../tmp/ui-audit-2026-09/13-file-resources.png) |
| 任务创建与配置 | 基础配置、客户端连接、单表/增量/多表/脚本/文件工作流 | 一般偏高风险 | [`24`](../tmp/ui-audit-2026-09/24-batch-create-step1.png)、[`26`](../tmp/ui-audit-2026-09/26-batch-single-config-real.png)、[`27`](../tmp/ui-audit-2026-09/27-batch-multi-config-real.png) |
| 运维与引擎 | Client、OpenMetadata、Doris、告警 | 一般 | [`08`](../tmp/ui-audit-2026-09/08-client.png)、[`09`](../tmp/ui-audit-2026-09/09-metadata-engine.png)、[`07`](../tmp/ui-audit-2026-09/07-alarm.png) |
| 入湖管理 | 物理入湖、资源详情、数据湖、生命周期、逻辑入湖 | 一般 | [`16`](../tmp/ui-audit-2026-09/16-lake-resources.png)、[`18`](../tmp/ui-audit-2026-09/18-lake-lifecycle.png)、[`41`](../tmp/ui-audit-2026-09/41-physical-resource-detail.png) |
| 系统管理 | 知识管理、开放接口、采集报告 | 一般 | [`20`](../tmp/ui-audit-2026-09/20-knowledge-management.png)、[`38`](../tmp/ui-audit-2026-09/38-open-api-loaded.png)、[`22`](../tmp/ui-audit-2026-09/22-reporting-reports.png) |

### 不纳入“已完成功能”评分的正式占位入口

`/reporting/forms`、`/sync/cloud-edge-tasks`、`/sync/edge-access-tasks`、`/sync/links`、`/sync/topology`、`/bi`、`/operations/protocol`、`/operations/diagnostics` 当前统一进入占位页。[`23-bi-placeholder.png`](../tmp/ui-audit-2026-09/23-bi-placeholder.png) 证明占位文案是诚实的；这些页面应作为产品路线边界保留，不应继续伪装成已完成页面。

## 4. 按用户任务的审计结论

1. **进入系统并定位功能：一般。** 侧栏分组清楚，当前项有青色激活线；但侧栏分组、菜单名、页面标题和技术模块名称仍有 `Client`、`Controller`、`OpenMetadata` 等混用，跨组定位需要学习成本。

2. **查找和管理数据源：一般。** 列表默认视图和卡片切换合理，连接/启用/探查状态可直接扫描；筛选控件多、状态筛选换行，卡片与列表的操作层级也不完全一致。[`01`](../tmp/ui-audit-2026-09/01-data-source-list.png)、[`01b`](../tmp/ui-audit-2026-09/01b-data-source-card.png)

3. **创建数据源：较好但偏重。** 类型选择的分类和计数有效，连接表单有“连接测试不等于元数据权限”的重要说明；但常用 JDBC 与具体 JDBC 连接器重复出现，连接表单在弹层内纵向很长，底部“连接测试/完成”同时出现，容易让用户在未填完时误判可直接提交。[`31`](../tmp/ui-audit-2026-09/31-data-source-create-modal.png)、[`32`](../tmp/ui-audit-2026-09/32-data-source-form.png)

4. **发起数据探查并查看结果：较好。** 三栏工作台的目录、结果和检查器职责明确，是全站最有产品识别度的模式；0 值概览中的下一步操作仍不够集中，任务配置页的“开始”操作在窄列中容易被压缩。[`03`](../tmp/ui-audit-2026-09/03-exploration-overview.png)、[`04`](../tmp/ui-audit-2026-09/04-exploration-tasks.png)、[`05`](../tmp/ui-audit-2026-09/05-exploration-results.png)

5. **创建和配置引接任务：一般偏高风险。** 任务类型选择、客户端连接测试、工作流编辑器的概念是对的；但基础配置与连接页重复选择源/目标，之后又进入另一套逻辑配置界面。画布在 1440px 视口中空白面积大、节点小、右侧栏图标优先，用户需要同时理解“物理路由、逻辑关系、发布状态、调度、环境、HOCON”等多个概念。[`24`](../tmp/ui-audit-2026-09/24-batch-create-step1.png)、[`25`](../tmp/ui-audit-2026-09/25-batch-client-connect-step2.png)、[`26`](../tmp/ui-audit-2026-09/26-batch-single-config-real.png)、[`27`](../tmp/ui-audit-2026-09/27-batch-multi-config-real.png)、[`28`](../tmp/ui-audit-2026-09/28-batch-script-config-real.png)

6. **监控、启停和处理任务：一般。** 状态、执行概况、链路动态和操作均有信息，但任务列表横向信息量超过首屏可读范围；在 1440px 下表格 body 的 `scrollWidth` 为约 1509px、可视宽度约 1182px，用户需要在表内横向滚动。代码中行内动作还同时使用 `disabled` 和点击前置条件提示，真正 disabled 的按钮不会触发 `onClick`，用户只能看到“点不了”而得不到原因。[`39`](../tmp/ui-audit-2026-09/39-batch-list-metrics.png)

7. **入湖、运维和系统配置：一般。** 配置页的信息层级、版本固定提示和连接说明较好；入湖详情的资源状态与 ODS 操作也有完整结构。生命周期、逻辑入湖和知识管理仍有面板过多、英文内部术语、重复主操作或技术列暴露的问题。[`35`](../tmp/ui-audit-2026-09/35-metadata-engine-config.png)、[`36`](../tmp/ui-audit-2026-09/36-warehouse-config.png)、[`41`](../tmp/ui-audit-2026-09/41-physical-resource-detail.png)

8. **处理空态、错误态和深链：一般偏低。** 告警空态和 ODS 未准备 guard 的 CTA 清楚；但任务详情不存在时需要用户“回到列表重新进入”，没有保留原筛选上下文，资源创建 guard 也缺少直接跳转到具体准备动作的路径。[`24-batch-detail.png`](../tmp/ui-audit-2026-09/24-batch-detail.png)、[`37-lake-table-create-guard.png`](../tmp/ui-audit-2026-09/37-lake-table-create-guard.png)

## 5. 关键问题清单

### P1：桌面表格和操作列没有以“可见主操作”为约束

**表现：** 批量、文件引接、探查任务、主数据等列表普遍把多列、状态、动态、启停、下线、更多动作塞进同一行。任务表通过 `scroll={{ x: "max-content" }}` 保留了全部列，但外层 shell 隐藏溢出；主数据页在 1440px 截图中右侧删除动作只剩部分文字。[`40-master-data-business-system.png`](../tmp/ui-audit-2026-09/40-master-data-business-system.png)

**影响：** 用户需要记住横向滚动位置；异常原因和操作入口不在同一视觉范围；固定列或窄列可能让“点击无效”被误认为权限问题。

**代码证据：** [`SyncTaskList/index.tsx`](../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/index.tsx) 定义了 220/120/260/210/220/170/260 多列并启用固定操作列（约 247-342 行），表格使用 `scroll.x = "max-content"`（约 832-846 行）；[`ActionColumn.tsx`](../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/components/ActionColumn.tsx) 同时展示启动、上线/下线和更多动作（约 302 行起）。

### P1：深色主视觉与浅色主题是两套产品语言

**表现：** 深色知识管理页的参数类型、编辑、删除控件是白色高亮；浅色数据源页则使用大面积白色、24px 圆角和胶囊按钮。主题切换当前在顶栏公开，但设计基线又写明“浅色主题冻结”。[`20`](../tmp/ui-audit-2026-09/20-knowledge-management.png)、[`33`](../tmp/ui-audit-2026-09/33-data-source-light.png)、[`34`](../tmp/ui-audit-2026-09/34-knowledge-light.png)

**代码证据：** [`design-system.less`](../seatunnel-web-ui/src/design-system.less) 共 1932 行，其中约 358 处 `!important`；深色基线使用 2-4px 圆角，而浅色覆盖在约 1170 行以后重新定义 24px 卡片/控件圆角和胶囊主按钮。[`app.tsx`](../seatunnel-web-ui/src/app.tsx) 约 95 行仍把主题切换放在全局顶栏。

**影响：** 用户切换主题后，不只是颜色变化，控件尺寸、层级、密度和品牌性格也发生变化；组件维护需要继续添加补丁，而不是复用同一套 token。

### P1：任务创建流程的概念和重复确认过多

**表现：** 基础页先选源/目标，连接页再次确认源/目标和客户端，随后单表、多表、脚本、文件模式又进入近似但不同的编辑器。多表页同时出现匹配模式、双列表、筛选和参数设置；脚本页则直接把用户放入 HOCON 编辑器。

**影响：** 新用户不知道“连接测试通过”后还要配置什么；熟练用户也需要在多个页头、发布状态和右侧设置栏之间切换。

### P1：水印和背景装饰进入业务内容

**表现：** 文件资源管理页能看到 `admin` 对角水印，遮挡资源列表。`app.tsx` 目前除 `/lake/*` 和主数据外默认启用水印，并配置了外部 MDN 背景图。

**影响：** 水印降低文件名、路径和表格的可读性；外部背景资源还会带来加载依赖和视觉噪声。设计文档已经建议“水印默认关，仅审计模式开启”，应与实现对齐。

### P2：术语、文案和状态动作没有完全统一

**表现：** 页面中混用“任务概览/批量数据引接”“Client/引接引擎”“Controller”“Scope”“Healthy”“链路动态调度”等词；内部 tag 名和 operationId 在 OpenAPI 中有价值，但不应成为普通管理页的主层级。表单也大量出现“标签 + 冒号”结构。

**影响：** 用户需要把内部实现词汇翻译成业务含义；相同动作在不同页面的按钮命名、确认文案和 toast 不完全一致。

### P2：可访问性基础已有，但需要系统验收

**已发现：** Chrome DevTools 在任务列表报告了一个表单字段缺少 `id` 或 `name` 的 issue；实际 DOM 中结束日期输入没有 id/name。全局已经有 `focus-visible` 规则，主题按钮和部分图标按钮有 aria-label，但不能替代完整的键盘、读屏和对比度测试。

**限制：** 本报告不宣称通过 WCAG 全量合规验收；截图无法验证读屏顺序、键盘陷阱、动态更新通知和 200% 缩放。

## 6. 修复方案

### 6.1 统一视觉方向

建议将现有 [`DESIGN.md`](../DESIGN.md) 的“值班仪表台”作为正式落地基线：保留深青蓝控制台的产品识别度，但把亮点放在状态和数据表，不再用紫色图标、白色逃生卡、无意义大圆角和层层阴影制造层次。

1. **主题策略：** 当前版本以深色为唯一验收基线；在浅色主题完全 token 化前隐藏主题切换。若必须保留切换，则两套主题只能改变颜色，不得改变圆角、控件高度、表格密度和按钮层级。
2. **令牌策略：** 所有页面只引用 `--st-*` 颜色、字号、间距、圆角和状态 token；逐域删除 Tailwind 的白色/靛蓝/紫色硬编码和内联颜色。
3. **几何策略：** panel 4px、control 3-4px、chip 2px；取消普通业务控件的 16/24/32px 圆角和胶囊化。等宽字体只用于 ID、URL、IP、指标和代码。
4. **状态策略：** 状态必须“颜色 + 文本 + 原因/时间”；正常状态弱化，失败/待处理状态突出，并提供同一行的原因、日志或重试入口。

### 6.2 先建共享组件，再逐域迁移

建议补齐以下共享模式，避免每个页面继续独立修补：

| 组件 | 统一规则 | 首批接入 |
| --- | --- | --- |
| `PageShell/PageHeader` | 页面标题、说明、返回、唯一主操作的固定结构 | 数据源、探查、任务、入湖、配置页 |
| `FilterToolbar` | 搜索 + 2-3 个高频筛选；其余进入“更多筛选” | 数据源、任务、文件、生命周期 |
| `DenseTable` | 1440px 保证名称/状态/主操作可见；代码列 tooltip；首列和操作列 sticky | 所有任务、主数据、知识、入湖列表 |
| `StatusChip` | 全站唯一状态视觉；含文本、语义色和原因 tooltip | 数据源、探查、任务、资源、引擎 |
| `ActionMenu` | 行内最多 1 个主动作 + 1 个次动作 +“更多”；删除/注销只进更多 | 任务、数据源、主数据、资源 |
| `EmptyState/ErrorState` | 说明“现在是什么状态”、唯一下一步、重试/返回路径 | 所有深层路由和接口请求 |
| `WizardFooter` | 当前步骤、未保存提示、上一步/下一步/提交的固定层级 | 数据源、批量/实时/文件任务 |

### 6.3 高频页面改造顺序

**第一优先级：任务列表、主数据和数据源。**

- 任务表改为“名称与健康状态优先”：链路动态、创建时间等进入可选列或二级详情。
- 1440px 下固定操作列完整显示“查看详情/启动”一个主动作，其他动作放入“更多”；父容器不得以 `overflow: hidden` 裁掉可操作内容。
- 启动按钮的前置条件使用 disabled + tooltip/辅助说明，或保留可点击状态并显示“需先上线”的原因，不能同时依赖 disabled 后的 `onClick` 提示。
- 主数据操作列使用图标 + 文本或更宽的固定列，确保编辑、删除完整可见；统一二次确认。
- 数据源创建器合并“常用 JDBC”和连接器列表的重复项；类型卡片展示“显示名 + 协议 + 适用场景”，连接表单分为基础信息、连接参数、权限校验三段，并在未满足必填条件前只保留“下一步”不可提交。

**第二优先级：探查和引接工作台。**

- 探查概览的 0 值区域提供唯一主 CTA“选择数据源并发起探查”，不要只展示一排 0。
- 保留探查三栏骨架，但资源目录改成行式列表；检查器优先展示表结构、空值率和最近探查时间。
- 任务创建收敛为：`1 基础信息 → 2 连接验证 → 3 任务配置`。源/目标只在第 1 步选择，第 2 步只显示摘要、客户端和测试结果。
- 单表/增量/多表/脚本共用同一个工作流页头、保存、校验、预览和发布条；模式差异只出现在主体区域。
- 画布默认以源和目标为视觉中心，缩小无意义空白；右侧“基础/调度/环境”改为带文字的 tab，并为每个未完成配置显示缺项计数。

**第三优先级：入湖、告警、知识和 OpenAPI。**

- 入湖页明确区分“创建 ODS 库”和“对账”：一个是改变资源状态的动作，一个是读取实际状态的动作，不能只靠表头理解。
- 告警空态保留“新建通道”，并在无通道时直接说明“规则发送需要先配置通道”。
- 知识管理的参数类型、编辑、删除、分页统一到深色 token；技术词保留在次级信息，不作为页面主标题。
- OpenAPI 保留技术密度，但 Controller 名称支持友好别名、固定左侧分组、搜索命中高亮；详情使用抽屉，不让用户离开当前列表上下文。
- 默认关闭业务水印；如确需审计水印，仅在审计/导出模式打开，并避免覆盖表格正文。

### 6.4 深链、错误和权限反馈

所有隐藏路由都按同一状态协议实现：

- **Loading：** 页面骨架最多显示合理等待时间；超时显示原因、重试和返回父列表。
- **Empty：** 说明缺少什么，以及唯一下一步；返回时保留父列表的筛选和分页上下文。
- **Error：** 页面级显示“什么失败 + 为什么 + 怎么处理”，toast 只作为辅助，不使用单独的 `Resource not found`。
- **Guard：** 例如 ODS 未准备时，按钮直接链接到对应资源详情或配置页，而不是只提供“返回”。
- **Permission/Precondition：** 发生不可点击状态时，在控件旁显示前置条件；不要让用户通过试点按钮来猜权限。

### 6.5 实施分期与验收

**P0：阻断级体验修复**

- 修复任务/主数据/探查表格的操作列截断和横向滚动层级。
- 修复所有表单字段的 `id/name`、label 关联和 icon-only 控件 aria-label。
- 关闭默认业务水印；统一深色下的白色/紫色/绿色逃生样式。
- 统一 disabled、前置条件、错误和重试反馈。

**P1：共享设计系统和高频页面**

- 落地 `PageHeader`、`FilterToolbar`、`DenseTable`、`ActionMenu`、`EmptyState`。
- 迁移数据源、主数据、批量/实时/文件任务、任务洞察。
- 删除重复筛选按钮、重复主 CTA 和过多行内动作。

**P2：深层工作流和入湖域**

- 重构任务创建三步流程及单表/增量/多表/脚本模式的共用页头。
- 迁移入湖、告警、知识、OpenAPI、配置页；补齐深链 guard 和返回上下文。

**验收门槛：**

- 1440 × 1000 桌面视口下，所有列表的名称、状态和主操作完整可见；需要横向滚动时有明确滚动提示且不裁掉固定操作列。
- 每行最多 2 个可见动作 +“更多”；危险操作只在更多菜单并二次确认。
- 所有页面只存在一套几何 token；深浅主题只改变颜色，或在浅色完成前不暴露切换入口。
- 每条路由都有可验证的 Loading、Empty、Error、Partial/Guard 状态。
- 键盘可完成导航、筛选、弹层、表格行操作和向导；focus ring 可见；状态不只依赖颜色。
- 真实后端恢复后，再补做权限、接口失败、保存成功、连接测试和异步任务轮询验收。

## 7. 建议的代码落点

1. 全局基础：[`src/design-system.less`](../seatunnel-web-ui/src/design-system.less)、[`src/global.less`](../seatunnel-web-ui/src/global.less)、[`src/app.tsx`](../seatunnel-web-ui/src/app.tsx)、[`src/theme.ts`](../seatunnel-web-ui/src/theme.ts)。
2. 共享状态与动作：[`src/components/StatusChip.tsx`](../seatunnel-web-ui/src/components/StatusChip.tsx)、新增 `DenseTable`、`FilterToolbar`、`ActionMenu`、`EmptyState`。
3. 任务列表：[`SyncTaskList/index.tsx`](../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/index.tsx)、[`SyncTaskList/index.less`](../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/index.less)、[`ActionColumn.tsx`](../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/components/ActionColumn.tsx)。
4. 工作流：[`batch-link-up/workflow/index.tsx`](../seatunnel-web-ui/src/pages/batch-link-up/workflow/index.tsx)、[`config/multi/MultiWorkflow.tsx`](../seatunnel-web-ui/src/pages/batch-link-up/config/multi/MultiWorkflow.tsx)、[`config/script/CustomWorkflow.tsx`](../seatunnel-web-ui/src/pages/batch-link-up/config/script/CustomWorkflow.tsx)、文件工作流组件。
5. 域页面：`pages/data-source`、`pages/master-data`、`pages/data-exploration`、`pages/file-*`、`pages/lake`、`pages/knowledge-management`、`pages/open-api`。

本轮只新增审计报告和截图证据，没有修改业务代码；仓库原有的 `AGENTS.md` 用户改动保持不变。
