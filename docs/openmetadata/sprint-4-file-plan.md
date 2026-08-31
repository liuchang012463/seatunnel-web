# Sprint 4 文件级计划：扫描结果与数据源探查

## 目标

在已经通过的 Sprint 0-3 固定版本与状态机基础上，增加只读的扫描结果/数据源探查门面：

- Database → Schema → Table 的懒加载浏览；
- 表结构、Column 类型与约束展示；
- OpenMetadata 1.12.10 最新成功 Table/Column Profile 展示；
- 仅基于 `NOT_NULL`、`PRIMARY_KEY`、单列 `UNIQUE` 的轻量质量判定；
- 复用既有 `DataSourceCatalogService#getTop20Data` 的 Top20 预览；
- 不复制 Database/Schema/Table/Column/Profile 到 SeaTunnel Web 数据库；
- 不开放 Pipeline 参数编辑，不引入第二套数据源管理，不直接调用 Airflow。

本 Sprint 的可运行数据库范围固定为 MySQL、PostgreSQL、Doris。Oracle、Dameng、Kingbase 继续返回既有 `CONNECTOR_NOT_SUPPORTED`，待后续基于 1.12.10 独立验证后实现。

## 1. OpenMetadata 1.12.10 固定 API

后端只能经 `OpenMetadataClient` 调用下列 1.12.10 Server 路径：

| 用途 | 路径 |
| --- | --- |
| Database 列表 | `GET /api/v1/databases?service={serviceFqn}&include=non-deleted&limit={n}` |
| Database 归属校验 | `GET /api/v1/databases/name/{databaseFqn}` |
| Schema 列表 | `GET /api/v1/databaseSchemas?database={databaseFqn}&limit={n}&include=non-deleted` |
| Table 列表 | `GET /api/v1/tables?databaseSchema={schemaFqn}&fields=columns,tableConstraints&include=non-deleted&limit={n}` |
| Table 详情 | `GET /api/v1/tables/{id}?fields=columns,tableConstraints&include=non-deleted` |
| 最新成功 Profile | `GET /api/v1/tables/{tableFqn}/tableProfile/latest?includeColumnProfile=true` |
| Column Profile 历史读取 | `GET /api/v1/tables/{tableOrColumnFqn}/columnProfile?startTs={start}&endTs={end}` |

这些路径、字段和 `include=non-deleted` 语义来自本地 `/tmp/openmetadata-1.12.10` 源码与已经固定的 API contract；本 Sprint 不参考 1.13.x。

## 2. 文件清单

### 后端 API / SPI

- `seatunnel-web-api/.../metadata/client/OpenMetadataClient.java`
  - 增加 Database/Schema/Table/Profile 读取边界；保留 OM 原始配置读写仅供内部固定版本回归，产品 Controller 不透传。
- `seatunnel-web-api/.../metadata/client/OpenMetadataRestClient.java`
  - 实现上表路径与 1.12.10 JSON 的最小 typed projection；所有请求继续经过固定版本、超时和安全 `/api` URL 校验。
- `seatunnel-web-api/.../metadata/client/OpenMetadata{DatabaseSchema,Table,Column,TableConstraint,TableProfile,ColumnProfile}.java`
  - 仅存临时 OM 响应投影，不落库。
- `seatunnel-web-api/.../metadata/DataExplorationService.java`
  - 校验数据源生命周期、Binding `ACTIVE + READY`、OM Service FQN 与三库范围；执行归属校验；映射结构/Profile；委托既有 Top20 Catalog。
- `seatunnel-web-api/.../metadata/DataExplorationQualityEvaluator.java`、`ExplorationQualityResult.java`
  - 对明确约束和已存在 Profile 做薄判定；缺规则/缺 Profile 不伪造为正常或零值。
- `seatunnel-web-api/.../controller/DataExplorationController.java`
  - 暴露 `/api/v1/data-exploration` 的 Database、Schema、Table、Profile、Preview DTO API；不暴露 OM 原始 JSON 或 Pipeline 编辑。
- `seatunnel-web-spi/.../bean/vo/DataExploration*.java`
  - 新 API DTO；不改变既有 DataSource CRUD/连接测试语义。
- `seatunnel-web-spi/.../enums/ExplorationQualityStatus.java`
  - `NORMAL`、`ABNORMAL`、`NO_RULE`、`NO_PROFILE`。

### 前端

- `seatunnel-web-ui/src/pages/data-source/service.ts`
  - 只调用 SeaTunnel `/api/v1/data-exploration` 后端门面；不持有 OM/Airflow URL。
- `seatunnel-web-ui/src/pages/data-source/types.ts`
  - 增加扫描结果、结构、Profile、Preview 的响应类型。
- `seatunnel-web-ui/src/pages/data-source/components/DataExplorationDrawer.tsx`
  - 复用现有数据源页风格，以 Drawer 懒加载 Database/Schema/Table，并展示结构、探查结果和 Top20。
- `seatunnel-web-ui/src/pages/data-source/components/DataSourceCard.tsx`、`index.tsx`
  - 在既有卡片动作中增加“查看扫描结果”入口；不新增数据源录入页面。

### 测试

- `seatunnel-web-api/src/test/.../OpenMetadataRestClientTest.java`
  - 固定 Database/Schema/Table/Profile 路径、查询参数、无 Airflow 请求、Profile 配置回归。
- `seatunnel-web-api/src/test/.../DataExplorationServiceTest.java`
  - 归属过滤、结构映射、Profile/质量与 Top20 委托、跨 Service 拒绝。
- `seatunnel-web-api/src/test/.../DataExplorationQualityEvaluatorTest.java`
  - 约束/Profile 组合与缺失语义。
- `seatunnel-web-ui/src/pages/data-source/service.test.ts`
  - 验证所有扫描结果请求均经过 SeaTunnel 后端路径。

## 3. 明确留到 Sprint 5+

- OM cursor 全量分页、数据清查聚合与分布统计；本 Sprint 采用后端受限读取，不使用超大 limit。
- 独立拓扑/数据清查页面、短缓存、XLSX 导出。
- Oracle、Dameng、Kingbase 的 Connector/Driver/Profiler 独立 Gate。
- Profile 历史趋势、自定义质量规则、血缘和 Agent/Airflow 配置。
