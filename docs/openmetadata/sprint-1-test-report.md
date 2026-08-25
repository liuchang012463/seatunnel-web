# Sprint 1 验证记录：主数据与本地 Binding

日期：2026-08-26

## 范围

- 单位 → 业务系统 → 数据源的唯一归属模型；
- `t_seatunnel_web_metadata_binding` 本地控制面；
- MySQL、PostgreSQL、Doris 为当前允许纳管的数据源范围；
- 不包含 OpenMetadata HTTP 客户端、Reconciler、Ingestion Pipeline 创建或 Airflow 调用。

## 结果

| 检查 | 命令/方式 | 结果 |
| --- | --- | --- |
| API 业务规则 | `./mvnw -pl seatunnel-web-api -am -DskipTests=false -Dtest=DataSourceServiceMasterDataTest,MetadataBindingCommandServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`（JDK 21） | 5/5 通过 |
| DAO 查询与 Binding DAO | `./mvnw -pl seatunnel-web-dao -am -DskipTests=false -Dtest=DataSourceDaoImplTest,MetadataBindingDaoImplTest -Dsurefire.failIfNoSpecifiedTests=false test`（JDK 21） | 4/4 通过 |
| 前端定向单测 | `npm test -- --runInBand src/pages/data-source/service.test.ts src/pages/data-source/utils.test.ts` | 5/5 通过 |
| 前端类型检查 | `npm run tsc` | 通过 |
| V1.0.15 SQL | MySQL 8.0.39 一次性库 `codex_sprint1_migration_check` | 通过，验证后已删除临时库 |

## SQL 验证要点

- 相同历史 `data_source_unit` 仅导入一条单位主数据；
- 不为历史数据伪造业务系统，`business_system_id` 保持 `NULL`；
- `t_seatunnel_web_business_system` 与 `t_seatunnel_web_metadata_binding` 建表成功；
- Binding 的唯一键与调度索引存在。

## 约束复核

- OpenMetadata Server 与受验证的 ingestion/managed patch 仍固定为 `1.12.10` / `1.12.10.0`；
- 本 Sprint 没有 OpenMetadata REST 请求，也没有 Airflow URL、API 或任务调用；
- Oracle、Dameng、Kingbase 未被新增为可纳管范围；
- 删除数据源时 Binding 保留并转为 `DELETED/PENDING`，等待后续 Reconciler 安全处理外部资源。
