# Sprint 5 文件级改造计划：数据清查、拓扑与导出

## 目标

在不新增 Database/Schema/Table/Column 本地镜像、也不改变现有数据源管理入口的前提下，完成 F-04.01 与 F-13.01 的查询侧能力：清查聚合、按需拓扑和规范化 XLSX 导出。所有外部元数据读取仍经 SeaTunnel Web 的 `OpenMetadataClient`，不调用 Airflow 管理 API。

## 固定边界

- OpenMetadata Server 固定 `1.12.10`。
- 当前已验证 ingestion/managed build 固定 `1.12.10.0`，属于 `1.12.10.x`；不切换到 `.1` 或 1.13.x。
- 本轮元数据闭环只包含 MySQL、PostgreSQL、Doris；Doris 已在 OM 测试源 `doris_test` 完成验证。
- Oracle、Dameng、Kingbase 继续 `DEFERRED`，不以兼容配置或本地假数据宣称支持。

## 文件清单与职责

### OpenMetadata client 分页边界

- `seatunnel-web-api/src/main/java/org/apache/seatunnel/web/api/metadata/client/OpenMetadataPage.java`
  - 保存 1.12.10 `paging.total` 与 opaque `paging.after`，拒绝制造 page-number cursor。
- `.../client/OpenMetadataClient.java`
  - 增加 Database/Schema/Table 的 cursor-page 默认兼容方法，保留原有调用者。
- `.../client/OpenMetadataRestClient.java`
  - 使用精确 1.12.10 路径与 `include=non-deleted`，仅回传 typed projection；cursor 只通过 `after` 原样传回。
- `.../metadata/DataExplorationService.java`
  - 扫描结果 facade 的 Database/Schema/Table 读取改为受上限保护的 cursor 分页；保留归属校验与既有 Catalog Top20 委托。
- `.../metadata/MetadataPipelineOperationService.java`
  - 数据源探查选择器的 Database 读取改为 cursor 分页。

### 清查聚合与缓存

- `.../metadata/MetadataInventoryCache.java`
  - 进程内 5 分钟短缓存，仅保存计数/分布快照；不保存元数据明细。
- `.../metadata/DataInventoryService.java`
  - Unit/System/DataSource 来自 SeaTunnel DB；Database/Schema/Table/Column 从 OM cursor 分页聚合。
  - Profile 覆盖率、已探查表和 `knownRowCount` 仅来自最近成功 Profile；单个源不可用时保留本地主数据统计。
  - 导出使用 callback 流式遍历，避免把所有元数据行积压在内存。
- `.../controller/DataInventoryController.java`
  - 提供 `/api/v1/data-inventory/summary`、三类 distribution 和 `/profile-coverage`。

### 拓扑懒加载

- `seatunnel-web-spi/src/main/java/org/apache/seatunnel/web/spi/enums/DataSourceTopologyNodeType.java`
- `.../bean/vo/DataSourceTopologyNodeVO.java`
  - 固定 `UNIT → BUSINESS_SYSTEM → DATA_SOURCE → DATABASE → SCHEMA → TABLE` 节点模型，Column 不进树。
- `.../metadata/DataSourceTopologyService.java`
  - 初始树只读本地 Unit/System/DataSource；展开节点才访问 OM，并按 1.12.10 cursor 逐层读取。
- `.../controller/DataSourceTopologyController.java`
  - 提供 `/api/v1/data-source-topology/tree` 与 `/children`。
- `seatunnel-web-ui/src/pages/data-source/service.ts`、`types.ts`
  - 增加 topology facade 的请求与 typed node。
- `.../components/DataExplorationDrawer.tsx`
  - 在现有扫描结果抽屉中增加按需展开的拓扑树；点击 Database/Schema/Table 复用已有详情区域。

### XLSX 导出

- `seatunnel-web-api/pom.xml`
  - 增加 Apache POI `5.2.5` 与兼容的 `commons-io 2.15.1`，避免运行时 `UnsynchronizedByteArrayOutputStream` 版本冲突。
- `.../metadata/DataExplorationExportService.java`
  - 使用 POI `SXSSFWorkbook(100)`、压缩临时文件和 try-with-resources 流式写四个固定 Sheet：`数据源清查`、`数据表清查`、`字段探查`、`特征统计`。
- `.../controller/DataExplorationExportController.java`
  - `POST /api/v1/data-exploration/export` 返回 XLSX 流，不透传 OM 原始 JSON。
- `seatunnel-web-ui/src/utils/HttpUtils.tsx`
  - 增加 blob POST 下载边界；浏览器仍只访问 SeaTunnel Web。
- `.../components/DataInventoryDashboard.tsx`、`.../pages/data-source/index.tsx`
  - 在现有数据源管理页面内嵌清查看板、刷新和导出按钮，不创建第二套数据源管理。

### 状态缓存失效与测试

- `.../metadata/MetadataStatusSynchronizer.java`
  - 状态成功落库后失效清查快照，避免 Scan/Profile 成功后继续显示旧计数。
- `.../metadata/client/OpenMetadataRestClientTest.java`
  - 覆盖 `paging.after` 编码和 `paging.total` 保留。
- `.../metadata/DataInventoryServiceTest.java`
  - 覆盖主数据 + OM 分页 + Profile 聚合，以及延期 Oracle 不调用 OM。
- `.../metadata/DataSourceTopologyServiceTest.java`
  - 覆盖初始树不读 OM、展开 DataSource 才读取 Database。
- `.../metadata/DataExplorationExportServiceTest.java`
  - 覆盖 SXSSF 输出可打开且四个 Sheet 名称固定。
- `seatunnel-web-ui/src/pages/data-source/service.test.ts`
  - 覆盖 topology facade 请求路径；既有扫描结果 facade 请求继续通过。

## 完成门槛

后端定向测试、前端 TypeScript/Jest 全部通过；分页请求只走 OM `/api`，不出现 `:8082`/Airflow URL；导出不建立明细镜像；Oracle、Dameng、Kingbase 仍明确延期。
