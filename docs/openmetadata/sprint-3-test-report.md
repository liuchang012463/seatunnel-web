# Sprint 3 测试报告：自动扫描、探查触发与状态同步

日期：2026-08-26  
分支：`codex/openmetadata-data-exploration-mvp`

## 范围

- 使用 OpenMetadata Server `1.12.10` 的固定 `/api/v1` IngestionPipeline 契约；
- 版本校验、Metadata/Profiler trigger、kill、pipelineStatus 和最近运行记录；
- 新建/修改后的 Metadata 自动触发、每日错峰调度、同源 Scan/Profile 互斥；
- OM 不可达时的 `UNKNOWN` 缓存语义、触发前崩溃窗口恢复；
- 现有 `/api/v1/data-source` 页面中的扫描、探查、运行记录和状态展示；
- Oracle、Dameng、Kingbase 仍为 `DEFERRED`，本 Sprint 不新增伪支持；不调用 Airflow。

## 固定运行时证据

| 项目 | 实际值 |
| --- | --- |
| OpenMetadata Server | `1.12.10`，revision `3bc20e698abf222742908c2aa5d0eaa736e7cfbd` |
| openmetadata-ingestion | `1.12.10.0` |
| openmetadata-managed-apis | `1.12.10.0` |
| OpenMetadata API base | `/api`；客户端拒绝 `:8082` 和 `/airflow` |

## 自动化验证

| 命令 | 结果 |
| --- | --- |
| `./mvnw -pl seatunnel-web-api -am -DskipTests=false -Dtest=OpenMetadataRunStatusMapperTest,MetadataConnectorRegistryTest,MetadataSourceReconcilerTest,OpenMetadataRestClientTest,MetadataStatusSynchronizerTest,MetadataPipelineOperationServiceTest,MetadataBindingCommandServiceImplTest,DataSourceServiceMasterDataTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS：28 tests，0 failures，0 errors |
| `./mvnw -pl seatunnel-web-dao -am -DskipTests=false -Dtest=DataSourceDaoImplTest,MetadataBindingDaoImplTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS：4 tests，0 failures，0 errors |
| `npm run tsc` | PASS |
| `npm test -- --runInBand src/pages/data-source/service.test.ts src/pages/data-source/components/MetadataStatus.test.tsx` | PASS：7 tests，0 failures |
| `git diff --check` | PASS |

## 关键断言

- `trigger/{id}`、`kill/{id}` 使用 `POST` 且无请求体；运行记录只从
  `/v1/services/ingestionPipelines/{pipelineFqn}/pipelineStatus?limit=N` 读取；
- PipelineServiceClient 响应的 HTTP 200 不代表成功，客户端还校验 JSON `code` 为 2xx 和 managed build 为 `1.12.10.0`；
- `PipelineStatus.status[*].warnings` 汇总为 warning 数，不读取不存在的顶层字段；
- Profiler 的 Database FQN 先通过 `/v1/databases/name/{fqn}` 校验，并比较返回的 service FQN；
- Metadata pipeline 明确写入 `markDeletedTables/Schemas/Databases=true`、`includeTables=true`、`includeViews=false`；每日 schedule 在 `01:00`–`04:00`；Profiler 不设置周期；
- 触发前以 Binding 版本条件预留 `metadataTriggeredVersion`，成功触发后保持一次性语义；超过 grace window 且 OM 没有对应 run 时可恢复重试；
- `READY`、`WAITING`、`ERROR`、`DELETING` 均进入状态刷新候选；OM 暂时不可达只将正在运行项标为 `UNKNOWN`，保留最后成功时间；
- 删除由 OM pipeline kill/delete 和 recursive service delete 完成，SeaTunnel Web 没有 Airflow HTTP client；
- 前端只调用 SeaTunnel Web 后端路由，继续复用现有 DataSource 卡片/Modal 风格，不创建第二套数据源页面。

## Sprint 3 结论

Sprint 3 通过。F-13.01 的自动扫描控制面和状态展示已闭环；扫描结果树、表/列 Profile、数据预览 facade、数据清查、拓扑和 XLSX 导出留在后续 Sprint 4–5，未在本 Sprint 混入实现。
