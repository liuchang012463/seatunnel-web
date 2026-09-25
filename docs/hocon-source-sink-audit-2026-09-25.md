# 引接任务 HOCON source/sink 审计报告

- 日期：2026-09-25
- 范围：SeaTunnel Web 所有任务类型构建后的 HOCON 中 `source {}` 与 `sink {}` 段
- 对照基线：Apache SeaTunnel 2.3.13 官方连接器文档（seatunnel.apache.org/docs/2.3.13）
- 方法：静态代码审计（只读，未改代码）+ 官方文档逐键比对。未做运行时任务验证；以下"引擎行为"推断均以上游 2.3.13 文档为准，自维护引擎如有扩展需按实际情况甄别。

## 一、审计覆盖的任务类型与生成链路

| 任务类型 | 入口 | source/sink 构建器 |
|---|---|---|
| 批量引接-单表/增量（GUIDE_SINGLE / *_INCREMENTAL） | `GuideSingleJobDefinitionHandler` → `GuideSingleHoconBuildService` | `DataSourceSourceBuilder` / `DataSourceSinkBuilder` → 各数据源 `DataSourceHoconBuilder` |
| 批量引接-多表（GUIDE_MULTI） | `GuideMultiJobDefinitionHandler` → `GuideMultiHoconBuildService`（服务端合成双节点 DAG） | 同上（Jdbc 单/多表 target builder、CDC resolver、DorisBatchBuilder） |
| 实时引接（STREAMING） | `StreamingJobDefinitionServiceImpl` → 同一批 handler | CDC source：`AbstractCdcSourceBuilder`（MySQL/PG 子类）；sink 完全复用批量 JDBC/Doris sink |
| 文件引接-离线接入（FILE_INGEST） | `GuideSingleJobDefinitionHandler`（FILE_SYNC 判定为 FILE_INGEST/TRANSFER） | source：`S3FileHoconBuilder`（MinIO/S3）；sink：JDBC/Doris 入表入湖 |
| 文件引接-文件流转（FILE_TRANSFER） | 同上 | source/sink：`FtpFile`/`SftpFile`/`S3File`（`AbstractRemoteFileHoconBuilder`） |
| 脚本任务（SCRIPT） | `ScriptJobDefinitionHandler` → `ScriptDatasourceHoconBuildService` | 用户书写 source/sink，按数据源重建连接键 |
| 连通性验证任务 | `verify/job/*ConnectivityTestJobDefinitionBuilder` | 真实连接器最小 source + `Console` sink |
| 湖仓（lake binding） | `LakeJobBindingResolver` → `DataSourceSinkBuilder.overrideLakeDatabase` | 仅覆写 Doris sink 的 `database` |

公共管线：`HoconConfigBuilder.build()` → `SeaTunnelConfigUtil.generateConfig()` 拼装 `env/source/transform/sink` 四段；source/sink 插件块以 connectorName 为根键渲染（`NodeGroup.flat`）。

## 二、结论摘要

整体结构是健康的：插件根键命名（`Jdbc`/`Doris`/`S3File`/`FtpFile`/`MySQL-CDC`/`Postgres-CDC` 等）、`schema { fields {} }` 写法、ES `hosts` 数组、`save_mode` 系列默认值等与 2.3.13 文档一致。但存在 **3 个可能直接导致任务失败或语义错误的高危问题**（Kafka 正则订阅、JDBC exactly-once、文件增量同步键名），以及一批不符合文档最佳实践的中低危问题。

> 说明：引擎为自维护 2.3.13。若以下 P0-3（文件增量键）等引擎侧确有扩展，可降级处理；否则均为真实缺陷。

## 三、高危问题（P0）

### P0-1 Kafka source 的 `pattern` 语义与官方文档冲突
- 现状：Web 把 `pattern` 当作"正则 topic 表达式"字符串输出，且校验强制 `topic`/`pattern` 二选一。
  - `KafkaHoconBuilder.java:45-46`（`topic`/`pattern` 平铺透传）、`:128-133`（"must configure exactly one of topic or pattern"）
  - `KafkaOptions.java:13`：Web 侧 `PATTERN` 定义为 `stringType()`
  - UI `KafkaNodeConfig.tsx:73,102-115`：订阅方式选"正则"时把正则串写入 `config.pattern`
- 文档：2.3.13 Kafka Source 中 `pattern` 是 **Boolean（默认 false）**，是"把 `topic` 当正则解释"的开关；正则本身写在 `topic`（示例 `topic = ".*seatunnel*."`、`pattern = "true"`）。且文档 `topic` 为必填。
- 后果：`pattern = "metrics_.*"` 在上游引擎按布尔解析会直接报错；即便引擎容忍字符串，也无法命中文档语义。当前系统**永远无法生成合法的正则订阅配置**。
- 修复建议：`pattern` 改为布尔开关输出（`pattern = true`），正则值写入 `topic`；Web 规则改为"topic 必填 + pattern 可选布尔"，UI 同步调整；`KafkaOptions.PATTERN` 改 `booleanType()`。

### P0-2 JDBC sink exactly-once 缺少 `xa_data_source_class_name`
- 现状：`JdbcSinkOptionAppender.java:103-109` 仅在配置了 `exactlyOnce`/`is_exactly_once` 时输出 `is_exactly_once`，全仓无任何地方输出 `xa_data_source_class_name`；`max_retries` 也从不输出（仅存在于校验规则）。
- 文档：`is_exactly_once=true` 必须配套 `xa_data_source_class_name`（且要求 XA 驱动与数据库配置，如 PG 调大 `max_prepared_transactions`、MySQL 8.0.29+ 授 XA_RECOVER_ADMIN）。
- 后果：用户开启 exactlyOnce 的任务在引擎侧直接失败或退化为非 XA 写入。
- 修复建议：开启 exactly-once 时要求（并在表单中采集）`xa_data_source_class_name`，随 HOCON 输出；可选补 `max_retries`、`transaction_timeout_sec` 的透传。

### P0-3 FTP/SFTP 增量同步键与官方文档不一致
- 现状：`AbstractRemoteFileHoconBuilder.java:30-35` 增量模式输出 `read_update_info=true`、`target_path`、`update_strategy`（默认 `only_add`）、`file_details_info`（默认 `len_mtime`）。
- 文档：2.3.13 FtpFile 选项表无 `read_update_info`、`file_details_info`；官方增量语义为 `sync_mode = "update"` + `target_path` + `update_strategy`（默认 `distcp`）+ `compare_mode`（默认 `len_mtime`）。
- 后果：若引擎未做私有扩展，`read_update_info=true` 无法触发增量（引擎按 `sync_mode` 判断），`file_details_info` 为未知键——文件流转的增量同步功能实际失效。
- 修复建议：对齐官方键：`sync_mode`/`compare_mode`/`update_strategy`/`target_path`；删除 `read_update_info`/`file_details_info`。改动前先在自维护引擎上实测现有键是否被支持（决定是"改键"还是"文档化扩展"）。

## 四、中危问题（P1，不符合文档/最佳实践）

### P1-1 `where_condition` 被追加进 JDBC sink
`JdbcExtraOptionAppender` 为 source/sink 共用（`AbstractJdbcBatchBuilder.java:78,94`），`appendDirectWhereCondition`（`JdbcExtraOptionAppender.java:72-80`）把节点上的 `where_condition` 也写进 sink 配置。2.3.13 JDBC **sink 选项表没有 `where_condition`**。增量任务生成的 sink 配置带着一个无效键，轻则噪音、重则被严格校验拒绝，且与 sink 的 `query`/`generate_sink_sql` 语义纠缠。建议：sink 侧跳过 `where_condition`（或仅当 sink `query` 存在时透传）。

### P1-2 JDBC source 输出多余的 `database` 键
`JdbcSingleSourceTargetBuilder.java:52-54` 给 source 写 `database`；2.3.13 JDBC source 选项表没有 `database`（`table_path`/`table_list` 已含库名，sink 才有 `database`）。多余键依赖引擎宽容解析。建议：source 侧不再输出 `database`。

### P1-3 `enable_upsert=true` 无条件输出
`JdbcSinkOptionAppender.java:77-81` 恒写 `enable_upsert`（默认 true）。文档说明默认即 true，但明确提示"**数据无主键重复时设为 false 可加速导入**"；且 append 写入模式 + 表带主键时也恒为 true，语义被强制成 upsert。建议：仅 upsert 模式或用户显式配置时输出，append 模式默认不写或写 false。

### P1-4 MySQL-CDC 多表向导丢失 `startup.mode`
`GuideMultiHoconBuildService.java` 中 PG 分支写 `startup.mode`（:229-233），MySQL 分支（:224-227）不写 → MySQL 多表实时任务恒为 `initial`（全量+增量），无法选 `latest`。UI 向导明明有 `startupMode` 字段（`GuideMultiJobContent.java:46`）。建议：MySQL 分支同样输出 `startup.mode`。

### P1-5 CDC source 输出 2.3.13 选项表中不存在的键
- `hostname`/`port`：`AbstractCdcSourceBuilder.java:93-97` 从数据源 host/port 别名输出；2.3.13 MySQL-CDC/Postgres-CDC 选项表只有 `url`（连接凭据为 `url/username/password`），`url` 已由 `BaseConnectionParam.url` 带出，`hostname/port` 属于未知键。
- PG 的 `schema-names`：文档仅在示例出现、选项表无定义（`PostgreSqlCdcSourceBuilder.java:68`）。
建议：只输出文档键（url/username/password/database-names/table-names/...），去掉 `hostname/port`；`schema-names` 若引擎不识别则移除。

### P1-6 `server-time-zone` 实际永远不会输出
builder 仅在节点带 `server-time-zone` 时输出（`AbstractCdcSourceBuilder.java:105`），UI 无该字段；官方默认 `UTC`。跨时区部署时 CDC 时间字段会漂移。建议：UI 暴露该字段，或按数据源/部署时区显式输出（模板里已示范 `Asia/Shanghai`）。

### P1-7 Doris `sink.label-prefix` 每次构建重新生成
`DorisBatchBuilder.java:317-323` 用 `"seatunnel_" + System.currentTimeMillis()`。文档要求 2pc 场景 label-prefix 全局唯一以保证 EOS；时间戳前缀虽然"唯一"，但每次构建/重跑都变化，2pc 开启后故障恢复期的预提交事务无法按前缀追踪。建议：label-prefix 改为任务级稳定值（如 jobDefinitionId/instanceId 派生）+ 用户可覆盖；`sink.enable-2pc` 输出布尔而非字符串 `"true"`（:325-328）。

### P1-8 Doris 默认建表模板硬编码，不适用于无主键表与生产环境
`DorisBatchBuilder.java:421-435`：`ENGINE=OLAP` + `UNIQUE KEY(${rowtype_primary_key})` + `DISTRIBUTED BY HASH(${rowtype_primary_key})` + `"replication_allocation" = "tag.location.default: 1"`。无主键表占位符展开为空会生成非法 DDL；副本数 1 仅适合单机测试。湖仓模式无任何模板/副本配置入口。建议：无主键表回退 `DUPLICATE KEY` 模板；副本数可配置。

### P1-9 Doris source `doris.filter.query` 用 `lastIndexOf("WHERE")` 截取
`DorisBatchBuilder.java:270-280`。含子查询/字符串常量/ORDER BY 的 SQL 会把非过滤子句塞进 filter（如 `ORDER BY` 直接追加）。建议：SQL 模式改走引擎支持的过滤下发方式或做 SQL 级解析。

### P1-10 S3File 输出文档外的 `enable_file_split`/`file_split_size`
`S3FileHoconBuilder.java:38-61` 白名单映射包含这两个键，2.3.13 S3File 选项表没有它们（自维护引擎扩展则罢）。建议：核实引擎支持后保留或移除。

### P1-11 流式 env 无 checkpoint 兜底
`StreamingEnvConfigExtender.java:17-25` 仅在 `checkpointInterval > 0` 时输出 `checkpoint.interval`；UI 清空后 env 完全无 checkpoint。而 Doris 2pc、Kafka exactly-once 语义、`commit_on_checkpoint` 都依赖 checkpoint。建议：STREAMING 任务默认强制输出 `checkpoint.interval`（如 30s），UI 不允许为空。

### P1-12 批量 JDBC 分片最佳实践缺口
Web 仅透传 `splitSize → split.size`（`JdbcSourceOptionAppender.java:20-23`）。文档最佳实践：无主键/唯一索引且未配 `partition_column` 的表**单并发读**；`split.size`/`partition_num` 只对 `table_path` 生效、对 `query` 无效。大表自定义 SQL 场景下用户没有任何分片手段。建议：表模式暴露 `partition_column`（及上下界），SQL 模式提示用户用 `{{...}}` 分片或明确告知单并发。

### P1-13 Kafka `kafka.config` 中的 SASL 明文密码
`KafkaClientProperties.java:24-37` 把 SASL 用户名/密码拼成 `sasl.jaas.config` 明文进 HOCON，随作业配置落盘/提交。建议：评估配置加密或在文档中明示风险。

### P1-14 JDBC 系密码不解密而 Doris 解密（链路不一致）
`AbstractJdbcHoconBuilder.processPassword` 为 no-op（JDBC 系原样输出库中 password），Doris 调 `PasswordUtils.decodePassword`（`DorisBatchBuilder.java:234-237`）；且 `PasswordUtils.encodePassword` 无生产调用点（库中疑似明文）。建议：统一密码存取与输出链路。

## 五、低危/规范问题（P2）

1. **规则与产物脱节**：CDC 的 `stop.mode`、`format`、`exactly_once`、`schema-changes.enabled`、`snapshot.*`、`connect.*` 等 option rule 均定义（`MySQLCDCSourceOptionRule.java`、`CDCJdbcSourceOptions.java`）但 builder 从不输出——表单/规则配了不生效，只能靠 `extraParams` 逃生门。JDBC sink `max_retries` 同类。
2. **port 以字符串输出**：`port = "3306"`（`BaseConnectionParam.port` 为 String，CDC/JDBC 直接透传），依赖引擎宽松转换；建议转 int。
3. **驱动兜底值问题**：MySQL 兜底 `com.mysql.jdbc.Driver`（5.x 老类名，`MysqlBatchBuilder.java:12-14`）；Oracle `defaultDriver()` 返回 null（conn 无 driver 时不输出 driver 键）；通用 JDBC 兜底空串会输出 `driver = ""`。
4. **dialect 映射**：Kingbase/Vastbase sink 强制 `dialect = "Postgres"`；Kingbase source 无 dialect，依赖连接器 URL 识别 `jdbc:kingbase8`，需按引擎方言工厂验证。
5. **同根键重复块**：同一 connectorType 多个 source 渲染为两个同名块（`NodeGroup.flat`），依赖引擎对重复键的合并行为，无防护。
6. **连通性验证语义缺口**：Doris 验证走 JDBC 探针（9030），不验证 StreamLoad（8030/fenodes）——sink 真正依赖的 HTTP 端口未被覆盖（`DorisConnectivityTestJobDefinitionBuilder.java:62-63`）；Kafka 验证 `start_mode=latest` 空转依赖后续产消息。
7. **`SeaTunnelConfigUtil.generateConfig` 占位符链式替换**：用户内容含 `source_placeholder` 等字面量会被误替换。
8. **source 与 sink 读键位置不一致**：sink 的 dbType/pluginName 从外层 `data` 读、source 从 `config` 读（`DataSourceSinkBuilder.java:78-79` vs `DataSourceSourceBuilder.java:92-93`），部分 payload 会在 sink 报 "Missing required field 'dbType'"。
9. **plugin_input/plugin_output 依赖前端写入**：后端 `DagBuildContext.resolveSourcePluginOutput/resolveSinkPluginInput` 是死代码；前端漏写时带 transform 的 DAG 会断链。
10. **HTTP**：`method` 限 GET/POST 与文档一致（文档原文 "only supports GET, POST"）；`pageing`、`json_filed_missed_return_null` 的"怪拼写"与上游文档一致（注意别"纠正"）。

## 六、与文档一致、无需改动的项（抽样）

- JDBC `username` 键：2.3.13 source/sink 文档键名均为 `username`（老版本是 `user`），Web 输出正确；Web 规则还保留了 `user` fallback。
- `schema_save_mode`/`data_save_mode`/`batch_size`：语义与文档枚举一致，`batch_size` 默认 1000 与文档一致；`schema_save_mode` 缺省更严格（ERROR_WHEN_SCHEMA_NOT_EXIST），符合"不静默建表"的安全取向（autoCreateTable 时才 CREATE）。
- ES：`hosts` 输出为数组、`query` 默认为 `{"match_all":{}}` 对象、`auth_type`/`auth.api_key_*`/`tls_*` 键与文档一致；`index_type` 默认不输出（文档建议 ES6+ 不指定）。
- S3File：`bucket`/`fs.s3a.*`/`hadoop_s3_properties`/`file_format_type`/`schema { fields {} }`/`csv_use_header_line`/`skip_header_row_number` 等与文档一致；`schema.fields` 值带引号在 HOCON 中等价合法。
- Kafka sink：`semantics`（默认 NON）、`transaction_prefix`、`partition`/`partition_key_fields` 互斥校验与文档一致。
- CDC：`database-names`/`table-names`/`table-pattern`/`startup.mode`/`startup.specific-offset.*`/`startup.timestamp`/`server-id`（连字符）/`debezium` 嵌套对象的键形均与文档一致；PG 侧 `slot.name` 必填、`decoding.plugin.name=pgoutput`、publication 走 `debezium.publication.name` 属正确实践（官方要求每任务唯一 slot）。
- 文件：`FtpFile`/`SftpFile` 连接键（host/port/user/password + `connection_mode`/`remote_verification_enabled`）与文档一致（注意 Web `connection_mode` 默认 `passive_local`，文档默认 `active_local`——非错误，属差异化默认值）。
- Console sink（连通性验证）块内 `parallelism = 1` 为合法通用选项。

## 七、修复优先级建议

| 优先级 | 事项 | 涉及文件 |
|---|---|---|
| P0 | Kafka `pattern` 改布尔开关 + 正则入 `topic`（后端+UI+校验） | KafkaHoconBuilder、KafkaOptions、KafkaNodeConfig.tsx |
| P0 | exactly-once 补 `xa_data_source_class_name` 采集与输出 | JdbcSinkOptionAppender、相关表单 |
| P0 | 文件增量键对齐 `sync_mode`/`compare_mode`（先实测引擎） | AbstractRemoteFileHoconBuilder |
| P1 | sink 剔除 `where_condition`；source 剔除 `database` | JdbcExtraOptionAppender、JdbcSingleSourceTargetBuilder |
| P1 | `enable_upsert` 按需输出 | JdbcSinkOptionAppender |
| P1 | MySQL-CDC 多表补 `startup.mode` | GuideMultiHoconBuildService |
| P1 | CDC 去掉 `hostname/port`、补 `server-time-zone` | AbstractCdcSourceBuilder |
| P1 | Doris label-prefix 任务级稳定化、建表模板兜底 | DorisBatchBuilder |
| P1 | 流式 env 默认 `checkpoint.interval` | StreamingEnvConfigExtender/UI |
| P1 | JDBC source 暴露 `partition_column` | JdbcSourceOptionAppender、SourcePanel |
| P2 | 见第五节逐条 | — |

每项修复按仓库约束：小步提交（Conventional Commits），涉及用户可操作行为的改动需用 Chrome dev/Playwright 完成 happy path 验收后再宣布完成。

## 八、修复处置记录（2026-09-25，fix/hocon-audit-20260925 分支）

修复前先对自维护引擎（/mnt/lc/seatunnel/arm64/connectors，2.3.13）的 connector jar 逐键核实，多处修正了本报告基于上游文档的推断：

### 引擎核实结论（对本报告的修正）

| 项 | 报告推断 | 引擎 jar 实测 | 处置 |
|---|---|---|---|
| P0-1 Kafka pattern | pattern 是布尔开关，正则入 topic | `KafkaSourceOptions.PATTERN` 为 `Option<Boolean>`，TOPIC 为 String —— 推断正确 | 已修复 |
| P0-3 文件增量键 | 引擎可能未支持 read_update_info | fat jar 中仅有 `sync_mode`/`target_path`/`update_strategy`/`compare_mode`；且 `FileUpdateStrategy` 枚举只有 `DISTCP`/`STRICT`（无 `only_add`）—— 比报告更严重 | 已修复 |
| P1-5 CDC hostname/port | 2.3.13 只认 url，应删 hostname/port | cdc-base `JdbcSourceOptions` 恰恰相反：只有 `hostname`(String)/`port`(Integer)/username/password，**无 url** —— 删 hostname/port 会导致任务失败 | 反向修复：保留 hostname/port，删除 url，port 转整数 |
| P1-10 S3File 扩展键 | 需核实引擎 | 引擎 jar 存在 `enable_file_split`/`file_split_size` —— 属引擎私有扩展 | 保留不动 |
| PG schema-names | 文档无定义需移除 | PG jar 中未见该键，但移除收益低、风险未知 | 暂保留，待引擎实测 |

### 已修复（按提交顺序）

| 提交 | 内容 |
|---|---|
| e4d451a2 | P0-1：Kafka `pattern` 改布尔开关、正则入 `topic`（Options/Builder/OptionRule/UI + 遗留 payload 迁移与歧义校验） |
| 7470bc55 | P0-2 + P1-1/2/3：exactly-once 缺 `xa_data_source_class_name` 时构建期报错（含 extraParams 场景）；sink 剔除 `where_condition`；source 剔除 `database`；`enable_upsert` 仅在显式配置或 upsert 模式输出 |
| 4b5c624d | P0-3：文件增量键改为 `sync_mode="update"` + `target_path`(必填) + `update_strategy`(distcp\|strict) + `compare_mode`(len_mtime\|checksum)；port 转整数；UI 默认 only_add→distcp |
| ed715d6a | P1-4/5/6：MySQL-CDC 多表补 `startup.mode`；CDC 删 `url`、port 转整数；`server-time-zone` 采集（节点 serverTimeZone 别名）+ 面板字段 |
| 0bfec834 | P1-7/8/9：Doris 开 2pc 时必须有任务级稳定 `sink.label-prefix`（否则构建期报错）、enable-2pc/enable-delete 输出布尔；无主键表回退 `${rowtype_duplicate_key}` 的 DUPLICATE KEY 模板（引擎 DorisCatalogUtil 实测支持该占位符）；`doris.filter.query` 改为顶层 WHERE 提取（跳过子查询/字符串字面量，截除 ORDER BY/GROUP BY/HAVING/LIMIT） |
| 0d8fb05 | P1-11/12/13(部分)/14 + P2-2/3/8：流式 env 恒输出 `checkpoint.interval`（默认 30s）；JDBC source 一等透传 `partition_column` 及上下界；JDBC 密码链路与 Doris 统一（新增 `PasswordUtils.decodeIfEncrypted`，明文原样透传）；JDBC-MYSQL 驱动兜底改 `com.mysql.cj.jdbc.Driver`；sink 节点 dbType/pluginName 先读 config 再回退 data |
| 0a3630f1 | P2-7：`SeaTunnelConfigUtil` 占位符单遍替换（用户内容含占位符字面量或 `$` 不再被误替换） |
| 995c4d5/4c2adb6f 等 | 适配既有单测（JDBC source 不再输出 database、文件新键、Kafka 迁移用例） |

### 暂不修复与遗留

- **P1-13 Kafka SASL 明文密码落盘**：属产品级配置加密决策（需与 P1-14 的存储加密统一推进），本次仅统一了读取链路，未引入存储加密。
- **P2-1 CDC stop.mode/format/exactly_once 等规则不生效**：引擎 cdc-base 实测支持这些键，但当前新向导 UI 无对应字段，建议随画布/表单重建时补齐。
- **P2-4/5/6**（Kingbase/Vastbase dialect、同根键重复块、Doris StreamLoad 端口校验）：需引擎运行时实测，静态修复风险大于收益。
- **UI 入口缺口（修复前已存在）**：Kafka 节点面板（`KafkaNodeConfig`）与实时 SourcePanel（`serverTimeZone` 字段所在）位于 `stream-link-up/workflow/` 旧画布目录，当前路由已全部指向新向导/表单页，该目录无路由可达。相关 UI 改动编译与类型检查通过，待画布重新接入后即可生效；Kafka/HOCON 语义已由后端单测覆盖。
- 验收：改动模块 `mvnw test` 全绿（core 95 例），tsc 无新增报错；file-sync 编辑页（file-transfer 路由）画布与面板交互冒烟通过。
