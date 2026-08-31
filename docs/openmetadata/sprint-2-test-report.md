# Sprint 2 测试报告：OpenMetadata Integration + Reconciler

日期：2026-08-26  
分支：`codex/openmetadata-data-exploration-mvp`

## 范围与版本闸门

- OpenMetadata Server：`1.12.10`（revision `3bc20e698abf222742908c2aa5d0eaa736e7cfbd`）
- `openmetadata-ingestion`：`1.12.10.0`
- `openmetadata-managed-apis`：`1.12.10.0`
- 仅允许 `MYSQL`、`POSTGRE_SQL`、`DORIS` 通过 Adapter；Oracle、Dameng、Kingbase 以 `CONNECTOR_NOT_SUPPORTED` 明确拒绝。
- 客户端只允许 OpenMetadata Server `/api/v1`，拒绝 `:8082` 与 `/airflow`；没有直接 Airflow 调用。

## 自动化验证

| 命令 | 结果 |
| --- | --- |
| `tools/openmetadata/verify-version.sh` | PASS：Server 1.12.10；ingestion/managed APIs 1.12.10.0 |
| `./mvnw -q -pl seatunnel-web-api -am ... test` | PASS：17 tests，0 failures，0 errors |
| `./mvnw -q -pl seatunnel-web-dao -am ... test` | PASS：4 tests，0 failures，0 errors |
| `git diff --check` | PASS |

API 测试覆盖固定版本、精确 `/api/v1` 路径、无 body deploy、错误 ingestion patch 拒绝、三种连接器、延期库拒绝、版本化 reconcile、退避和稳定名删除兜底。DAO 测试覆盖既有绑定和数据源 DAO 的回归。

## 实现结论

- 新建/更新本地数据源仅写入 Binding desired state；外部请求由后台 reconciler 在事务外执行。
- 通过条件更新的 `version + sync_status + update_time` 实现跨节点 lease/claim；没有新增第二套数据源表。
- 成功后写回 OM DatabaseService、metadata pipeline、profiler pipeline 的 ID/FQN 并 deploy；本 Sprint 不 trigger，因此不会自动启动 ingestion。
- 删除先将本地数据源标为 `REVOKED` 并保留；仅 OM 清理成功后删除 Binding 与本地数据源。缺失 OM ID 时按稳定 FQN adopt 后清理，404 视为成功。
- 未交付新的 Flyway migration。曾在隔离库验证 lease 专用字段，但 `metadata_source_binding` 已接近行大小上限；该临时 migration 与临时库均已移除，最终复用现有乐观版本和状态字段实现 lease。

## 未进入的下一 Sprint

Sprint 3 才处理 ingestion/profile 的自动 trigger、OM PipelineStatus 回写、最近运行记录和前端运行状态展示。本 Sprint 保持默认 `metadata.openmetadata.enabled=false`，部署前需显式配置 `/api` base URL 和 JWT。
