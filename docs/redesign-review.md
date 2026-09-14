# Redesign 前端审查报告

> 审查日期：2026-09-14
> 分支：`redesign`
> 审查提交：`0a48601e`（`fix(ui): 浅色下 Segmented 轨道与侧栏折叠钮强制对齐主题`）
> 对比基线：`cad171ee3ccd6f3c05dec53a7e2a14e188c9994b`
> 范围：`seatunnel-web-ui` 本次 redesign 的路由、页面、共享组件、主题、响应式和可交互状态

## 结论

当前提交不适合作为正式发布或 redesign 验收版本。视觉方向已经部分落地，暗色壳层、数据源创建弹层、指标页、探查概览/结果页、Client 和 Metadata 页面在桌面端基本形成统一风格；但存在三个发布阻断级问题：正式菜单中的多条业务路由仍然渲染本地原型/占位页、任务表的固定操作列遮挡数据、批量任务页在手机和窄平板上不可操作。

如果本分支的目标仅是“可点击原型”，应把原型模式明确隔离并在菜单和页面标题中标注；如果目标是可接入后端的正式前端，上述问题修复前不应合入发布分支。

## 审查口径与方法

- 基线到当前提交共 550 个前端文件发生变化，约 `44,814` 行新增、`17,498` 行删除；重点阅读了路由、应用壳、主题、数据源、探查、三类引接、入湖、报警、Client、OpenAPI 和原型注册表。
- 静态检查：`yarn tsc` 通过；`yarn build` 通过；`yarn test --runInBand` 为 24 个 suite 中 23 个通过，1 个失败。
- 运行检查：先完成生产构建，再用 `max preview` 访问 1440×1000 桌面页面；另用 390×844 手机和 768px 窄平板检查布局。通过 Playwright 记录截图、可访问性树快照和页面状态。
- 颜色、密度、状态和壳层要求以 [`docs/DESIGN.md`](../DESIGN.md) 为验收基线；本报告中的“符合/不符合”均指该文档，而不是个人偏好。

## 阻断问题（P0）

### P0-01 正式业务菜单仍指向本地原型或占位页

`config/routes.ts` 在非原型模式下仍把以下路由直接指向 `prototypePage`：`/reporting/forms`、`/resources/data-discovery`、`/sync/cloud-edge-tasks`、`/sync/edge-access-tasks`、`/sync/links`、`/sync/topology`、`/bi` 和 `/operations/diagnostics`（[`routes.ts:14-46`](../../seatunnel-web-ui/config/routes.ts#L14)）。`/operations/protocol` 明确渲染“原型设计中”的占位页（[`ProtocolPlaceholderPage.tsx:5-8`](../../seatunnel-web-ui/src/pages/prototype/ProtocolPlaceholderPage.tsx#L5)）。

这些页面不是只换了视觉层：`CapabilityPage` 从 `localStorage` 读取和写入演示记录，并在操作后直接显示成功 toast（[`CapabilityPage.tsx:129-241`](../../seatunnel-web-ui/src/pages/prototype/CapabilityPage.tsx#L129)；[`store.ts:20-60`](../../seatunnel-web-ui/src/prototype/store.ts#L20)）。因此用户在 `/bi`、云边任务、拓扑或采报页面看到的“创建/执行/状态更新”不会进入业务 API。截图可见这些页面使用相同的通用演示表和静态记录：[`18-reporting-forms.png`](./evidence/18-reporting-forms.png)、[`19-bi.png`](./evidence/19-bi.png)、[`32-cloud-edge.png`](./evidence/32-cloud-edge.png)、[`33-topology.png`](./evidence/33-topology.png)、[`24-protocol-placeholder.png`](./evidence/24-protocol-placeholder.png)。

影响：菜单名称和按钮行为会让用户误以为功能已经可用，且会污染验收数据。建议二选一：

- 正式发布：把路由接回真实页面和 API，并为每条关键操作补成功、失败、权限和重试状态。
- 原型阶段：将原型路由置于显式 feature flag 下，菜单改为“原型/建设中”或隐藏，禁止在正式模式直接显示成功业务 toast。

### P0-02 批量任务页在手机和窄平板上不可操作

390px 和 768px 截图都出现相同布局崩溃：标题和副标题区域被压到近乎 0 宽，中文逐字换行；“创建”按钮压住标题图标；底部批量操作栏覆盖筛选器和表格，按钮与分页被裁切（[`29-batch-link-up-mobile.png`](./evidence/29-batch-link-up-mobile.png)、[`30-batch-link-up-tablet.png`](./evidence/30-batch-link-up-tablet.png)）。

原因是页头顶部仍是不可换行的横向 flex（[`TaskListPageHeader/index.less:10-23`](../../seatunnel-web-ui/src/components/TaskListPageHeader/index.less#L10)），移动媒体查询只把 actions 宽度设为 100%，没有让顶部区域改为纵向布局或允许合理换行（[`index.less:79-91`](../../seatunnel-web-ui/src/components/TaskListPageHeader/index.less#L79)）。批量栏则固定在底部，内部操作按钮全部放在单行 flex 中（[`BottomActionBar.tsx:90-222`](../../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/components/BottomActionBar.tsx#L90)；[`batch-link-up/index.less:105-127`](../../seatunnel-web-ui/src/pages/batch-link-up/index.less#L105)）。

这也违反了设计文档中“仅在选中任务后显示 BatchBar、主动作不超过 3 个”的约束（[`DESIGN.md:148-150`](../DESIGN.md#L148)）：页面无条件渲染 `BottomActionBar`，只是在未选择时禁用它（[`SyncTaskList/index.tsx:818-835`](../../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/index.tsx#L818)）。建议先修复为移动端纵向页头、可折叠/换行的批量操作，并在 `selectedCount === 0` 时不渲染浮动栏；同时给底部安全区预留真实高度，而不是只固定 `padding-bottom`。

### P0-03 多个核心表格的固定操作列遮挡数据

这是桌面端最直接的操作性缺陷，且不是单页偶发现象：

| 页面 | 运行证据 | 源码信号 | 现象 |
| --- | --- | --- | --- |
| 数据源列表 | [`03-data-source-list.png`](./evidence/03-data-source-list.png)、[`31-data-source-list-light.png`](./evidence/31-data-source-list-light.png) | 8 列固定宽度合计约 1178px，`scroll.x=1180`（[`index.tsx:364-467`](../../seatunnel-web-ui/src/pages/data-source/index.tsx#L364)、[`index.tsx:688-695`](../../seatunnel-web-ui/src/pages/data-source/index.tsx#L688)） | `探查状态`列被右侧操作列盖住，状态 chip 只剩一部分 |
| 探查任务 | [`10-exploration-tasks.png`](./evidence/10-exploration-tasks.png) | 列宽合计 1340px，`scroll.x=1320`（[`index.tsx:458-511`](../../seatunnel-web-ui/src/pages/data-exploration/tasks/index.tsx#L458)、[`index.tsx:641-650`](../../seatunnel-web-ui/src/pages/data-exploration/tasks/index.tsx#L641)） | `最近探查`与固定操作列重叠，操作按钮溢出面板 |
| 离线引接 | [`12-batch-link-up.png`](./evidence/12-batch-link-up.png) | 操作列固定，表格使用 `scroll.x=1500`（[`SyncTaskList/index.tsx:223-329`](../../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/index.tsx#L223)、[`index.tsx:787-804`](../../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/index.tsx#L787)） | 1440px 视口中调度列和右侧操作区互相覆盖，右侧动作被裁切 |
| 物理入湖 | [`21-lake-resources.png`](./evidence/21-lake-resources.png)、[`21-lake-resources-after-wait.png`](./evidence/21-lake-resources-after-wait.png) | 列宽合计约 1220px，`scroll.x=1180`（[`physical/index.tsx:402-452`](../../seatunnel-web-ui/src/pages/lake/physical/index.tsx#L402)、[`index.tsx:514-520`](../../seatunnel-web-ui/src/pages/lake/physical/index.tsx#L514)） | `最近对账`列被固定操作列压住 |

建议统一由 `DenseTable` 计算最小宽度：以实际列宽计算 `scroll.x`，让外层只负责水平滚动；固定列必须有完全不透明且足够宽的背景，操作列在窄宽度下降级为“更多”；每个目标 viewport 都要做截图回归，不能只依赖 `scroll.x` 的硬编码。

## 高优先级问题（P1）

### P1-01 请求长时间 pending 时页面没有可恢复的错误态

多个页面把“请求尚未返回”当成永久 loading：

- OpenAPI 地址硬编码为 `http://localhost:9527/v3/api-docs`（[`open-api/index.tsx:24-28`](../../seatunnel-web-ui/src/pages/open-api/index.tsx#L24)），fetch 没有 `AbortController`、超时或部署环境配置（[`openapi-parser.ts:152-186`](../../seatunnel-web-ui/src/pages/open-api/openapi-parser.ts#L152)）。运行中等待超过 7 秒仍只有 skeleton，页面显示“共 0 个控制器”：[`34-open-api.png`](./evidence/34-open-api.png)、[`35-open-api-stuck.png`](./evidence/35-open-api-stuck.png)。
- 探查任务弹层打开后等待 Database，Schema 和提交状态没有给用户下一步；代码在请求返回前持续 `exploreLoading`（[`data-exploration/tasks/index.tsx:325-360`](../../seatunnel-web-ui/src/pages/data-exploration/tasks/index.tsx#L325)），运行证据见 [`36-exploration-modal-loading.png`](./evidence/36-exploration-modal-loading.png)。
- 入湖资源、逻辑入湖和生命周期页在后端不可达时分别出现持续 spinner 或无内联错误/重试入口（[`21-lake-resources-after-wait.png`](./evidence/21-lake-resources-after-wait.png)、[`22-lake-logical.png`](./evidence/22-lake-logical.png)、[`23-lake-lifecycle.png`](./evidence/23-lake-lifecycle.png)）。物理资源汇总甚至静默吞掉异常（[`physical/index.tsx:361-364`](../../seatunnel-web-ui/src/pages/lake/physical/index.tsx#L361)）。
- 数据源列表请求失败时 catch 为空，最终只关闭 loading，不保存错误状态（[`data-source/index.tsx:105-132`](../../seatunnel-web-ui/src/pages/data-source/index.tsx#L105)），用户可能得到“暂无数据源”而不是“加载失败”。

这不满足四种状态要求：Loading、Empty、Error、Partial 必须可区分（[`DESIGN.md:124-131`](../DESIGN.md#L124)）。建议统一请求层的超时/取消、页面级 `error` 状态和“重试”，并把“无数据”与“请求失败”分开呈现；OpenAPI 地址应来自当前部署配置或同源路径。

### P1-02 主题存在“DOM 已浅色、Provider 仍暗色”的状态分裂

运行时将已保存的 `light` 写入 `html[data-st-theme]`，但重新导航后 ThemeConfigProvider 仍从一次性的模块快照初始化为暗色；观察到的页面状态是：DOM 和 `localStorage` 为 `light`，主题按钮 aria-label 仍为“切换浅色模式”（按钮认为当前是暗色）。相关代码路径为：异步初始化设置主题（[`app.tsx:68-78`](../../seatunnel-web-ui/src/app.tsx#L68)）、模块快照（[`theme.ts:49-61`](../../seatunnel-web-ui/src/theme.ts#L49)）、Provider 只读一次初值（[`ThemeConfigProvider.tsx:143-166`](../../seatunnel-web-ui/src/components/ThemeConfigProvider.tsx#L143)）以及按钮文案由 Provider 状态决定（[`ThemeSwitch.tsx:27-35`](../../seatunnel-web-ui/src/components/RightContent/ThemeSwitch.tsx#L27)）。

截图 [`25-data-source-light.png`](./evidence/25-data-source-light.png)、[`26-metrics-light.png`](./evidence/26-metrics-light.png)、[`27-alarm-light.png`](./evidence/27-alarm-light.png) 和 [`31-data-source-list-light.png`](./evidence/31-data-source-list-light.png) 还暴露了报警页的深色 tab/渐变在浅色背景中残留；样式直接写死深色背景和大量 `!important`（[`alarm.less:182-185`](../../seatunnel-web-ui/src/pages/alarm/alarm.less#L182)、[`alarm.less:284-310`](../../seatunnel-web-ui/src/pages/alarm/alarm.less#L284)）。这会造成 token、Ant Design 算法和页面 CSS 三套状态不一致，也带来低对比度风险。

建议让 Provider 在挂载前同步读取同一个存储值，或由单一 React 状态驱动 DOM、Ant Design token 和 ProLayout；修复前按设计文档的兼容策略暂时隐藏浅色切换（[`DESIGN.md:192-199`](../DESIGN.md#L192)）。

### P1-03 全局壳层缺少页面定位和运行上下文

当前顶栏主要只有产品名、主题和知识入口；`layout` 固定返回 `prototypeMenuData`，且没有当前页面标题、环境徽标、全局健康摘要或用户入口（[`app.tsx:82-101`](../../seatunnel-web-ui/src/app.tsx#L82)）。运行截图 [`02-data-source-no-backend.png`](./evidence/02-data-source-no-backend.png)、[`09-exploration-overview.png`](./evidence/09-exploration-overview.png) 和 [`17-metadata-engine.png`](./evidence/17-metadata-engine.png) 可见内容区域虽有页标题，但顶栏无法提供跨页定位和环境确认。

这与 PageShell 规范要求的“产品名 + 环境 + 当前标题 + 全局健康 + 主题 + 用户”不符（[`DESIGN.md:138-142`](../DESIGN.md#L138)）。中台/生产场景下，缺少环境和健康摘要会增加误操作风险。建议先补壳层信息架构，再将菜单数据改为正式路由和权限过滤；同时为无当前用户的状态提供明确登录/权限策略，而不是只返回 `undefined`（[`app.tsx:41-53`](../../seatunnel-web-ui/src/app.tsx#L41)）。

## 设计系统与交互一致性问题（P2）

### P2-01 核心表格仍使用高饱和表头和卡片式视觉

设计文档明确要求 DenseTable 不使用饱和青色实心带、删除斑马纹并降低正常状态噪声（[`DESIGN.md:124-130`](../DESIGN.md#L124)）。当前数据源表头使用白字和渐变（[`data-source/index.less:297-341`](../../seatunnel-web-ui/src/pages/data-source/index.less#L297)），离线任务表头也使用强渐变（[`SyncTaskList/index.less:32-50`](../../seatunnel-web-ui/src/pages/batch-link-up/components/SyncTaskList/index.less#L32)）；报警页、入湖页仍有类似硬编码。截图 [`03-data-source-list.png`](./evidence/03-data-source-list.png)、[`10-exploration-tasks.png`](./evidence/10-exploration-tasks.png) 和 [`12-batch-link-up.png`](./evidence/12-batch-link-up.png) 显示核心信息表被很重的色带包围，和“值班仪表台”的克制方向相反。

### P2-02 数据源默认视图与设计基线相反，行内动作过多

数据源页默认 `viewMode='card'`（[`data-source/index.tsx:59-75`](../../seatunnel-web-ui/src/pages/data-source/index.tsx#L59)），而设计要求列表为默认视图、卡片为可选（[`DESIGN.md:162-179`](../DESIGN.md#L162)）。截图 [`02-data-source-no-backend.png`](./evidence/02-data-source-no-backend.png) 因此首先展示 10 张卡片，不利于中台用户一屏扫描。

列表行内同时显示“测试连接”“探查结果/数据湖管理”和“更多”（[`data-source/index.tsx:501-540`](../../seatunnel-web-ui/src/pages/data-source/index.tsx#L501)），加上多个状态 tag 后信息层级偏重；应收敛为“检测 + 探查 + 更多”，危险和生命周期动作只放入更多菜单，遵循 [`DESIGN.md:30-35`](../DESIGN.md#L30) 的动作和状态原则。

### P2-03 可访问性语义仍有明显风险

本轮只做了 DOM/可访问性树快照和键盘可见性层面的抽查，没有完成屏幕阅读器和自动化对比度审计。已发现 `RightContent` 中 OpenAPI 入口是带 `onClick` 的普通 `div`，没有 button 语义或 aria-label（[`RightContent/index.tsx:40-56`](../../seatunnel-web-ui/src/components/RightContent/index.tsx#L40)）。若该入口继续放在顶栏，应改成原生按钮并支持 Enter/Space、焦点样式和可读标签。浅色报警页的深色 tab/弱文字还应在主题修复后用对比度工具复测。

## 验证结果与工程卫生

### 通过项

- `yarn tsc`：通过。
- `yarn build`：通过，生产构建可生成并由 preview 服务。
- 运行中页面：指标页、探查概览/结果、Client 空状态/创建弹层、Metadata Engine、数据源创建弹层和多数暗色页头在 1440px 下具备可用的基本视觉骨架。指标页、探查概览分别见 [`06-metrics.png`](./evidence/06-metrics.png)、[`09-exploration-overview.png`](./evidence/09-exploration-overview.png)；创建交互见 [`04-data-source-create-modal.png`](./evidence/04-data-source-create-modal.png)、[`16-client-create.png`](./evidence/16-client-create.png)。

### 未通过或受环境限制

- `yarn test --runInBand`：24 suites 中 23 个通过，`src/prototype/__tests__/registry.test.ts:32-44` 失败；测试仍断言 11 条隐藏详情路由，而当前新增了 `/sync/batch-link-up/:id/config/single-incremental`（[`routes.ts:62-65`](../../seatunnel-web-ui/config/routes.ts#L62)）。这不是可忽略的快照差异，应在确认路由清单后更新测试或修复路由注册。
- `git diff --check base..HEAD -- seatunnel-web-ui`：失败。存在批量调度、实时页头和样式文件的 trailing whitespace，以及 3 个入湖 less 文件 EOF 空行；具体位置见命令输出中的 `ScheduleParamsSection.tsx:139`、`RealtimeHeader.tsx:59`、`stream-link-up/index.less:24` 和 physical detail/table/wizard less。
- `yarn biome:lint`：未能完成，已安装的 `@biomejs/cli-linux-arm64` 需要当前环境没有的 GLIBC 2.29/2.30；这是执行环境限制，不能视为代码通过。
- `yarn prototype` 开发入口在本次环境首先触发 Mako 找不到 `src/.umi/plugin-tailwindcss/tailwind.css`，并伴随 watcher/promisify 错误（[`01-console.log`](./evidence/01-console.log)）。`package.json` 的 `prototype` 脚本没有像 `start:dev` 一样设置 `CHECK_TIMEOUT`（[`package.json:16-27`](../../seatunnel-web-ui/package.json#L16)）。生产构建可以运行，但原型评审入口不稳定，应修复后再作为设计验收入口。

## 页面覆盖与证据索引

| 证据 | 页面/状态 |
| --- | --- |
| 01 | Traceability 原型及开发启动日志 |
| 02–05 | 数据源管理：默认卡片、列表、创建弹层、JDBC 配置 loading |
| 06–08 | 指标、报警空态和报警规则页 |
| 09–11 | 探查概览、探查任务、探查结果；11 同时有可访问性树快照 |
| 12–14 | 离线、实时、文件引接任务列表 |
| 15–17 | Client、Client 创建、Metadata Engine |
| 18–19 | 数据采报和 BI 通用原型页 |
| 20–23 | 仓库、物理入湖、逻辑入湖、生命周期 |
| 24 | 协议管理占位页 |
| 25–27 | 数据源、指标、报警浅色主题 |
| 28–30 | 390px 数据源、390px 批量任务、768px 批量任务 |
| 31 | 浅色数据源列表固定列问题 |
| 32–33 | 云边任务和拓扑原型 |
| 34–35 | OpenAPI 正常 skeleton 与长时间 pending |
| 36 | 探查任务弹层长时间 loading |

## 建议的验收顺序

1. 先隔离/下线正式模式中的原型与占位路由；同步修正 registry 测试和路由清单。
2. 修复所有固定列宽度和 390/768px 任务页布局；用数据源、探查、批量、物理入湖四页做 1440/1024/768/390 回归截图。
3. 统一请求超时、取消、错误、空态和重试；移除 OpenAPI 的 localhost 硬编码。
4. 让主题由单一状态源驱动，浅色修复完成前隐藏切换；再按 token 重做报警/表格的残余硬编码。
5. 补 PageShell 的环境、当前标题、健康摘要、用户和焦点/语义检查，最后再跑 `tsc`、全量测试、build、Biome 和 `git diff --check`。

## 限制

本次后端启动因 `.env` 指向的测试 MySQL 在当前受限环境无法建立连接，未完成真实后端数据、权限、写入和长轮询的集成验证；生产 preview 使用了 Umi mock 能够渲染的页面，因此截图证明的是前端呈现和交互壳层，不证明 API 契约已打通。截图覆盖了主要路由和关键空/loading 状态，但不是完整的 WCAG 自动化审计，也未替代真实浏览器、屏幕阅读器和测试环境联调。
