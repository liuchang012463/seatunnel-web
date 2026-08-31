# Sprint 3 文件级改造计划：触发、状态同步与数据源管理展示

本 Sprint 对应设计第 14、16–21、32–35、40 节。范围只覆盖现有 DataSource 的自动扫描/手工扫描/手工探查控制面和状态展示；不进入扫描结果树、Profile 明细、数据清查或导出。

## 固定边界

- 只调用 OpenMetadata Server `1.12.10` 的 `/api/v1/services/ingestionPipelines/...`；禁止 SeaTunnel Web 直接访问 Airflow。
- `trigger/{id}`、`kill/{id}` 无 body；状态只读 `/{pipelineFqn}/pipelineStatus`。
- Runtime 仍要求 Server `1.12.10` 与 ingestion/managed APIs `1.12.10.0`。
- 仅 MySQL、PostgreSQL、Doris 能进入同步/触发；Oracle、Dameng、Kingbase 维持 `CONNECTOR_NOT_SUPPORTED`。
- 不新建 DataSource 主表或 Metadata/Table/Profile 明细镜像表；`t_seatunnel_web_metadata_binding` 只缓存控制面和最新状态。

## 先测后写

| 测试文件 | 覆盖 |
| --- | --- |
| `metadata/client/OpenMetadataRestClientTest` | 1.12.10 trigger、kill、pipelineStatus 精确路径/无 body/响应解析 |
| `metadata/MetadataStatusSynchronizerTest` | running/success/failed/不可达映射，metadata 自动触发与版本合并、同源互斥 |
| `metadata/MetadataPipelineOperationServiceTest` | scan/explore 参数校验、READY 限制、running 冲突、service FQN 归属、profiler filter 更新 |
| `service/impl/DataSourceServiceMasterDataTest` | DataSource 列表批量回填 Binding 缓存状态 |
| `data-source/...test.tsx` | 数据源卡片状态标签、扫描/探查按钮与删除中禁用 |

## 计划修改/新增

| 路径 | 责任 |
| --- | --- |
| `metadata/client/OpenMetadataClient.java`, `OpenMetadataRestClient.java`, `OpenMetadataPipelineRun.java` | 1.12.10 trigger/kill/运行记录只读边界 |
| `metadata/MetadataStatusSynchronizer.java`, `MetadataStatusScheduler.java` | 10/60 秒本地缓存刷新、UNKNOWN 保护、版本驱动自动 Scan |
| `metadata/MetadataPipelineOperationService.java` | 现有 DataSource 的手工 scan/explore/retry；Profiler filter 更新后 deploy/trigger |
| `metadata/adapter/*`, `MetadataSourceReconciler.java` | 生成按 datasourceId 错峰的每日 metadata cron；删除前通过 OM kill 正在运行的 pipeline |
| `MetadataBindingDao*.java` | 状态刷新候选及批量 Binding 查询，复用 version 条件更新而非新表 |
| `DataSourceMetadata*VO/DTO`, `DataSourceController.java`, `DataSourceServiceImpl.java` | 状态、运行记录和操作 REST；列表避免 N+1 回填最新缓存 |
| `seatunnel-web-ui/src/pages/data-source/*` | 保持现有卡片视觉，展示自动扫描/探查状态并提供对应操作 |
| `docs/openmetadata/sprint-3-test-report.md` | 版本、API 契约和测试证据 |

## 验收点

- 新建/修改已同步数据源会按 `metadataTriggeredVersion < syncedConfigVersion` 自动触发一次 Metadata Scan；每日 cron 落在 01:00–04:00，Profiler 无周期运行。
- 同一数据源 scan/profile 的 QUEUED/RUNNING 互斥；手工入口返回现有统一业务错误，不以 Airflow 原始状态暴露给前端。
- OM 不可达时当前运行状态设为 `UNKNOWN`，保留最后成功时间和缓存，不误判失败。
- 删除通过 OpenMetadata kill/delete，由 OM 清理 orchestrator；本应用没有 Airflow HTTP client。
- 运行历史只从 OM 读取最近 N 条，不把完整历史复制进 SeaTunnel DB。
