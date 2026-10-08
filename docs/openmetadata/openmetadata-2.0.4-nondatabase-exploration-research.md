# OpenMetadata 2.0.4 非数据库源探查能力调研

调研日期：2026-10-08
调研对象：Kafka、HTTP(REST API)、FTP/SFTP、S3/MinIO 在 OM 升级到 2.0.4 后新增的探查类能力，以及能否并入 SeaTunnel Web。

证据基线：
- OpenMetadata fork `/home/haruka/workspace/OpenMetadata`，分支 `codex/openmetadata-extensions-2.0.4`（基于官方 2.0.4-release），对比分支 `codex/openmetadata-extensions-1.12.10`。
- 官方 Java SDK / spec `org.open-metadata:openmetadata-sdk:2.0.4`、`openmetadata-spec:2.0.4`（本地 `~/.m2`）。
- 生产自定义 ingestion 镜像 `openmetadata/ingestion:2.0.4-kingbase-dameng-vastbase-custom`（`beca492a9e92`）。
- SeaTunnel Web 当前代码（分支 `develop`）。

## 1. 结论摘要

1. **OM 2.0.4 对这几类源没有 profiler。** 探查（指标、行数、列统计）在 OM 中始终是 Table 级能力；Kafka/S3/SFTP/REST 不注册 `profiler_class`，也没有 profiler pipeline 配置类型。Kafka 与 S3 的连接 schema 在 2.0.x 新增了 `supportsProfiler` 字段，但它**在 UI 中被显式隐藏**，只用来放行 AutoClassification 代理，不代表有 profiler。这是最容易误判的一点。
2. **2.0.x 真正新增的是"样本数据 + 自动分类"**：Kafka 主题样本消息、S3/MinIO 结构化容器样本数据，均通过新增的 AutoClassification pipeline 提供；同时结构化容器的清单（manifest）配置能力显著增强。
3. **HTTP/REST 没有任何新增探查能力**，只有内部解析重构；FTP 至今没有 OM 适配器；SFTP 的 `extractSampleData` 在 1.12.10 就已存在，不是新增。
4. **可以并入 Web，但并入的是"样本数据/数据模型"而非"探查指标"**。SDK 2.0.4 已提供 Topic/Container/Directory/File/APICollection/APIEndpoint 服务与样本数据读取；生产 ingestion 镜像已包含全部采样依赖（已实测导入通过）。
5. 真正需要决策的是**数据治理与成本**：开启样本数据意味着 ingestion 会实际读取消息/对象/文件内容并写入 OM 库与 ES，且 OM 端 `VIEW_SAMPLE_DATA` 权限者可查看。

## 2. OM 2.0.4 对这四类源的实际能力

| 能力 | Kafka（topic） | HTTP（apiCollection/apiEndpoint） | SFTP（directory/file） | S3/MinIO（container） |
| --- | --- | --- | --- | --- |
| 元数据抽取 | 有 | 有（需 `openApiSpecUrl`） | 有 | 有 |
| Profiler 指标 | **无** | **无** | **无** | **无** |
| 样本数据 | 有 | **无** | 有（CSV/TSV 行） | 有（仅结构化容器） |
| Schema / 数据模型 | 消息 schema | 请求/响应 schema | 文件 columns | 容器 `dataModel`（manifest 驱动） |
| 自动分类（PII 打标） | **2.0.x 新增** | 无 | 无 | **2.0.x 新增** |
| 数据质量（DQ） | 无 | 无 | 无 | 无 |
| 血缘 | 无（仅 Pub/Sub 有） | 无 | 无 | 无 |

### 2.1 没有 profiler 的证据

- 连接规格由 `ServiceSpec` 声明，profiler 需要 `profiler_class`：`ingestion/src/metadata/utils/service_spec/service_spec.py:36-66`，缺失时 `import_profiler_class` 抛 `ValueError`（`:148-152`）。
- Kafka spec 只有 metadata + connection + sampler：`ingestion/src/metadata/ingestion/source/messaging/kafka/service_spec.py:6-10`；S3：`.../storage/s3/service_spec.py:6`；REST：`.../api/rest/service_spec.py:5`；SFTP：`.../drive/sftp/service_spec.py:19`。
- `ProfilerInterface` 的实体类型固定为 `Table`：`ingestion/src/metadata/profiler/interface/profiler_interface.py:63-76`。
- 服务端与 UI 双重拦截：`openmetadata-service/.../createAndRunIngestionPipeline/CreateIngestionPipelineImpl.java:219-224`（显式拒绝 MESSAGING + PROFILER）、`:346-355`（storage 拒绝 profiler）；UI `openmetadata-ui/.../utils/IngestionConfigUtils.ts:160-183` 对 STORAGE/MESSAGING 把类型覆盖为仅 `AutoClassification`。
- `supportsProfiler` 在 Kafka/S3 schema 中存在（`kafkaConnection.json:114-117`、`s3Connection.json:57-60`），但 UI 统一隐藏：`ServiceUISchema.constant.ts:31`（`'ui:widget': 'hidden'`）。

### 2.2 数据质量与血缘（负向结论）

- DQ 仅支持 TABLE/COLUMN：`openmetadata-spec/.../tests/testDefinition.json:44-52` 的 `entityType` 枚举、`TestSuiteRepository.java:344`。
- 血缘：`messaging_service.py:108-118` 的 `yield_topic_lineage` 只有 Pub/Sub 实现；storage/drive/api 目录下无任何 `AddLineageRequest`。OpenLineage 明确无法把 S3/HDFS 路径解析成实体（`.../openlineage/models.py:150-157`）。

## 3. 与 1.12.10 的真实差异

按 `git diff codex/openmetadata-extensions-1.12.10..codex/openmetadata-extensions-2.0.4` 核对：

**新增（2.0.x 才有）**

- `messagingServiceAutoClassificationPipeline.json`（新增文件）：`storeSampleData`（默认 false）、`sampleDataCount`（默认 50）、`enableAutoClassification`、`confidence`、`classificationLanguage`、`topicFilterPattern`。→ Kafka 主题样本数据 + PII 打标。
- `storageServiceAutoClassificationPipeline.json`（新增文件）：同构字段，作用于结构化容器。→ S3/MinIO 容器样本数据 + PII 打标。
- 采样框架扩展到 messaging/storage：`ingestion/src/metadata/sampler/messaging/`、`sampler/storage/` 为新增目录（1.12.10 只有 nosql/pandas/sqlalchemy），并新增 `sampler/entity_adapters.py`。
- 结构化容器 manifest 增强（`containerMetadataConfig.json`、`manifestMetadataConfig.json`）：`dataPath` 支持 glob（`*`/`**`/`?`）、新增 `unstructuredData`（非表格文件按容器登记）、`autoPartitionDetection`（Hive 风格分区）、`excludePaths`/`excludePatterns`（默认排除 `_delta_log`/`_SUCCESS` 等）、`partitionColumns` 可显式声明 name/dataType；`storageServiceMetadataPipeline.json` 新增 `defaultManifest`。

**不是新增（1.12.10 已有）**

- SFTP `extractSampleData`：1.12.10 的 `sftpConnection.json:125` 与 `sftp/metadata.py:513,771` 已存在，2.0.4 只是重构。
- Kafka 元数据 pipeline 的 `generateSampleData`：1.12.10 的 `messagingServiceMetadataPipeline.json` 与 `common_broker_source.py:77-87` 已存在。
- HTTP/REST：连接 schema 未变，无样本数据/DQ/profiler；2.0.4 的 537 行改动是解析重构（tags/paths 收集、容错），非新能力。
- FTP：两个版本都没有 OM 适配器。

## 4. SeaTunnel Web 现状（改造前基线）

- **适配器层**：非数据库适配器统一继承 `AbstractNonDatabaseMetadataConnectorAdapter`，三个 profiler 重载直接抛 `UnsupportedOperationException`（`.../metadata/adapter/AbstractNonDatabaseMetadataConnectorAdapter.java:34-52`）。`MetadataConnectorAdapter.supportsProfiler()` 默认 `serviceCategory()==DATABASE`（`MetadataConnectorAdapter.java:19-21`），唯一消费者是 `MetadataSourceReconciler.reconcileActive:115-128`。→ 这些源目前只建 service + metadata pipeline。
- **探查/拓扑/盘点均为数据库白名单**：`DataExplorationService.context` 硬编码 9 种 DbType（`DataExplorationService.java:851-887`）；`DataSourceTopologyService.supported:269-275`、`DataInventoryService.isSupported:586-592` 同名单。
- **前端**：`OM_PROFILER_DB_TYPES`/`supportsOmProfiler`（`src/pages/data-exploration/shared.ts:130-153`）；非数据库走 `GenericDataExplorationDrawer`，数据来自 SeaTunnel 插件目录（`/api/v1/data-source/catalog/*`），**不是 OM**；`GENERIC_EXPLORATION_DB_TYPES` 含 KAFKA/ELASTICSEARCH/HTTP/MINIO/S3/FTP/SFTP。
- **OM 客户端是数据库中心接口**：`OpenMetadataClient` 只暴露 Database/Schema/Table/profile/service/pipeline，没有任何 topic/container/directory/file/apiCollection 读取。
- **待修小缺陷**：`MetadataPipelineOperationService.reserveExploration:202-221` 没有能力校验，非数据库源触发探查会异步失败（`OM_PIPELINE_TRIGGER_ERROR`）而不是返回明确错误；前端"探查结果"入口对所有类型无条件显示。

## 5. 可增加能力的逐项评估

依赖前置：生产 ingestion 镜像已实测具备 `fastavro`、`confluent_kafka`、`pyarrow`、`pandas`、`boto3`、`paramiko`、`presidio_analyzer`、`sklearn`、`avro`（官方镜像默认 `INGESTION_DEPENDENCY=all`，自定义镜像 `FROM docker.getcollate.io/openmetadata/ingestion:2.0.4`）。

### 候选 A：在 Web 中读取 OM 非数据库实体（主题/容器/目录/文件/API）——推荐

- 可行性：SDK 2.0.4 提供 `TopicService`/`ContainerService`/`DirectoryService`/`FileService`/`APICollectionService`/`APIEndpointService`；spec POJO 带 `Topic.getSampleData()`、`Container.getDataModel()/getSampleData()`、`File.getColumns()/getSampleData()`；REST 侧 `GET /v1/topics/{id}/sampleData`、`GET /v1/containers/{id}/sampleData` 亦存在。
- 改造点：`OpenMetadataClient` 增加实体读取方法 → 新增 VO/控制器端点 → 前端在通用探查抽屉里增加"OM 元数据/样本数据"页签（保留现有插件目录视图）。
- 工作量：中。风险低（纯读）。
- 价值：把 OM 已抽取的主题 schema、容器数据模型、文件列、样本数据暴露到 Web，而不必进 OM UI。

### 候选 B：Kafka 主题样本数据 —— 推荐优先

- 两条路径：① 元数据 pipeline 置 `generateSampleData=true`（1.12.10/2.0.4 均支持，改动最小）；② 新建 AutoClassification pipeline 并置 `storeSampleData=true`（2.0.x 新路径，可同时产出 PII 标签）。
- 改造点：`KafkaMetadataConnectorAdapter` 增加 pipeline 配置项 + 开关；配合候选 A 展示。
- 风险：ingestion 会实际消费消息（只读），并把最多 `sampleDataCount`（默认 50）条消息内容写入 OM 库与 ES；OM 中 `VIEW_SAMPLE_DATA` 权限者可查看。需数据治理确认。

### 候选 C：S3/MinIO 结构化容器数据模型 + 样本数据 —— 可行性中，需 UI 设计

- 容器列（`dataModel`）来自 manifest：需在 metadata pipeline 提供 `storageMetadataConfigSource`（全局清单）或依赖桶内 `openmetadata.json`；2.0.4 新增 `defaultManifest`。当前 Web 的 S3 适配器不写这些字段。
- 样本数据需 AutoClassification pipeline（仅结构化容器、且容器已有 columns）。
- 改造点：`AbstractS3CompatibleMetadataConnectorAdapter` 支持清单配置 + 前端提供 dataPath/structureFormat/分区/排除项的录入界面。
- 风险：manifest 配置对用户有学习成本；采样会下载对象文件。MinIO 即 S3 + `endpointUrl`，无独立类型。

### 候选 D：SFTP 文件列与样本数据 —— 改动最小

- 只需在 `SftpMetadataConnectorAdapter` 的服务连接里写 `extractSampleData: true`，文件实体即带 `columns` 与 `sampleData`，再经候选 A 展示。
- 风险：默认关闭是有原因的（性能，会下载文件）；OM UI 自身没有文件样本数据页签，展示完全依赖 Web。

### 候选 E：AutoClassification（PII）流水线 —— 可做，独立决策

- Kafka/S3 在 2.0.4 支持新增的 `autoClassification` pipeline 类型。Web 目前只协调 metadata/profiler 两类 pipeline，需要扩展协调器与状态展示。
- Presidio 在 ingestion 容器内本地运行（无外部出网），但 PII 标签会写入 OM 并可能触发下游治理动作。

### 候选 F：HTTP/REST —— 无内容可加

OM 侧没有样本数据、没有 profiler、没有 DQ，schema 也只有 OpenAPI 的请求/响应定义。Web 现有的插件目录视图已是更合适的展示面。建议明确"不支持"，与 FTP 同样处理。

### 候选 G：数据质量 —— 不可行

OM 2.0.4 的 DQ 限定 TABLE/COLUMN，主题/容器/文件/API 无测试定义与执行路径。

## 6. 建议

1. 先做候选 B（Kafka 样本数据）+ 候选 A（OM 实体读取），形成"非数据库源也能在 Web 看到 OM 侧元数据与样本"的最小闭环。
2. 候选 D 随 A 一起做（适配器加一个连接字段）。
3. 候选 C 需要先定 manifest 的录入形态，属于独立迭代。
4. 候选 E 与数据治理策略绑定，单独评估。
5. 候选 F/G 在文档与 UI 上明确标注不支持，避免把 `supportsProfiler` 误读成 profiler。
6. 顺手修 `reserveExploration` 缺少能力校验的问题，让非数据库源触发探查返回明确错误。

## 6.1 实施结果（2026-10-08）

已实现候选 A、B、D 与第 6 条修复，候选 C、E、F、G 未实现。

**候选 A —— OM 非数据库资产读取**

- 适配器新增 `resourceTypes()`：Kafka→topic、Elasticsearch→searchIndex、HTTP→apiCollection+apiEndpoint、S3/MinIO→container、SFTP→directory+file；FTP 无适配器，返回 `CONNECTOR_NOT_SUPPORTED`。
- `OpenMetadataClient` 新增 `listResourcesPage` / `getResourceDetail`，由官方 SDK 2.0.4 读取身份、schema 与样本数据；样本字段只在详情读取，列表不取样本，避免大列表携带负载。
- 详情读取在缺少 `VIEW_SAMPLE_DATA` 权限时自动降级为"仅 schema"，而不是整体失败。
- 接口：`GET /api/v1/data-source/{id}/om-resources`、`GET /api/v1/data-source/{id}/om-resources/{resourceId}?resourceType=`。
- 前端：非数据库探查抽屉新增 "OpenMetadata" 页签，按名称匹配当前选中资源，展示 schema、样本行/主题消息与标签；未匹配时显示"未收录"并列出 OM 已有资源。

**候选 B/D —— 样本数据开关**

- 开关落在绑定表 `t_seatunnel_web_metadata_binding.sample_data_enabled`，默认 0；迁移为 `V1_0_37__add_metadata_sample_data_flag.sql`。
- 决策一（治理）：默认关闭，按数据源显式开启；只有 `supportsSampleData()` 为 true 的适配器（Kafka、SFTP）会生效，其余类型即使置位也无效。
- 决策二（成本）：不提供全局开启。Kafka 走元数据 pipeline 的 `generateSampleData`，OM 自身把采样限制为每主题 10 次 poll、总时长 10 秒；SFTP 走连接级 `extractSampleData`（OM 默认关闭的原因正是会下载文件内容），两者都不做批量或全量采样。
- 接口：`POST /api/v1/data-source/{id}/sample-data?enabled=`；开关状态经 `metadata-status` 的 `sampleDataSupported` / `sampleDataEnabled` 暴露给前端。
- 开关保存会递增 `config_version` 并置 PENDING，由协调器重写 service/pipeline；由此可能触发一次该数据源的元数据扫描，这属于既有自动扫描语义。

**第 6 条修复**

`MetadataPipelineOperationService.reserveExploration` 与 `triggerExploration` 现在先校验适配器的 profiler 能力，非关系型数据源返回明确的请求错误，而不是异步失败。

**未实现部分与原因**

- 候选 C（S3/MinIO 结构化容器）：需要先确定 manifest（`dataPath` / `structureFormat` / 分区 / 排除项）的录入形态，且采样会下载对象文件；作为独立迭代。
- 候选 E（AutoClassification PII）：涉及 PII 标签写入 OM 后的下游治理动作，单独评估。
- 候选 F/G：HTTP 在 OM 侧没有样本数据/profiler/DQ 可加；DQ 在 2.0.4 仅支持 TABLE/COLUMN。两者在实现中明确返回不支持。

**验证**

- 单元测试：metadata 包 130 项通过（含新增的适配器、协调器、客户端映射与探查守卫用例）；前端 service 测试 14 项通过，`tsc` 无错误。
- 实机验证（OM 2.0.4、独立 schema、真实数据源）：Kafka/MinIO/Elasticsearch/HTTP 的资源列表与详情读取成功，FTP 正确返回不支持；浏览器完成 Kafka 与 SFTP 两条抽屉路径，OM 页签渲染 schema/样本区域与开关。
- 开关链路实测：置位后 OM 中该 Kafka 元数据 pipeline 的 `sourceConfig.config.generateSampleData` 变为 `true`，扫描 SUCCESS。该测试主题在采样窗口内没有产出样本消息，因此页面按设计显示"暂无样本数据"。
- 期间修复：无 schema 的 API 端点在列表映射时把 null 字段数拆箱导致 NPE，已改为按 0 计。

**迁移版本协调**

样本数据开关的迁移最初使用 1.0.36，但共享开发库 `seatunnel_web_dev_20260906` 已被另一并行工作流应用了同版本的 `V1_0_36__add_metadata_exploration_run_baseline.sql`，导致 Flyway 校验失败。按"禁止重复版本"的约束，本迁移改号为 **1.0.37**。验证在独立克隆 schema 中进行，未向共享开发库写入该列，也未修改其历史表。若其他工作流继续新增迁移，需在 1.0.37 之后取号。



## 7. 落地前必须确认的前置条件

- 数据治理：样本数据内容落 OM 库/ES 的合规性；`VIEW_SAMPLE_DATA` 权限范围；Kafka 采样对生产主题的影响（消费者组、流量）。
- 采样成本：S3/SFTP 采样会下载文件；对超大对象需要先定上限与排除规则。
- 版本契约：`assertFixedVersion()` 要求 2.0.4；本地开发环境仍连 1.12.10，不能用它验收新能力。
- 验收：任何新增的 Web 可操作行为，须按项目约定用 Chrome/Playwright 完成受影响 happy path 验收；OM 侧能力需在生产 2.0.4 上实测，不能仅凭 staging 推断。
