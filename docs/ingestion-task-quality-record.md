# 数据引接任务审计与测试记录

分支：`codex/ingestion-task-audit-fixes`
记录日期：2026-10-07（执行期 2026-10-01 至 2026-10-07）
范围：批量数据引接、实时数据引接、离线文件导入、文件同步任务，任务概览及相关二级页面。

## 问题记录与修复

| 编号 | 问题 | 修复 | 状态 |
| --- | --- | --- | --- |
| UI-01 | 多个二级页面在主标题下重复展示眉题或解释性副标题，压缩首屏空间。 | 任务列表、探查概览、知识管理及相关任务详情页移除副标题，统一收紧标题区留白；任务洞察保留标题和操作控件，不再额外放置说明段落。 | 已修复；重启后浏览器复核通过 |
| OVR-01 | 任务洞察只开放批量任务，实时、离线导入、文件同步不可选。 | 开放四种任务类型并为每类调整指标、图表标题和单位；文件同步隐藏不适用的记录量与记录速率。 | 已修复；浏览器已验证四种类型请求 |
| OVR-02 | 实时任务使用独立快照表，旧概览只查询批量指标表。 | 实时概览改用实时快照汇总与趋势查询；速率按各 pipeline 汇总。 | 已修复；重启后四类概览请求复核通过 |
| OVR-03 | 任务成功率把运行中的实例也纳入分母，状态分类没有区分非终态 `FAILING` 和失败终态。 | 成功率按已结束实例计算；失败只计 `FAILED`/`UNKNOWABLE`，`FAILING` 归为运行中，取消和保存点完成单独统计。 | 已修复；后端服务测试和实际 summary API 复核通过 |
| OVR-04 | 批量类概览只从已落库指标计执行数，正在运行但尚未终态落库的实例会消失。 | 批量、离线导入和文件同步汇总将统计范围内有指标的实例与当前仍运行的实例合并去重；页面说明指标记录/当前运行实例的计数口径。 | 已修复；实际 summary API 与浏览器复核通过 |
| OVR-05 | 实时成功/失败状态取值范围与所选时间段不一致；已停止任务在趋势计算中被漏掉。 | 状态取值按范围内最新快照并回退当前状态；累计趋势保留停止任务的区间增量。 | 已修复；DAO mock 服务测试通过，SQL 逻辑审查并经只读 API 查询复核 |
| OVR-06 | 趋势桶按单桶峰值缩放，但图表标签沿用区间总量单位，累计量较大、单桶较小时会出现数量级错标。 | 趋势数值按所选时间范围合计选择缩放因子，与 summary 单位一致；增加回归测试覆盖跨阈值多桶数据。 | 已修复；回归测试通过 |
| OVR-07 | 没有已结束任务时成功率显示 `0%`，摘要分析也可能显示 `null%`，容易误读。 | 无已结束实例时卡片显示“—”，摘要显示“暂无已结束任务”，并在有运行实例时提示数量。 | 已修复；工具函数及浏览器空区间流程复核通过 |
| OVR-08 | 实时摘要卡/分析漏掉成功数；`FAILING` 被算作失败；批量、离线导入和文件同步的活动实例未进入指标表状态汇总。 | 四类任务摘要列出成功、失败、运行中、已停止/保存点结束数量；批量类从实例表补入当前活动实例，实时状态统一将 `FAILING` 计入运行中。 | 已修复；四类浏览器复核及运行实例 SQL 验证通过 |
| OVR-09 | 汇总总量缩放后转为整数，12,000 条会显示成 1 万，和趋势合计 1.2 万不符；缩放后的字节也会被截断。 | 汇总 DTO 保留两位小数，与趋势值精度一致；新增回归用例。 | 已修复；服务测试及真实历史数据概览通过 |
| OVR-10 | 快速切换任务类型或时间范围时，较早的慢请求可能覆盖新筛选结果，旧请求的 `finally` 也会提前清除新请求的加载状态。 | 为每轮加载分配请求代数，只允许最新一轮提交数据、错误、就绪和加载状态；筛选变化或卸载使旧请求失效。 | 已修复；延迟响应回归测试通过 |
| OVR-11 | 实时趋势的记录/字节速率来自 DECIMAL 聚合，图表公共转换先转成长整数，小于 1 的速率会变为 0，小数部分也会丢失。 | RAW 速率使用浮点解析后保留两位显示；计数和字节趋势仍按整数转换。 | 已修复；小于 1 与含小数速率回归测试通过 |
| CFG-01 | 批量与实时新建向导默认生成 `mysql2mysql` 任务名，用户未选择该连接组合时容易误解。 | 初始任务名置空，保留清晰占位提示和必填校验。 | 已修复；浏览器已验证 |
| CFG-02 | 来源、去向连接卡片重复创建 Form，形成嵌套表单。 | 两侧卡片改为共用单一 Form。 | 已修复；浏览器已验证 |
| CFG-03 | 批量和实时多表配置的子组件与父组件各自创建 Form，造成嵌套 DOM、表单状态分裂。 | 子组件保留 Form.Item 并使用父表单上下文；默认匹配方式由父表单设置。 | 已修复；浏览器确认两页各只有一个 form |
| CFG-04 | 实时单表编辑器调用 `useForm`，但没有表单上下文。 | 页面补上无 DOM 的 Form 上下文，异步草稿加载仍使用同一个实例。 | 已修复；浏览器已验证 |
| CFG-05 | 文件同步在仅配置 MinIO 时仍默认选择 FTP，导致新任务不可直接使用已配置连接。 | 文件同步来源继续按文件类型提供，目标默认值及回退值改为 MinIO。 | 已修复；浏览器已验证 |
| CFG-06 | 文件资源源节点错误显示为 MinIO 目录或远端目录。 | 对 `FILE_RESOURCE` 显示“湖文件”及相应文件选择提示。 | 已修复；浏览器已验证 |
| CFG-07 | 文件预览表格使用已弃用的行索引作为 `rowKey`。 | 预览记录分配稳定且非枚举的行键，避免污染传给转换逻辑的字段。 | 已修复；文件导入流程复核通过 |
| CFG-08 | 文件预览引用的两条 locale 消息缺失，打开节点面板时产生格式化错误。 | 增加中英文消息定义。 | 已修复；文件导入流程复核通过 |
| CFG-09 | 文件同步概览把字节传输错误展示为记录数，并沿用记录速率图表。 | 改为传输数据量/文件传输速率，隐藏记录数和记录速率图表。 | 已修复；浏览器已验证 |
| CFG-10 | 连接测试未返回时切换数据源类型或离开连接步骤，旧验证响应可能覆盖当前连接状态；类型切换时旧数据源选项也可能短暂被自动选中。 | 连接验证增加请求代数保护，卸载时使请求失效；来源和目标选项请求同步失效旧响应，加载新类型前清空旧选项；父向导更换类型时清空数据源 ID 和连接状态。 | 已修复；延迟响应、离开步骤并切至无数据源类型后状态保持“未测试” |
| OBS-01 | 多表 Form 切换匹配方式时，`rc-util` 的深比较日志提示存在循环引用；定位在第三方 `rc-field-form` 的 `Field.triggerMetaEvent` / `rc-util.isEqual`，移除应用层嵌套表单后仍可由字段元数据触发。 | 已确认不影响控件操作、字段校验或页面渲染；不修改供应商依赖文件。记录为第三方开发控制台告警，后续随依赖升级复核。 | 功能验收通过；告警仍存在 |

| CFG-11 | 文件同步新建向导选择 FTP、SFTP、Amazon S3 等来源后，初始化仍把来源类型重置为湖文件。 | 创建配置从向导所选来源类型初始化；页面参数构建器回归测试覆盖 FTP、SFTP、S3 三种来源。 | 已修复；FTP、SFTP、S3 浏览器端任务均完成传输 |
| CFG-12 | 新建远端来源节点没有带上所选数据源 ID，生成的 Engine 配置缺少连接引用。 | 新建图节点写入 dataSourceId；编辑和新建图都保留数据源引用。 | 已修复；S3 任务发布、运行成功 |
| CFG-13 | 文件同步的 Amazon S3 source 使用 CSV 格式但没有 schema，SeaTunnel Engine 以 schema 必填拒绝任务。 | 文件同步 source 和 sink 使用 binary 格式，按文件传输处理，不再要求结构化 schema。 | 已修复；S3 文件传输成功，源和目标 SHA-256 一致 |

## 数据源覆盖

2026-10-07 开发环境数据源管理页显示 11 条用户可见连接；分页 API 返回 12 条，其中包括一条不在普通列表展示的系统托管 Doris 连接。新建任务向导列出 16 种 connector 类型。对连接管理页测试、任务配置校验、Engine 侧验证和端到端执行分别记账，不能用其中一项代替其他项。FTP、SFTP、Amazon S3 的临时连接只在本轮浏览器实测时创建，已在收尾时删除。

| Connector 类型 | 本轮覆盖与结果 |
| --- | --- |
| JDBC | 临时通用 JDBC 数据源通过管理端和本地 Engine 客户端连接测试；使用 MySQL JDBC driver class 与 `/mnt/lc/seatunnel/arm64/lib/mysql-connector-java-8.0.27.jar` 完成 JDBC→JDBC 批量任务，3 行数据成功写入。临时数据源和数据库已删除。 |
| MYSQL | 持久连接管理页测试通过；Engine 批量来源/目标可用；完成 MySQL→MySQL 批量写入和 MySQL CDC→MySQL 增量写入；也作为离线文件导入目标。 |
| POSTGRESQL（内部类型 POSTGRE_SQL） | 开发环境持久连接的管理端测试通过；此前 PostgreSQL→Kingbase 批量任务完成并写入 22 行。本次新建批量链路的 Engine 来源连通性测试失败，日志显示 Engine 加载了 Vastbase JDBC jar，详见复核补记。 |
| ORACLE | 持久连接管理页测试通过；批量来源 Engine 检查复测通过，初次检查失败后重试成功；未做端到端写入。 |
| ZEONEDB-D | 向导可选；无已配置实例，且本轮在本机 /mnt/lc 和 192.168.100.91:/root/lc 未发现可用服务或已验证驱动，未能连接测试或运行任务。 |
| VASTBASE | 旧记录中的连接测试失败发生在测试容器停止期间；本次按授权启动容器后，管理页连接测试通过，收尾时恢复为停止状态。批量来源 Engine 检查此前重试通过；本轮未重新运行端到端写入。 |
| KINGBASE | 持久连接管理页测试和批量来源 Engine 连接检查通过；执行期内 PostgreSQL→Kingbase 批量任务有 22 行端到端写入证据（仅作为 sink），未完成 Kingbase 作为 source 的端到端任务。 |
| DAMENG | 持久连接管理页测试和批量来源 Engine 连接检查通过；未做端到端写入。 |
| DORIS | 持久连接管理页测试和批量来源 Engine 连接检查通过；未做端到端写入。 |
| ELASTICSEARCH | 持久连接管理页测试通过；批量 source/sink 和实时 source Engine 检查通过；未做端到端写入。 |
| KAFKA | 持久连接管理页测试通过；批量 source/sink 和实时 source 的 Engine 检查均在提交作业时失败（Engine 无法访问 topic）；未做端到端任务。 |
| FTP | 临时连接的浏览器连接测试通过；文件同步→MinIO 实际传输两个文件记录，目标内容与源文件 SHA-256 一致；临时连接已删除。 |
| SFTP | 临时连接的浏览器连接测试通过；文件同步→MinIO 实际传输两个文件记录，目标内容与源文件 SHA-256 一致；临时连接已删除。 |
| AMAZON S3 | 临时连接的浏览器连接测试通过；修复 binary 格式后完成 S3→MinIO 文件同步，目标 33 字节且 SHA-256 与源相同；临时连接和测试服务已删除。 |
| MINIO | 持久连接管理页测试通过；作为湖文件、FTP、SFTP、Amazon S3 文件同步的目标，所有本轮输出均已按测试前缀核对并清理。 |
| HTTP | 持久连接管理页测试通过；批量来源和实时来源的 Engine 连接检查通过；HTTP 仅适用作来源，本轮未做端到端任务。 |

覆盖判断：16 种 connector 类型均在向导选项和适用性矩阵中逐项登记；15 种类型至少完成了连接测试或 Engine 侧配置检查。真实任务执行证据包括 JDBC→JDBC、MySQL→MySQL、MySQL CDC→MySQL、PostgreSQL→Kingbase（Kingbase 仅作为 sink）、以及湖文件/FTP/SFTP/Amazon S3→MinIO。ZEONEDB-D 没有可用实例/驱动；Kafka 的 Engine 检查因无法访问 topic 失败；PostgreSQL 普通 JDBC Engine 检查疑似受共享驱动冲突影响，尚未隔离复测，PostgreSQL CDC 仅验证配置生成、未运行 Engine 任务。其余数据库与消息源未以全量数据写入任务作验证，因此不能据此宣称所有 connector 的所有功能都已端到端通过。

## 四种任务类型端到端验收

| 任务类型 | 实际执行 | 用户可配置项与参数覆盖 | 结果 |
| --- | --- | --- | --- |
| 批量数据引接 | MySQL→MySQL；任务 codex-audit-mysql-batch-20261001。通用 JDBC→通用 JDBC；任务 codex-generic-jdbc-batch-20261001。 | 新建单表链路、来源/目标连接和表选择、发布、上线、手动运行；MySQL 任务验证 5 行数据及中文字符，通用 JDBC 任务验证 3 行数据（中文、带引号文本、小数）。通用驱动参数使用 `com.mysql.cj.jdbc.Driver`、Engine 挂载的 8.0.27 驱动 jar、批量写入默认 batch size 1000 和追加模式。多表配置另做浏览器配置路径检查，未执行多表数据迁移。 | 两个 Engine 作业均完成；源与目标行值一致。临时任务、数据源和通用 JDBC 专用数据库已清理。 |
| 实时数据引接 | MySQL CDC→MySQL；任务 codex-audit-mysql-cdc-20261001-2。 | CDC 来源表和目标表选择、初始快照加运行期增量；启动后向来源插入一行，目标出现该行。未覆盖 SSL、GTID/server-id 边界及故障恢复组合。 | 初始 2 行和新增第 3 行均到达目标；任务下线并删除，专用表已清理。 |
| 离线文件导入 | 湖文件 uitest-sample.csv→MySQL；任务 codex-audit-file-ingest-20261001。 | 文件选择、CSV 格式/字段识别、目标数据源和自动建表写入；本次采用默认 CSV 解析参数。另浏览器走查 JSON、TXT、工作簿的识别与预览，但未将这些格式全部导入目标表。 | 3 行数据写入并核对；任务与专用目标表已清理。 |
| 文件同步任务 | 湖文件→MinIO、FTP→MinIO、SFTP→MinIO、Amazon S3→MinIO，各执行一次。 | 文件同步来源类型、来源 dataSourceId、远端连接参数、目录/对象前缀、正则文件名过滤、扩展名 csv、二进制文件格式、chunk size 1024、整文件传输、MinIO 目标前缀。S3 覆盖 endpoint、region us-east-1、bucket、root prefix、STATIC 认证、path-style access、连接超时 10000ms、请求超时 30000ms；密钥从未写入记录。FTP/SFTP 覆盖连接测试、远端文件选择和认证字段，使用的账号值不留存。 | 四条链路均完成；FTP/SFTP/S3 的目标文件与源内容 SHA-256 一致。任务、临时连接、对象及临时服务已清理。 |

## 修复的任务体验问题

| 修复 | 验收证据 |
| --- | --- |
| 文件同步来源类型在新建向导中保留，不再错误回落为湖文件。 | 从 FTP、SFTP、Amazon S3 来源完成新建、配置、发布和运行。 |
| 新建远端来源图节点携带所选数据源 ID。 | S3 预览配置引用所选远端连接；任务成功执行。 |
| 文件同步按二进制文件传输设置 source/sink 的 file_format_type，避免要求文本 schema。 | 第一次 S3 运行捕获到 Engine schema 必填错误；修复后编辑、重发、运行成功，验证源/目标 SHA-256 一致。 |
| 文件同步任务概览不把字节数显示为行数；文件来源目标默认和回退值使用已配置的 MinIO。 | 浏览器新建路径、配置预览和文件同步列表指标检查通过。 |
| 文件导入预览使用稳定的非枚举行键并补齐中英文消息。 | CSV 识别、字段预览和导入成功。 |

## 参数覆盖边界

本轮参数覆盖依据产品表单中走过的实际路径，不是对 Engine connector 的所有可选参数做排列组合。已实际改变或验证的参数包括来源类型/连接引用、数据库表、通用 JDBC driver class/jar 路径及 batch size/追加模式、CDC 源表/目标表、文件选择和格式、远端 endpoint/region/bucket/root prefix、静态认证类型、path-style access、超时、正则匹配、扩展名、chunk size、整文件模式和目标目录。未覆盖的可选配置包括连接器专属 TLS/代理、复杂格式 schema、并行度极值、失败重试组合、断点续传故障注入及大文件压力测试。它们不影响本轮已执行路径的结论，但仍是后续 connector 验收项。

## 测试数据与资源清理

- 已删除本轮创建的批量、实时 CDC、离线导入、湖文件/FTP/SFTP/S3 文件同步任务定义；未删除列表原有的 验收-文件传输-MinIO-20260916 和 test_sync_minio。
- 已删除测试数据库 codex_ingest_audit_20261001 以及 seatunnel_web 中三张 codex_audit_* 专用测试表。
- 已删除 MinIO 中本轮使用的四个唯一输出前缀及 Engine 临时前缀；保留共享 MinIO 服务和原有对象。
- 已删除通用 JDBC 验收任务 `codex-generic-jdbc-batch-20261001`、临时数据源 `codex-generic-jdbc-audit-20261001` 以及仅含两张 `codex_jdbc_*` 测试表的数据库 `codex_generic_jdbc_20261001_1329`。
- 已删除三条临时 FTP/SFTP/Amazon S3 数据源连接、S3 测试容器、FTP/SFTP 测试容器和 /mnt/lc/test/codex-ingest-audit-20261001 测试文件目录。共享 MinIO 容器里为本轮建立的两个 mc alias 也已删除。
- 测试任务和测试资源只使用隔离名称/前缀；没有清空其他会话数据，没有触碰 .91:/root/lc 下的资源，也没有执行 Compose 或重启容器。

## 自动化验证与浏览器验收

四种任务的关键 happy path 已在本地 Chrome/Playwright 实际完成到任务运行和结果核对。除以下列出的单元测试外，不把静态配置校验、连接检查或草稿表单检查计作端到端成功。

| 验证 | 结果 |
| --- | --- |
| 前端 Jest（全量） | 28 个 Jest 套件、129 个测试全部通过；文件同步回归测试通过实际页面参数构建器覆盖 FTP、SFTP、S3 向导来源类型及 source/sink 数据源引用、binary 文件格式；指标页面测试覆盖旧请求不能覆盖新筛选结果。 |
| 前端 TypeScript | 通过。 |
| 前端生产构建 | Mako 生产构建成功。 |
| 后端回归测试 | 41 个 Maven reactor 模块构建成功；JobMetrics、MySQL CDC、PostgreSQL CDC 的 18 个测试和 HOCON 脱敏器的 7 个测试通过。 |
| 四类任务概览 summary API | MySQL 临时实例行验证 batch、stream、file-ingest、file-transfer 对 `FAILING`/保存点活动状态的分类和计数；夹具已删除。 |
| git diff --check | 通过。 |

2026-10-07 再次执行：前端全量 Jest 仍为 28 个套件、129 个测试通过；`yarn tsc` 和 Mako 生产构建通过。后端 41 个 Maven reactor 模块构建成功，18 个选定回归测试通过（JobMetrics 6、MySQL CDC 6、PostgreSQL CDC 6）；另运行 HOCON 脱敏器回归测试 7 项，全部通过。

## 外部限制

- Kafka 管理页连接测试通过，但批量 source/sink 与实时 source 的 Engine 提交检查失败，提示 Engine 无法访问 topic；未修改持久 Kafka 连接参数。
- PostgreSQL 管理端连接测试通过；本次新建批量链路的 Engine 来源连通性测试失败，详见 2026-10-07 复核补记；未改变持久数据源参数。
- Vastbase 的旧管理页连接测试返回 11003，而批量 source Engine 检查重试通过；复核时启动原本停止的测试容器后管理页连接测试成功，并在收尾时停止该容器。仍未做本轮端到端数据写入。
- 通用 JDBC 已通过连接检查和 JDBC→JDBC 批量任务；ZEONEDB-D 仍没有可用实例或已验证驱动，本轮没有任务执行证据。
- HTTP 本轮只作为来源检查；各数据库适用的任务类型并不相同，所以未把 source/sink 不兼容组合强行算作覆盖。

## 2026-10-07 复核补记

重新对照目标检查后，之前的质量记录不足以证明“所有数据源及所有配置参数均已端到端覆盖”：四种任务类型有实际运行证据，但参数仅覆盖常用成功路径；ZeoneDB-D 无可用实例/驱动，Kafka Engine 检查失败，PostgreSQL 普通 JDBC Engine 检查疑似受共享驱动冲突影响且尚未隔离复测。PostgreSQL CDC 仅检查了配置生成，未运行 Engine 任务。下面补充本次浏览器、API、Engine 与回归测试证据，并修正 PostgreSQL 普通批量检查的旧记录。

### 数据源配置表单字段盘点

逐项请求任务新建可选的 16 种数据源配置；配置 API 均返回成功并含字段定义。下表完整记录当前表单字段及必填性。它验证配置表单可用，不代表每个字段值、字段组合或连接器能力都经过真实连接/任务运行。ZeoneDB-D 字段来自本地应用的插件表单配置注册；没有可用 ZeoneDB 实例或已验证 Engine 驱动。H2 当前不可创建，不在 16 种可选类型内。

| 数据源类型 | 字段数 | 必填字段 | 可选字段 |
| --- | ---: | --- | --- |
| JDBC | 7 | url, driver, driverLocation, user, password | database, schemaName |
| MYSQL | 7 | host, port, database, user, password | driverLocation, other |
| ORACLE | 7 | host, port, connectType, database, user, password | driverLocation |
| POSTGRE_SQL | 8 | host, port, database, schemaName, user, password | driverLocation, other |
| ZEONEDB | 8 | host, port, database, user, password | schemaName, driverLocation, other |
| VASTBASE | 8 | host, port, database, user, password | schemaName, driverLocation, other |
| DORIS | 7 | fenodes, queryPort, database, user, password | driverLocation, other |
| ELASTICSEARCH | 15 | hosts | authType, username, password, apiKeyId, apiKey, apiKeyEncoded, tlsVerifyCertificate, tlsVerifyHostname, connectTimeoutMs, socketTimeoutMs, tlsKeystorePath, tlsKeystorePassword, tlsTruststorePath, tlsTruststorePassword |
| KINGBASE | 8 | host, port, database, schemaName, user, password | driverLocation, other |
| DAMENG | 8 | host, port, database, user, password | schemaName, driverLocation, other |
| KAFKA | 9 | bootstrapServers, securityProtocol | saslMechanism, username, password, clientId, requestTimeoutMs, kafkaConfig, schemaRegistryUrl |
| FTP | 9 | host, port, user, password, basePath, connectionMode, remoteVerificationEnabled, connectTimeoutMs, dataTimeoutMs | — |
| SFTP | 7 | host, port, user, password, basePath, connectTimeoutMs, dataTimeoutMs | — |
| S3 | 10 | endpoint, region, bucket, basePath, credentialMode, pathStyleAccess, connectTimeoutMs, requestTimeoutMs | accessKey, secretKey |
| MINIO | 8 | endpoint, region, bucket, basePath, accessKey, secretKey, connectTimeoutMs, requestTimeoutMs | — |
| HTTP | 12 | baseUrl, authenticationType | healthCheckPath, username, password, bearerToken, apiKeyHeader, apiKeyValue, defaultHeaders, connectTimeoutMs, socketTimeoutMs, openApiSpecUrl |

### 页面和指标路由复核

- 批量引接、实时引接、离线文件导入、文件同步四个任务列表页面均在 Chrome 中加载；对应列表请求成功。离线导入和文件同步的批量定义分页请求也返回成功。
- 指标页依次选择 BATCH、STREAM、FILE_INGEST、FILE_TRANSFER；四类 summary 与图表请求均成功，页面展示了对应类型的指标标签和内容。
- 本次另打开 PostgreSQL CDC 与批量新建流程检查来源配置，未保存草稿或创建任务。
- 2026-10-07 最终开发服务重启后再次在 Chrome 打开四个列表页：批量任务 9 条、实时任务列表（当前为空）、离线导入 6 条、文件同步 2 条；页面请求均返回 200。任务概览默认 BATCH 的 summary/charts 请求也返回 200。此轮是最终构建后的页面复核，不替代前述四种任务真实运行记录。

### 本次 Chrome 任务流程审计

- 在 1440×1000 桌面视口中重新走查四类新建入口。批量基础配置和客户端连接页均可加载，MySQL 来源、去向及客户端自动检查通过；实时入口默认选择 MySQL-CDC→MYSQL，名称必填校验有效，MySQL 来源、去向及实时客户端检查通过。
- 离线导入入口使用湖文件作为来源，目标类型菜单包含 JDBC、MYSQL、ORACLE、PostgreSQL、ZEONEDB、VASTBASE、KINGBASE、DAMENG、DORIS；文件配置可选 CSV、Excel、JSON（建议 NDJSON）、TEXT/TXT，CSV 参数面板可见分隔符、首行表头、编码、跳过行数、引号字符和转义字符。
- 文件同步入口的类型菜单包含湖文件、FTP、SFTP、Amazon S3 和 MinIO。选择 FTP 来源后可到达客户端与连接步骤；当前普通数据源列表没有可选的 FTP 实例，因此本次没有把该空实例状态当作连接通过。FTP/SFTP/S3→MinIO 的真实传输证据来自上文已完成并清理的历史实测。
- 四类任务概览选择器均可切换并加载对应 summary/charts：批量显示 77 条、6.54 KB、15 个执行实例；实时显示 0 条指标的空状态；离线导入显示 1,147 条、68.71 KB、7 个实例；文件同步显示 23.61 KB、2 个已完成实例。数值来自当前开发环境所选时间范围，不是本轮新造的测试数据。
- 复核期间补充修复实时速率图表小数精度：DECIMAL 速率不再在 API 图表转换时截断；回归用例覆盖 0.75 和 512.125（页面按两位小数展示为 512.13）。
- 本次创建的四个流程均未保存任务。离线导入、文件同步以及批量、实时向导写入的本地 sessionStorage 草稿键已清理；四类任务列表仍分别为 9、0、6、2 条。浏览器错误控制台未发现消息。

### PostgreSQL Engine 复测

- 开发环境分页 API 返回 12 条记录（其中 1 条系统托管 Doris 不显示在普通列表）；用户可见的 PostgreSQL 连接管理测试通过，连接参数为内网 PostgreSQL 测试库。没有修改该持久数据源。
- 在 Chrome 新建批量任务向导中选择该 PostgreSQL 来源和现有 MySQL 去向后，客户端来源连通性测试失败、MySQL 去向测试通过。取消向导后任务列表仍为原有 9 条，没有保存草稿或新任务。
- Engine REST 返回 `Factory initialize failed` / `Unable to create a source for identifier 'Jdbc'`，但当前失败日志中的底层异常是 PostgreSQL JDBC 会话初始化错误 `PSQLException: Protocol error. Session setup failed.`。同一日志确认 SeaTunnel 已发现 `connector-jdbc-2.3.13.jar`，因此不是缺少 `Jdbc` source 插件。
- 对应开发环境失败请求连接 `192.168.100.95:15432/test` 时，JDBC 堆栈从 `/opt/seatunnel/lib/Vastbase-G100-2.16_pg_2026062910.jar` 加载 `org.postgresql.Driver`；同一 Engine 共享 `lib` 目录也有 PostgreSQL JDBC jar。Web 的 JDBC HOCON builder 只生成 URL、用户、driver 类名和密码，不生成表单里的 `driverLocation`。这指向 Engine 共享类路径中的同名驱动冲突，但还没有通过隔离 Engine 类路径复测，不能当作已修复。未创建作业。
- SeaTunnel 2.3.13 PostgreSQL CDC builder 的回归断言已按当前配置生成的 `url` 字段更新，并断言不再生成旧的 `hostname`、`port` 字段。PostgreSQL CDC 插件模块及依赖模块测试共 6 项通过；这验证配置生成，不替代 Engine 连接成功。

### HOCON 预览凭证脱敏复测

Luna Max 复审发现 HOCON 预览原先可能返回 ES API key/TLS 密码、HTTP 请求头、Kafka SASL JAAS 内容，以及 JDBC/HTTP URL 和 ES `hosts` 中嵌入的凭证。脱敏器现按敏感字段名遮蔽凭证、遮蔽整个 HTTP headers 对象，并清理所有字符串中的 URL user-info 和敏感 query/分号参数；HOCON 解析失败时返回固定的全遮蔽提示。

新增的 7 项回归测试覆盖 ES API key 与 TLS 密码、HTTP Authorization 和自定义 headers、Kafka `sasl.jaas.config`、JDBC/HTTP/Oracle thin/SQL Server URL 凭证、ES hosts user-info，以及解析失败闭锁。校验同时确认主机、路径和非敏感 URL 参数仍保留。

### 本次复核清理

- PostgreSQL 探针专用表和 publication 已删除并核实不存在；两个 PostgreSQL 新建流程均取消，未保存草稿。
- 未添加新的持久数据源记录，未保留测试 Engine 作业或数据表；没有执行 Compose、容器部署或重启。
- 先前四类任务端到端记录仍有效；其专用连接、表、任务和文件资源已按上文清理记录处理。本补记没有重新运行这四类任务，因此不把列表页和指标页复核当作端到端运行证据。
- 只读检查了 `/mnt/lc` 和 `91:/root/lc` 中的部署清单；本机可见 MySQL、PostgreSQL、Oracle、Kafka、Elasticsearch、Doris、MinIO 等测试服务，91 上有 Kingbase 相关部署文件，两个位置均未发现 ZeoneDB 安装包、服务或 JDBC 驱动。本次没有修改这些部署。
- 追补 Kafka 只读探测：本机 `kafka0` 容器状态为 running，映射端口 19092 接受 TCP 连接；使用容器内配置的 SASL PLAIN 测试凭据执行 topic 列举请求，15 秒内未返回。该结果只证明端口可达，不能证明认证、topic 元数据或 SeaTunnel Engine 可用；未修改或重启容器。
