# Sprint 2 文件级改造计划：OpenMetadata Integration + Reconciler

本计划只对应设计第 11–15、19、32、57、58 节与 DD-012、DD-013、DD-014、DD-021、DD-022。

## 固定边界

- 只访问 OpenMetadata Server 的 `/api/v1/...`；不访问 Airflow `:8082` 或 Airflow REST API。
- OpenMetadata Server 固定为 `1.12.10`，运行 ingestion / managed patch 固定为已验证的 `1.12.10.0`。
- 初始 Adapter 仅覆盖 `MYSQL`、`POSTGRE_SQL`、`DORIS`；Oracle、Dameng、Kingbase 不会被错误登记为支持。
- 不创建第二套数据源主表，也不落 OpenMetadata 数据库、表、字段、Profile 明细。
- 外部 HTTP 统一具备连接与读取超时，日志和异常不记录 JWT、密码、完整连接 JSON 或 JDBC secret。

## 先测后写

| 测试文件 | 覆盖 |
| --- | --- |
| `.../metadata/OpenMetadataRunStatusMapperTest.java` | `queued/success/failed/running/partialSuccess/stopped` 映射 |
| `.../metadata/adapter/MetadataConnectorRegistryTest.java` | 三库适配、延期库拒绝、1.12.10 request 形状 |
| `.../metadata/MetadataSourceReconcilerTest.java` | ACTIVE 成功、版本合并、可恢复错误/退避、DELETED/404 幂等、多实例 claim |
| `.../metadata/client/OpenMetadataRestClientTest.java` | base URL 校验、HTTP timeout/认证、精确 `/api/v1` 路径与无 body trigger/deploy |

## 计划修改/新增

| 路径 | 责任 |
| --- | --- |
| `seatunnel-web-api/.../metadata/client/OpenMetadataRestClient.java` | 严格 1.12.10 REST 封装，服务/管道 lookup、upsert、deploy、delete |
| `seatunnel-web-api/.../metadata/OpenMetadataProperties.java` 与 `.../config/OpenMetadataConfig.java` | `metadata.openmetadata` 配置、timeout、禁用安全默认值 |
| `seatunnel-web-api/.../metadata/adapter/*` | DbType → 1.12.10 DatabaseService/metadata/profiler DTO 适配和白名单注册表 |
| `seatunnel-web-api/.../metadata/MetadataSourceReconciler.java` | desired-state 状态机、配置版本合并、退避、同步状态和 OM IDs/FQNs 回写 |
| `seatunnel-web-api/.../metadata/MetadataReconcileScheduler.java` | 20s 调度，单次批量处理；实际抢占由 DAO 条件更新完成 |
| `seatunnel-web-dao/.../MetadataBindingDao*.java` | 可抢占候选、版本条件 claim、成功/错误/删除状态持久化 |
| `seatunnel-web-api/src/main/resources/application.yml` | 环境变量化 OM base URL/JWT 与版本锁；默认关闭执行，避免未配置环境外呼 |
| `docs/openmetadata/sprint-2-test-report.md` | 真实 OM 1.12.10 回归与单元测试证据 |

## 完成标准

- 新/改 DataSource 的既有本地事务只产生 `PENDING`，不会在 HTTP 请求内外呼 OM；
- Reconciler 可按稳定名 adopt/创建 DatabaseService、Metadata Pipeline、Profiler Pipeline，随后 deploy；
- 失败记录脱敏错误、按 1/5/15/30 分钟退避，最新配置版本合并；
- 删除以 `DELETED/DELETING` 协调，OM 404 视为成功；只有 OM 资源清理完成才删除 Binding；
- 只允许三库进入同步，其他 DbType 明确得到 `CONNECTOR_NOT_SUPPORTED`；
- 单元测试、编译、与当前 1.12.10 环境的隔离回归通过后再进入 Sprint 3。
