# SeaTunnel Web 数据探查设计说明

> 本文件名沿用项目约定中的 `DESGIN.md` 拼写。它描述数据探查工作区的视觉语言、页面结构和交互约束，不是设计稿坐标的逐像素复刻。

## 1. 适用范围

覆盖以下页面和详情工作区：

- `/data-exploration/overview`：探查概览，回答“有多少数据、覆盖到什么程度”。
- `/data-exploration/tasks`：探查任务配置，回答“对哪些数据源发起一次性探查”。
- `/data-exploration/results`：探查结果，回答“按类型浏览哪些数据源”。
- `/data-exploration/results?dataSourceId=...&dbType=...`：内嵌式数据源详情，包括数据库表目录、Kafka/HTTP 资源目录和 MinIO/S3/FTP 文件目录。

页面以现有 SeaTunnel Web shell、Ant Design 组件和 `src/design-system.less` 为基础，保持左侧产品导航、顶部全局栏和深色数据工作台的一致性。

## 2. 设计来源与取舍

本说明综合了：

- `docs/样式参考/样式参考图1.png`、`docs/样式参考/样式参考图2.png`。
- `docs/样式参考/design-spec.json` 中的字体、字号、颜色和边框语义。
- `/tmp/seatunnel-browser-artifacts.K5LsYQ/` 中的深色最终页面参考，重点是 `final-results-landing.png`、`final-database-inline-detail.png` 和 `final-http-inline-detail.png`。
- `/tmp/seatunnel-playwright-artifacts.BbOgGy/` 中的连接器目录参考，重点是 Kafka、数据库表详情和 MinIO 嵌套目录页面。

设计板上的绝对坐标只属于样式参考板，不直接套用到产品页面；产品页面优先遵循语义层级、现有路由和响应式布局。

## 3. 视觉令牌

### 3.1 颜色

| 语义 | CSS 令牌 | 值 | 用途 |
| --- | --- | --- | --- |
| 应用/页面底色 | `--st-color-bg-app` / `--st-color-bg-page` | `#001922` | 全局 shell、页面背景 |
| 主面板 | `--st-color-bg-panel` | `#002E41` | 卡片、三栏工作区、表格容器 |
| 提升层 | `--st-color-bg-elevated` | `#07394A` | 弹层、悬浮内容、选中后的提升层 |
| 控件底色 | `--st-color-bg-control` | `rgba(0,25,34,.72)` | 输入框、选择器、资源条目 |
| 主文字 | `--st-color-text-primary` | `#FFFFFF` | 页面标题、资源名称、关键数据 |
| 次文字 | `--st-color-text-secondary` | `#D5D5D5` | 正文、表格内容、说明文字 |
| 弱文字 | `--st-color-text-muted` | `#666F75` | 辅助说明、英文眉标题、占位信息 |
| 强调色 | `--st-color-accent` | `#4DD2FF` | 链接、图标、进度、焦点和主操作 |
| 主按钮/边框 | `--st-color-primary` / `--st-color-border` | `#2187A8` | 次级按钮、边框、表头背景 |
| 激活色 | `--st-color-primary-active` | `#117DA0` | pressed/active 状态、细分隔线 |
| 选中背景 | `--st-color-selected` | `rgba(33,135,168,.42)` | 左侧导航、当前资源、当前类型 |

所有新增样式优先引用令牌，不在页面样式中重新定义同义颜色。边框保持 `1px`，用低透明度 divider 形成层级，避免重阴影和白色大面积容器。

### 3.2 字体与层级

字体族统一为 `"Microsoft YaHei", "微软雅黑", Arial, sans-serif`。

| 层级 | 字号 / 行高 | 典型使用 |
| --- | --- | --- |
| large title | `28px / 37px` | 概览页、页面级标题 |
| title | `24px / 31px` | 详情资源标题、重要标题 |
| title/content | `20px / 26px` | 面板标题、弹层标题 |
| content-lg | `18px / 24px` | 重要内容和数据 |
| content-md | `16px / 21px` | 默认内容 |
| content-sm | `14px / 19px` | 表格、控件和辅助内容 |
| caption | `12px / 16px` | 眉标题、状态、说明 |

标题使用常规字重；通过字号、颜色和留白建立层级，不依赖粗体堆叠。英文眉标题使用大写和较宽字距，例如 `METADATA RESULTS`、`INSPECTOR`。

### 3.3 控件与状态

- 标准控件高度为 `34px`，大控件高度为 `38px`。
- 小型状态标签使用低对比度边框和圆角胶囊；状态颜色只承担“已完成 / 处理中 / 异常”的识别，不作为唯一信息来源。
- 悬停使用 `--st-color-hover`，选中使用 `--st-color-selected`，焦点使用 `--st-color-focus` 和可见 outline。
- 空状态必须说明下一步，例如“从左侧目录选择资源”或“请先完成数据源扫描”，不能只展示空图标。
- 加载、失败和空结果都保留当前工作区结构，避免页面因异步请求而跳变。

## 4. 页面结构

### 4.1 探查概览

页面顺序固定为：页面标题 → 范围筛选 → 元数据规模 → 覆盖率与当前范围 → 数据源探查状态。

- 范围筛选为级联关系：单位改变后清空业务系统和数据源；业务系统只有在单位已选时可用。
- 指标面板只展示当前筛选范围，数据源表的“查看结果”进入结果页并携带 `dataSourceId` 和 `dbType`。
- 进度圆环表达数据表完成率，旁边同时给出分子/分母、Database 覆盖和已统计行数，避免只依赖百分比。

### 4.2 探查任务配置

任务页保持“配置一次性探查”的单一目标：搜索和筛选位于同一工具条，数据源状态表紧随其后。

- “重新扫描”“开始探查”“运行记录”“查看结果”是行级动作，使用文字链接和图标组合，减少按钮噪声。
- 开始探查弹层在切换数据源时必须清空上一次 Database 选择；无可用 Database 时不可提交。
- 提交后使用状态反馈和轮询结果更新行状态，不在当前页引入定时调度概念。

### 4.3 探查结果

结果页使用固定三栏工作区：

1. 左栏：`EXPLORATION TYPES`，展示全部、数据库、消息队列、API 服务、文件传输及数量。
2. 中栏：`METADATA RESULTS`，展示当前范围下的资源卡片、搜索和结果数量。
3. 右栏：`INSPECTOR`，展示当前范围、结果统计和类型分布；进入详情后展示数据源信息。

资源卡片必须提供名称、类型、环境、归属、探查状态和进入详情的明确 affordance。类型筛选、搜索、单位/业务系统筛选互相叠加；返回详情时清除 URL 中的 `dataSourceId` 和 `dbType`，保持浏览上下文可预期。

### 4.4 数据源详情工作区

数据库、Kafka/HTTP、MinIO/S3/FTP 使用统一的“顶部标题 + 左目录 + 中心详情 + 右侧 Inspector”骨架，但中心内容由连接器类型决定：

- 数据库：数据库、Schema、表目录；表详情包含列定义、样本数据、数据指标三个按需标签页。
- Kafka/HTTP/ES：连接器返回的一级资源列表；中心展示资源概览和原始目录属性。
- MinIO/S3/FTP：使用面包屑和目录进入；进入下一级目录时先清理旧资源选择，待新目录加载后再选中资源。

所有详情工作区均提供返回结果页、加载态、读取失败提示和无选择空态。右侧 Inspector 用于放置描述、标签、域、约束、连接器和探查状态等上下文信息，不承载主浏览动作。

## 5. 实现映射

| 区域 | 主要文件 |
| --- | --- |
| 主题令牌和 Ant Design 覆盖 | `seatunnel-web-ui/src/design-system.less`、`seatunnel-web-ui/src/global.less` |
| 概览 | `src/pages/data-exploration/overview/index.tsx`、`overview.less` |
| 任务配置 | `src/pages/data-exploration/tasks/index.tsx`、`src/pages/data-exploration/index.less` |
| 结果工作区 | `src/pages/data-exploration/results/index.tsx`、`results.less` |
| 数据库详情 | `src/pages/data-source/components/DataExplorationDrawer.tsx`、`DataExplorationDrawer.less` |
| 非数据库详情 | `src/pages/data-source/components/GenericDataExplorationDrawer.tsx`、`GenericDataExplorationDrawer.less` |
| 数据源类型分组和兼容归属 | `src/pages/data-source/dataSourceRegistry.ts`、`src/pages/data-exploration/shared.ts` |

## 6. 响应式约束

- `1440px` 是主要工作台参考宽度：结果页三栏完整展示，右侧 Inspector 保持可见。
- `1160px` 以下压缩左右栏并让页面标题和操作区换行。
- `900px` 以下隐藏 Inspector，保留目录与结果列表。
- `680px` 以下改为纵向工作区，筛选器和操作按钮占满可用宽度；目录限制高度并独立滚动。
- 详情页同样优先保证目录和中心内容可用，Inspector 在窄屏时隐藏或降级为次要信息。

## 7. 验证记录

本轮使用 Playwright Chromium（`1440 × 1000`）对本地前端进行验证。因未授权启动测试后端和容器，API 使用 Playwright route mock，未连接外部服务。

通过的交互断言共 12 项：

- 结果页渲染资源、类型筛选、数据库详情、表详情和返回结果页。
- Kafka 资源选择显示概览。
- MinIO 两级目录导航会替换旧资源选择。
- 概览页渲染指标并支持刷新后保持当前路由。
- 任务页渲染数据源、搜索筛选和处理中状态。

最终回归结果：TypeScript `tsc --noEmit` 通过，Playwright 12/12 通过，应用控制台错误为 0。仍存在项目既有的 React Intl 菜单 fallback warning，不影响本次页面交互。

## 8. 后续维护规则

1. 新增探查页面优先复用三栏详情骨架和现有 token，不新建第二套深色颜色体系。
2. 新增连接器时先登记 `dataSourceRegistry.ts` 的类型、分组和图标，再接入结果页和详情路由。
3. 任何筛选器变更都要同时验证选项清空、URL 状态和统计数据是否一致。
4. 任何目录导航都要验证加载中、失败、空目录和返回上级四种状态。
5. 页面修改后至少用 1440px 截图检查一次，并用 Playwright 验证核心点击、搜索、返回和按需加载动作。
