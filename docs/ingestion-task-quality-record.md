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
| CFG-14 | HTTP 数据源连接测试把表单提交的 JSON 文本 `defaultHeaders` 当成对象反序列化，合法请求头导致测试失败。 | 转换器兼容 JSON 对象、JSON 文本和空白文本；非法 JSON 返回明确参数错误。 | 已修复；保留原有 converter 覆盖并新增 5 项回归测试（4 项 Headers 解析、1 项 JSON 文本 Authorization 拒绝，共 13 项）；Chrome 浏览器使用强制要求自定义 Header 的本地 fixture，JSON 文本默认 Headers 连接测试成功，未保存临时数据源 |

## 数据源覆盖

2026-10-07 开发环境数据源管理页显示 11 条用户可见连接；分页 API 返回 12 条，其中包括一条不在普通列表展示的系统托管 Doris 连接。新建任务向导列出 16 种 connector 类型。对连接管理页测试、任务配置校验、Engine 侧验证和端到端执行分别记账，不能用其中一项代替其他项。FTP、SFTP、Amazon S3 的临时连接只在本轮浏览器实测时创建，已在收尾时删除。

| Connector 类型 | 本轮覆盖与结果 |
| --- | --- |
| JDBC | 临时通用 JDBC 数据源通过管理端和本地 Engine 客户端连接测试；使用 MySQL JDBC driver class 与 `/mnt/lc/seatunnel/arm64/lib/mysql-connector-java-8.0.27.jar` 完成 JDBC→JDBC 批量任务，3 行数据成功写入。临时数据源和数据库已删除。 |
| MYSQL | 持久连接管理页测试通过；Engine 批量来源/目标可用；完成 MySQL→MySQL 批量写入和 MySQL CDC→MySQL 增量写入；也作为离线文件导入目标。 |
| POSTGRESQL（内部类型 POSTGRE_SQL） | 开发环境持久连接的管理端测试通过；历史 PostgreSQL→Kingbase 与 Kingbase→PostgreSQL 批量任务均完成并各写入 22 行。本次新建 PostgreSQL→MySQL 链路的 Engine 来源连通性测试失败，日志显示 Engine 加载了 Vastbase JDBC jar，详见复核补记。 |
| ORACLE | 持久连接管理页测试通过；批量来源 Engine 检查复测通过，初次检查失败后重试成功；历史 Oracle→PostgreSQL 批量任务完成并写入 2 行。 |
| ZEONEDB-D | 向导可选；本轮复查本机 `/mnt/lc` 和 `192.168.100.91:/root/lc` 的部署目录、运行容器及本地驱动后，仍未发现可用服务或已验证驱动。厂商公开产品页列出 ZeoneDB-S/H/M 产品信息，但未找到可公开获取的部署包或 JDBC 驱动；因此未连接测试或运行任务。 |
| VASTBASE | 旧记录中的连接测试失败发生在测试容器停止期间；本次按授权启动容器后，管理页连接测试通过，收尾时恢复为停止状态。批量来源 Engine 检查通过；历史 Dameng→Vastbase、Vastbase→Vastbase 批量任务分别完成并写入 3 行、1 行。 |
| KINGBASE | 持久连接管理页测试和批量来源 Engine 连接检查通过；历史 PostgreSQL→Kingbase 与 Kingbase→PostgreSQL 批量任务均完成并各写入 22 行，已验证 source 和 sink 两种角色。 |
| DAMENG | 持久连接管理页测试和批量来源 Engine 连接检查通过；历史 Dameng→Vastbase 批量任务完成并写入 3 行。 |
| DORIS | 持久连接管理页测试和批量来源 Engine 连接检查通过；本次 Doris→MySQL 批量任务完成并写入 2 行。 |
| ELASTICSEARCH | 持久连接管理页测试通过；批量 source/sink 和实时 source Engine 检查通过；本次 Elasticsearch→MySQL 实时任务完成并写入 2 行。 |
| KAFKA | 持久连接管理页测试通过；修复 Kafka HOCON 中带点配置键被嵌套的问题后，Kafka→MySQL 单表实时任务成功运行，2 条测试消息写入目标表。批量 Kafka source/sink 仍没有端到端执行证据。 |
| FTP | 临时连接的浏览器连接测试通过；文件同步→MinIO 实际传输两个文件记录，目标内容与源文件 SHA-256 一致；临时连接已删除。 |
| SFTP | 临时连接的浏览器连接测试通过；文件同步→MinIO 实际传输两个文件记录，目标内容与源文件 SHA-256 一致；临时连接已删除。 |
| AMAZON S3 | 临时连接的浏览器连接测试通过；修复 binary 格式后完成 S3→MinIO 文件同步，目标 33 字节且 SHA-256 与源相同；临时连接和测试服务已删除。 |
| MINIO | 持久连接管理页测试通过；作为湖文件、FTP、SFTP、Amazon S3 文件同步的目标；另完成 MinIO→MinIO 文件同步，目标对象 42 字节且 ETag 与来源一致。全部本轮输出均已按测试前缀核对并清理。 |
| HTTP | 本次使用临时连接完成 HTTP→MySQL 批量任务并写入 2 行；请求为 GET/JSON，配置查询参数、自定义 Header、`$.data.*` 内容字段、`id=int`/`name=string` Schema、3 次重试和连接/读取超时。另用必须收到自定义 Header 的 fixture 在浏览器验证 defaultHeaders JSON 文本连接测试成功。HTTP 仅适用作来源，临时连接和服务已清理。 |

覆盖判断：16 种 connector 类型均在向导选项和适用性矩阵中逐项登记，配置表单字段和必填性也逐项盘点。除没有可用服务或已验证驱动的 ZEONEDB-D 外，其余 15 种类型均有成功的真实任务执行证据，覆盖 JDBC→JDBC、MySQL/CDC、Oracle、PostgreSQL 与 Kingbase 双向、Dameng/Vastbase、Doris、Elasticsearch、Kafka、HTTP，以及湖文件/FTP/SFTP/Amazon S3/MinIO 文件传输。这里的“覆盖”表示各类型至少有一条适用链路成功，不表示每一种 source/sink 方向、任务类型或可选参数组合都已执行。PostgreSQL 普通 JDBC Engine 检查另有共享驱动冲突疑点，尚未隔离复测；PostgreSQL CDC 本轮仅验证配置生成，未运行 Engine 任务。各连接器尚有未跑的字段值、认证方式、TLS、分页、格式、重试和故障组合，不能据此宣称所有功能都已端到端通过。

## 四种任务类型端到端验收

| 任务类型 | 实际执行 | 用户可配置项与参数覆盖 | 结果 |
| --- | --- | --- | --- |
| 批量数据引接 | MySQL→MySQL；任务 codex-audit-mysql-batch-20261001。通用 JDBC→通用 JDBC；任务 codex-generic-jdbc-batch-20261001。另有 Doris→MySQL、HTTP→MySQL 实际任务。 | 新建单表链路、来源/目标连接和表选择、发布、上线、手动运行；MySQL 任务验证 5 行及中文，通用 JDBC 任务验证 3 行（中文、带引号文本、小数），Doris 与 HTTP 各验证 2 行。通用驱动使用 `com.mysql.cj.jdbc.Driver`、Engine 挂载的 8.0.27 驱动 jar、默认 batch size 1000 和追加模式。HTTP 覆盖 GET/JSON、`/api/rows`、Query Params、Header、`$.data.*`、`id=int`/`name=string`、重试 3 次、退避 1000/10000ms、连接/读取超时 5000/15000ms；Doris 覆盖 FE 地址、HTTP/Query 端口、数据库、表和 MySQL 目标追加写入。多表路径另做浏览器检查，未执行多表迁移。 | 四个 Engine 作业均完成，目标数据核对一致。临时任务、数据源和专用数据表已清理。 |
| 实时数据引接 | MySQL CDC→MySQL；任务 codex-audit-mysql-cdc-20261001-2。另有 Kafka→MySQL 和 Elasticsearch→MySQL 实际任务。 | CDC 来源表和目标表选择、初始快照加运行期增量；启动后向来源插入一行，目标出现该行。Kafka 覆盖 topic、消费组、`earliest`、checkpoint、Poll Timeout、JSON schema 和任务级 client 配置；Elasticsearch 覆盖 HTTP endpoint、Basic auth、index、fields、`match_all` DSL、SCROLL、scroll time/size 和 MySQL 追加写入。未覆盖 CDC 的 SSL、GTID/server-id 边界及故障恢复组合。 | MySQL CDC 初始 2 行和新增第 3 行、Kafka 2 行、Elasticsearch 2 行均到达目标；任务与专用资源已清理。 |
| 离线文件导入 | 湖文件 uitest-sample.csv→MySQL；任务 codex-audit-file-ingest-20261001。 | 文件选择、CSV 格式/字段识别、目标数据源和自动建表写入；本次采用默认 CSV 解析参数。另浏览器走查 JSON、TXT、工作簿的识别与预览，但未将这些格式全部导入目标表。 | 3 行数据写入并核对；任务与专用目标表已清理。 |
| 文件同步任务 | 湖文件→MinIO、FTP→MinIO、SFTP→MinIO、Amazon S3→MinIO、MinIO→MinIO，各执行一次。 | 文件同步来源类型、来源 dataSourceId、远端连接参数、目录/对象前缀、正则文件名过滤、扩展名 csv、二进制文件格式、chunk size 1024、整文件传输、MinIO 目标前缀。S3 覆盖 endpoint、region us-east-1、bucket、root prefix、STATIC 认证、path-style access、连接超时 10000ms、请求超时 30000ms；密钥从未写入记录。FTP/SFTP 覆盖连接测试、远端文件选择和认证字段，使用的账号值不留存。MinIO→MinIO 使用 CSV 后缀过滤，源与目标对象 ETag 相同。 | 五条链路均完成；FTP/SFTP/S3/MinIO 的目标文件与源内容 SHA-256 或 ETag 一致。任务、临时连接、对象及临时服务已清理。 |

## 修复的任务体验问题

| 修复 | 验收证据 |
| --- | --- |
| 文件同步来源类型在新建向导中保留，不再错误回落为湖文件。 | 从 FTP、SFTP、Amazon S3 来源完成新建、配置、发布和运行。 |
| 新建远端来源图节点携带所选数据源 ID。 | S3 预览配置引用所选远端连接；任务成功执行。 |
| 文件同步按二进制文件传输设置 source/sink 的 file_format_type，避免要求文本 schema。 | 第一次 S3 运行捕获到 Engine schema 必填错误；修复后编辑、重发、运行成功，验证源/目标 SHA-256 一致。 |
| 文件同步任务概览不把字节数显示为行数；文件来源目标默认和回退值使用已配置的 MinIO。 | 浏览器新建路径、配置预览和文件同步列表指标检查通过。 |
| 文件导入预览使用稳定的非枚举行键并补齐中英文消息。 | CSV 识别、字段预览和导入成功。 |

## 参数覆盖边界

本轮参数覆盖依据产品表单中走过的实际路径，不是对 Engine connector 的所有可选参数做排列组合。已实际改变或验证的参数包括来源类型/连接引用、数据库表、通用 JDBC driver class/jar 路径及 batch size/追加模式、CDC 源表/目标表、HTTP method/format/path/query/header/defaultHeaders/schema/retry/timeout、Elasticsearch auth/index/fields/DSL/scroll、文件选择和格式、远端 endpoint/region/bucket/root prefix、静态认证类型、path-style access、超时、正则匹配、扩展名、chunk size、整文件模式和目标目录。未覆盖的可选配置包括连接器专属 TLS/代理、复杂格式 schema、并行度极值、失败重试故障组合、分页边界、断点续传故障注入及大文件压力测试。它们不影响本轮已执行路径的结论，但仍是后续 connector 验收项。

## 测试数据与资源清理

- 已删除本轮创建的批量、实时 CDC、离线导入、湖文件/FTP/SFTP/S3 文件同步任务定义；未删除列表原有的 验收-文件传输-MinIO-20260916 和 test_sync_minio。
- 已删除测试数据库 codex_ingest_audit_20261001 以及 seatunnel_web 中三张 codex_audit_* 专用测试表。
- 已删除 MinIO 中本轮使用的唯一输出前缀及 Engine 临时前缀；保留共享 MinIO 服务和原有对象。2026-10-07 MinIO→MinIO 复测的源前缀、目标前缀、任务与临时 `mc` alias 也已删除，并验证三个专用对象键均不存在。
- 已删除通用 JDBC 验收任务 `codex-generic-jdbc-batch-20261001`、临时数据源 `codex-generic-jdbc-audit-20261001` 以及仅含两张 `codex_jdbc_*` 测试表的数据库 `codex_generic_jdbc_20261001_1329`。
- 已删除本次 Doris、Elasticsearch、HTTP 复测的任务定义和专用输出表；已删除 Elasticsearch 测试 index、两个临时 HTTP fixture 目录和进程；HTTP 任务数据源已删除，defaultHeaders 浏览器回归使用的数据源仅为未保存草稿。HTTP 首次试跑因 fixture 将 query string 当作路径导致 404，修正 fixture 后同一定义运行成功。
- 已删除三条临时 FTP/SFTP/Amazon S3 数据源连接、S3 测试容器、FTP/SFTP 测试容器和 /mnt/lc/test/codex-ingest-audit-20261001 测试文件目录。共享 MinIO 容器里为本轮建立的两个 mc alias 也已删除。
- Kafka 复测前的任务和资源只使用隔离名称/前缀；没有清空其他会话数据或触碰 91:/root/lc。Kafka 复测期间仅按下文修改本机 Kafka advertised listener 并重建三个测试 broker，专用 topic、表和任务随后均已删除。
- MinIO→MinIO 复测使用独立的源/目标 prefix 和文件；任务离线、删除后已核验并删除三个专用对象键及临时 alias。

## 自动化验证与浏览器验收

四种任务的关键 happy path 已在本地 Chrome/Playwright 实际完成到任务运行和结果核对。除以下列出的单元测试外，不把静态配置校验、连接检查或草稿表单检查计作端到端成功。

| 验证 | 结果 |
| --- | --- |
| 前端 Jest（全量） | 28 个 Jest 套件、129 个测试全部通过；文件同步回归测试通过实际页面参数构建器覆盖 FTP、SFTP、S3 向导来源类型及 source/sink 数据源引用、binary 文件格式；指标页面测试覆盖旧请求不能覆盖新筛选结果。 |
| 前端 TypeScript | 通过。 |
| 前端生产构建 | Mako 生产构建成功。 |
| 后端回归测试 | 41 个 Maven reactor 模块构建成功；JobMetrics、MySQL CDC、PostgreSQL CDC 的 18 个测试、HOCON 脱敏器的 7 个测试和 HTTP Headers converter 的 13 个测试通过。 |
| 四类任务概览 summary API | MySQL 临时实例行验证 batch、stream、file-ingest、file-transfer 对 `FAILING`/保存点活动状态的分类和计数；夹具已删除。 |
| git diff --check | 通过。 |

2026-10-07 再次执行：前端全量 Jest 仍为 28 个套件、129 个测试通过；`yarn tsc` 和 Mako 生产构建通过。后端 41 个 Maven reactor 模块构建成功，18 个选定回归测试通过（JobMetrics 6、MySQL CDC 6、PostgreSQL CDC 6）；另运行 HOCON 脱敏器 7 项、HTTP Headers converter 13 项和 Kafka HOCON builder、连通性探针 source builder、最终 HOCON assembler 定向回归测试，均通过。

## 外部限制

- PostgreSQL 管理端连接测试通过；本次新建批量链路的 Engine 来源连通性测试失败，详见 2026-10-07 复核补记；未改变持久数据源参数。
- Vastbase 的旧管理页连接测试返回 11003，而批量 source Engine 检查重试通过；复核时启动原本停止的测试容器后管理页连接测试成功，并在收尾时停止该容器。仍未做本轮端到端数据写入。
- 通用 JDBC 已通过连接检查和 JDBC→JDBC 批量任务；ZEONEDB-D 仍没有可用实例或已验证驱动，本轮没有任务执行证据。
- HTTP、Doris 和 Elasticsearch 本轮均有真实任务执行；各数据库/连接器适用的任务类型并不相同，所以未把不适用的 source/sink 组合强行算作覆盖。

## 2026-10-07 复核补记

重新对照目标检查后，之前的质量记录不足以证明“所有数据源及所有配置参数均已端到端覆盖”：四种任务类型有实际运行证据，但每个数据源字段值与字段组合没有全部执行实测。ZEONEDB-D 无可用实例/驱动；本次将 Kafka 从早先连接失败推进到 Kafka→MySQL 实时任务成功，但批量 Kafka source/sink 仍未运行；PostgreSQL 普通 JDBC 新链路 Engine 检查仍疑似受共享驱动冲突影响且尚未隔离复测。PostgreSQL CDC 仅检查了配置生成，未运行 Engine 任务。下面补充 Doris、Elasticsearch、HTTP 的任务实跑、HTTP Headers 修复以及回归证据。

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

### Kafka 实时链路复测

- 使用持久 Kafka 测试连接创建单表实时任务，来源为 Kafka、目标为 MySQL。配置并验证了 Topic、消费组、`earliest` 起点、Checkpoint Offset 提交开关、Poll Timeout、JSON schema 和任务级 `kafka.config`（`compression.type=lz4`）；配置预览中的 SASL 配置仍保持脱敏，带点的 Kafka client 配置键保持为扁平键。
- 在浏览器检查了 Topic/正则订阅切换、`specific_offsets` 与 `timestamp` 条件字段、Checkpoint 开关及 text 格式的分隔符条件字段，最终恢复为 Topic、`earliest` 和 JSON 后保存。JSON、text、csv、avro、protobuf 出现在格式菜单中；本次真实数据任务只运行 JSON。
- 最初 Engine consumer 能读取 topic 元数据，却因 Kafka broker advertised listener 返回外部地址而连接超时。为本机测试环境将 `/mnt/lc/kafka/docker-compose.yml` 三个 broker 的 advertised listener 改为 SeaTunnel 测试节点可达的内网地址，并逐个重建 kafka0/kafka1/kafka2；没有改动仓库内或持久数据源连接参数。修复后 Engine consumer 成功读到两条隔离测试消息，MySQL 目标表查询确认写入 2 行。
- 此流程同时复现并修复了两处配置问题：Kafka 连通性探针的 JSON source 缺少 schema；`kafka.config` 和最终任务 HOCON 组装把 `sasl.jaas.config` 等带点键误解析为嵌套对象。新增 builder 与 assembler 回归测试覆盖 schema 和扁平键行为。
- 测试 Kafka topic、MySQL 目标表和实时任务均已停止并删除；本次没有保留测试记录。UI 列表中的行数指标仍显示 0，因此写入验收以目标表查询结果为准。

### MinIO 文件同步来源复测

- 在本机测试 MinIO 桶中建立唯一的 42 字节 CSV 文件，使用文件同步任务从 MinIO 读取到 MinIO 的另一个隔离 prefix；浏览器的来源、目标和客户端连通性检查均通过，配置校验 0 项。
- 配置了 `/crminio07` 来源目录、`source\\.csv` 文件名正则、`csv` 扩展名、binary 格式、1024 字节分块和完整文件模式；预览 HOCON 中 endpoint、path-style、桶和路径正确，凭证保持脱敏。
- 任务运行成功，列表显示 2 条记录；目标 `source.csv` 为 42 字节，ETag 与来源相同。任务下线并删除后，源/目标 prefix、初始长名 prefix 和临时 `mc` alias 均已删除，并核验对应对象不存在。

### Doris 批量来源复测

- 使用本机 Doris 测试实例创建 `test.codex_review_doris_20261007` 两行源表和 MySQL 目标表 `seatunnel_web.codex_review_doris_sink_20261007`；任务 `codex-review-doris-source-20261007`（definition `23284887163232`）的 Doris→MySQL 连接检查通过、配置校验 0 项。
- Engine 首次连接超时是因为 Doris BE 所在 Docker 网络未与 SeaTunnel worker 共享。仅将 `seatunnel_worker_1`、`seatunnel_worker_2` 临时加入 `doris_doris-net` 后，9060/8060 端口连通；任务实例 `23284956123232` 完成，Engine 指标 read/write 各 2 行，MySQL 目标表查询确认两条记录。
- 任务下线并删除，源/目标表已删除；worker 已从 Doris 网络断开。未修改 Compose 文件。

### Elasticsearch 实时来源复测

- 创建隔离 index `codex_review_es_20261007`，包含 `id/name` 两条文档；任务 `codex-review-es-source-20261007`（definition `23284990872032`）选择 Elasticsearch→MySQL，客户端、来源和目标连接检查均通过，配置校验 0 项。
- 配置 Basic auth、HTTP host `192.168.100.95:9201`、index、`id/name` fields、`{"match_all":{}}` DSL、SCROLL、scroll time `2m` 和 scroll size `100`；目标使用 MySQL append。实例 `23285023813600` 结束状态为 FINISHED，API 指标 read/write 各 2 行，目标表查询确认两条数据。
- 下线并删除任务，删除目标表和 index，分别核验目标行数为 0、index 删除成功。任务列表当时显示的行数仍为 0，验收依据是实例 API 指标和目标表查询。

### HTTP 批量来源复测

- 在本机测试目录启动只返回两条固定 JSON 记录的 HTTP fixture，并创建临时数据源 `codex-review-http-source-20261007`。连接参数为 Base URL `http://192.168.100.95:18109`、健康检查 `/health`、NONE 认证、连接/读取超时 5000/15000ms；客户端和 MySQL 目标连通检查通过。
- HTTP source 配置 GET `/api/rows`、JSON、Query Param `source=review`、Header `X-Codex-Review=http-source`，解析选择 `$.data.*`，schema 为 `id=int`、`name=string`；缺失字段返回 null 开启，分页不启用，重试 3 次、退避 1000/10000ms。配置校验 0 项，预览响应中的 headers 和 JDBC password 保持脱敏。
- 首次 Engine 运行返回 404，原因是临时 fixture 错把含 query string 的请求目标当作路径比较；fixture 改为按 URL path 路由后，同一任务实例 `23285204580576` 运行 FINISHED，read/write 各 2 行，MySQL 目标表逐行核对为 `1/http-source-one`、`2/http-source-two`。失败实例 `23285187262688` 的日志记录了首次 404。
- HTTP 默认 Headers 文本输入另发现转换问题：表单 JSON 字符串被直接按对象反序列化。转换器现兼容 JSON 对象、JSON 字符串和空白字符串，并对非法 JSON 返回明确错误；保留原有认证必填、URL 校验、凭证脱敏与表单配置用例，新增 5 项用例（4 项 Headers 解析、1 项 JSON 文本 Authorization 拒绝）后共 13 项 converter 回归测试通过。后端重启后另用临时 header-enforcing fixture 实际走过浏览器连接测试，数据源默认 Headers JSON 字符串被成功带到 `/health`；临时数据源未保存。任务执行使用空的数据源默认 Headers，并以任务级 Header 验证 HTTP source 请求头。
- 任务定义已下线并删除；目标 MySQL 表、HTTP 数据源和 fixture 进程/目录均已清理。

### HOCON 预览凭证脱敏复测

Luna Max 复审发现 HOCON 预览原先可能返回 ES API key/TLS 密码、HTTP 请求头、Kafka SASL JAAS 内容，以及 JDBC/HTTP URL 和 ES `hosts` 中嵌入的凭证。脱敏器现按敏感字段名遮蔽凭证、遮蔽整个 HTTP headers 对象，并清理所有字符串中的 URL user-info 和敏感 query/分号参数；HOCON 解析失败时返回固定的全遮蔽提示。

新增的 7 项回归测试覆盖 ES API key 与 TLS 密码、HTTP Authorization 和自定义 headers、Kafka `sasl.jaas.config`、JDBC/HTTP/Oracle thin/SQL Server URL 凭证、ES hosts user-info，以及解析失败闭锁。校验同时确认主机、路径和非敏感 URL 参数仍保留。

### 本次复核清理

- PostgreSQL 探针专用表和 publication 已删除并核实不存在；两个 PostgreSQL 新建流程均取消，未保存草稿。
- 未添加新的持久数据源记录；Kafka 测试任务已删除，专用 topic 和 MySQL 目标表已删除。为解决本机 Kafka broker advertised listener 的网络问题，本次在 `/mnt/lc/kafka/docker-compose.yml` 更新内网 advertised listener 并逐个重建三个测试 Kafka broker；没有触碰 91:/root/lc 的部署。
- 先前批量、离线导入和文件同步任务端到端记录仍有效；本次另新增 Kafka→MySQL 实时端到端证据。测试资源均按上文清理记录处理，列表页和指标页复核不替代真实运行证据。
- 检查了 `/mnt/lc` 和 `91:/root/lc` 中的部署目录与运行容器；本机可见 MySQL、PostgreSQL、Oracle、Kafka、Elasticsearch、Doris、MinIO 等测试服务，91 上有 Kingbase、ArangoDB 等服务，两个位置均未发现 ZeoneDB 安装包、服务或 JDBC 驱动。另核对了[厂商公开产品页](https://www.zeonedb.com/)和[ZeoneDB-M 介绍](https://www.zeonedb.com/zeonedb-m/)；页面介绍 ZeoneDB-S/H/M 产品，但没有可公开获取的部署包或 JDBC 驱动下载入口。Apache SeaTunnel 2.3.13 JDBC 文档说明自定义驱动需放入连接器依赖目录；未找到厂商驱动，不能用别的数据库服务冒充 ZeoneDB 验收。Kafka 复测只修改本机 Kafka advertised listener；没有在 91 部署或修改 ZeoneDB。
- Kafka 复测前，映射端口 19092 接受 TCP 连接，但 SeaTunnel consumer 无法连接 broker 元数据提供的外部 advertised listener；更新内网地址并重建后，Kafka→MySQL 实时消息链路运行通过。该修复只针对本机测试部署网络，不代表 Kafka 批量 source/sink 已完成实测。
