# OpenMetadata 1.12.10 API Contract（Sprint 0）

本契约只依据当前运行的 `openmetadata-service-1.12.10.jar`、`openmetadata-spec-1.12.10.jar` 和运行 API 核实。不要从其他版本文档推导路径。

## 取证来源

```text
/opt/openmetadata/libs/openmetadata-service-1.12.10.jar
  assets/swagger.json
  org/openmetadata/service/resources/services/ingestionpipelines/IngestionPipelineResource.class
/opt/openmetadata/libs/openmetadata-spec-1.12.10.jar
  json/schema/api/services/ingestionPipelines/createIngestionPipeline.json
  json/schema/metadataIngestion/workflow.json
  json/schema/metadataIngestion/databaseServiceMetadataPipeline.json
  json/schema/metadataIngestion/databaseServiceProfilerPipeline.json
  json/schema/entity/services/ingestionPipelines/ingestionPipeline.json
```

验证命令（在部署容器中读取 JAR，不访问 Airflow API）：

```bash
docker exec openmetadata_server unzip -p \
  /opt/openmetadata/libs/openmetadata-service-1.12.10.jar assets/swagger.json
docker exec openmetadata_server unzip -p \
  /opt/openmetadata/libs/openmetadata-spec-1.12.10.jar \
  json/schema/metadataIngestion/databaseServiceMetadataPipeline.json
curl -sS http://127.0.0.1:8585/api/v1/system/version
```

API base URL 是部署配置中的 `/api`，因此 JAR OpenAPI 的 `/v1/...` 在 HTTP 上对应 `/api/v1/...`。

## 认证和版本

- 受保护资源使用 `Authorization: Bearer <JWT>`。
- 登录 DTO `LoginRequest` 必填 `email`、`password`；密码必须先 Base64 编码。
- `GET /api/v1/system/version` 返回 `version`、`revision`、`timestamp`；本部署实测 `version=1.12.10`。
- JWT、密码、连接 `authType` 和 JDBC secret 不得写入仓库 fixture、日志或错误响应。

## DatabaseService

| 动作 | 精确路径 | DTO/参数 |
| --- | --- | --- |
| list | `GET /api/v1/services/databaseServices?limit=&include=` | `include` 为 `all|deleted|non-deleted` |
| create | `POST /api/v1/services/databaseServices` | body `CreateDatabaseService` |
| upsert | `PUT /api/v1/services/databaseServices` | body `CreateDatabaseService` |
| get by id | `GET /api/v1/services/databaseServices/{id}` | UUID id |
| delete | `DELETE /api/v1/services/databaseServices/{id}?recursive=true&hardDelete=true` | 两个 query 参数可选，删除 DataSource 时使用 |

`CreateDatabaseService` 必填 `name`、`serviceType`；`connection` 为 `DatabaseConnection`。在 spec JSON 中，`DatabaseConnection` 是 `{ "config": <connection schema> }` 包装，而不是扁平 connection 字段。

1.12.10 `DatabaseService.serviceType` 枚举包含 `Mysql`、`Postgres`、`Oracle`、`Doris`、`CustomDatabase` 等，但枚举存在不等于 Connector Gate 通过。

## IngestionPipeline entity

| 动作 | 精确路径 | 精确行为 |
| --- | --- | --- |
| list | `GET /api/v1/services/ingestionPipelines` | 支持 `service`、`pipelineType`、`limit`、`include` 等 query |
| get by FQN | `GET /api/v1/services/ingestionPipelines/name/{fqn}` | `{fqn}` 必须 URL encode |
| create | `POST /api/v1/services/ingestionPipelines` | body `CreateIngestionPipeline` |
| upsert | `PUT /api/v1/services/ingestionPipelines` | body `CreateIngestionPipeline` |
| deploy | `POST /api/v1/services/ingestionPipelines/deploy/{id}` | 无 request body |
| trigger | `POST /api/v1/services/ingestionPipelines/trigger/{id}` | **无 request body** |
| status/history | `GET /api/v1/services/ingestionPipelines/{fqn}/pipelineStatus?startTs=&endTs=&limit=` | `PipelineStatusList` |
| one status | `GET /api/v1/services/ingestionPipelines/{fqn}/pipelineStatus/{runId}` | `PipelineStatus` |
| kill | `POST /api/v1/services/ingestionPipelines/kill/{id}` | 无 request body |
| delete by id | `DELETE /api/v1/services/ingestionPipelines/{id}?hardDelete=true` | 无 Airflow 直连 |
| delete by FQN | `DELETE /api/v1/services/ingestionPipelines/name/{fqn}?hardDelete=true` | 404 需按幂等删除处理 |
| orchestrator status | `GET /api/v1/services/ingestionPipelines/status` | 由 OM 检查 PipelineServiceClient；SeaTunnel 不调用 Airflow |

特别约束：1.12.10 的 trigger endpoint 没有 `runtimeConfig` body。若要按 Database 限制 Profiler，必须先 PUT 更新 pipeline entity 的 `sourceConfig.config.databaseFilterPattern`，再调用 trigger。

## CreateIngestionPipeline DTO

`CreateIngestionPipeline` 必填字段：

```json
{
  "name": "<stable-name>",
  "service": {
    "id": "<database-service-uuid>",
    "type": "databaseService",
    "name": "<service-name>",
    "fullyQualifiedName": "<service-fqn>"
  },
  "pipelineType": "metadata",
  "sourceConfig": {
    "config": {}
  },
  "airflowConfig": {}
}
```

`sourceConfig` 必须是 `{config: ...}` 包装；`pipelineType` 允许 `metadata` 或 `profiler`。`airflowConfig` 可包含 `concurrency`、`scheduleInterval`、`maxActiveRuns`、`retries`、`retryDelay`、`pipelineCatchup` 等字段。

### Metadata source schema（1.12.10）

`sourceConfig.config` 使用 `DatabaseServiceMetadataPipeline`，关键字段及本项目强制值：

```json
{
  "type": "DatabaseMetadata",
  "markDeletedTables": true,
  "markDeletedSchemas": true,
  "markDeletedDatabases": true,
  "includeTables": true,
  "includeViews": false
}
```

1.12.10 schema 的默认值是 `markDeletedSchemas=false`、`markDeletedDatabases=false`、`includeViews=true`；不能依赖默认值。Schema 还支持 `schemaFilterPattern`、`tableFilterPattern`、`databaseFilterPattern`、`threads` 等字段。

### Profiler source schema（1.12.10）

`sourceConfig.config` 使用 `DatabaseServiceProfilerPipeline`：

```json
{
  "type": "Profiler",
  "databaseFilterPattern": {"includes": ["<database-name>"], "excludes": []},
  "includeViews": false,
  "computeMetrics": true,
  "computeTableMetrics": true,
  "computeColumnMetrics": true,
  "profileSampleType": "PERCENTAGE",
  "profileSample": 100
}
```

过滤字段确实叫 `databaseFilterPattern`；采样字段确实叫 `profileSampleType` 与 `profileSample`。`trigger` 不接受运行时 JSON。

## PipelineStatus DTO

`PipelineStatus.pipelineState` 的 1.12.10 枚举是：

```text
queued | success | failed | running | partialSuccess | stopped
```

`runId`、`startDate`、`timestamp`、`endDate` 为运行信息；`status` 是 `StepSummary[]`。SeaTunnel 后续 mapper 必须显式处理 `partialSuccess` 和 `stopped`，不能把未知字符串直接透传前端。

## Metadata hierarchy and Profile read

| 动作 | 精确路径 |
| --- | --- |
| Database | `GET /api/v1/databases?service={serviceFqn}&limit=` |
| DatabaseSchema | `GET /api/v1/databaseSchemas?database={databaseFqn}&limit=` |
| Table/columns | `GET /api/v1/tables?databaseSchema={schemaFqn}&fields=columns,tableConstraints&include=non-deleted&limit=` |
| latest table + column profile | `GET /api/v1/tables/{tableFqn}/tableProfile/latest?includeColumnProfile=true` |
| table profile history | `GET /api/v1/tables/{tableFqn}/tableProfile?startTs=&endTs=` |
| column profile history | `GET /api/v1/tables/{tableFqn}/columnProfile?startTs=&endTs=` |

Profile latest 的实际 1.12.10 响应是 Table entity，表级 `profile` 和列级 `columns[].profile` 嵌在响应中；不是一个只含 `tableProfile`/`columnProfile` 的自定义包装。SeaTunnel 后续必须在自己的 DTO 中转换，不能把 OM 原始 JSON 透传前端。

## Connector extension points

运行中的 `openmetadata-ingestion 1.12.10.0` 源码确认：

- 数据库动态入口约定为 `metadata.ingestion.source.database.<service_type>.<service_name>.service_spec.ServiceSpec`；
- `BaseSpec` 负责 metadata/profiler/sampler/test/connection class path；
- `DefaultDatabaseSpec` 默认提供 `SQAProfilerInterface`、`SQASampler`、`SQATestSuiteInterface`；
- 官方 MySQL、Postgres、Oracle、Doris 的 `service_spec.py` 均按 1.12.10 运行时实现；Doris 仅显式传 metadata/lineage class；
- 数据库自定义扩展至少需要 `connection.py`、`metadata.py`、`service_spec.py`，并需要 1.12.10 Server side connection schema/generated model/注册和 secrets converter 配套。

当前镜像中的 `/opt/dameng_connector` 和 `/opt/kingbase_connector` 没有可复现源码包/lock；Kingbase 现有服务还声明为 `Postgres`。因此它们在 Sprint 0 矩阵中只能是 `DEFERRED`，不作为通过证据。

## 真实请求与响应 fixture

脱敏模板和实际返回形状位于：

- `tools/openmetadata/contracts/create-database-service.request.json`
- `tools/openmetadata/contracts/create-ingestion-pipeline.metadata.request.json`
- `tools/openmetadata/contracts/create-ingestion-pipeline.profiler.request.json`
- `tools/openmetadata/contracts/pipeline-status.success.response.json`
- `tools/openmetadata/contracts/table-profile-latest.response.json`

这些 fixture 不包含 JWT、数据库密码、JDBC URL 参数或真实业务行。
