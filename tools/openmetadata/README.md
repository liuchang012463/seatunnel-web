# OpenMetadata 1.12.10 工具

这些脚本用于固定版本核验和重复执行 Sprint 0 smoke。它们只调用 OpenMetadata Server 的 `/api/v1/...` REST API；不会访问 Airflow `:8082`、Airflow `/api` 或任何 managed API。OpenMetadata 自己通过其 PipelineServiceClient 控制编排器，这是本设计允许的边界。

## 标准化 CustomDatabase 扩展

当前部署通过 `openmetadata_ingestion` 的只读 bind mount 加载
`customdatabase-om-1.12.10.0`，扩展 Python 包不需要重新构建 ingestion 镜像。
仓库内的可复制源代码位于
`tools/openmetadata/ingestion-extension/customdatabase-om-1.12.10.0`，安装到运行环境使用：

```bash
VASTBASE_JDBC_DRIVER_SOURCE=/mnt/djc/vastbase/Vastbase-G100-2.16_pg_2026062910.jar \
  tools/openmetadata/install-customdatabase-extension.sh
```

脚本默认目标是
`/data/vol_a/lc/open_metadata/extensions/customdatabase-om-1.12.10.0`，也可通过
`OPENMETADATA_EXTENSION_DIR` 指定。它会同步通用 CustomDatabase loader、Vastbase
connector 和厂商 JDBC Jar；容器只需继续使用既有 bind mount 即可，后续更新扩展
代码或驱动重复执行同一脚本，不需要重打镜像。驱动路径也可以在连接配置的
`VASTBASE_JDBC_DRIVER_PATH` 环境变量中覆盖，默认使用 `vastbase_connector/drivers`
下的厂商 Jar。

Vastbase 的 Web 连接配置使用 `jdbc:postgresql://` 协议和
`org.postgresql.Driver` 类名，但驱动文件必须是 Vastbase 发布的
`Vastbase-G100-2.16_pg_2026062910.jar`；不使用 PostgreSQL 原生驱动。

SeaTunnel Engine 的批量 source/sink 也需要在每个 master/worker 的共享
`lib` 目录放置同一个 Vastbase 驱动 Jar。Vastbase 插件本身不声明 PostgreSQL
原生依赖；现有 PostgreSQL 原生 Jar 可以继续为 `POSTGRE_SQL` 数据源服务，不能
作为 Vastbase 的替代驱动。由于厂商驱动兼容 PostgreSQL JDBC 接口，生成的任务
配置仍使用 `org.postgresql.Driver` 和 `jdbc:postgresql://`。

本次测试环境的完整 Web 验收记录见
[`docs/openmetadata/vastbase-acceptance.md`](../../docs/openmetadata/vastbase-acceptance.md)。

## 前置条件

- Server 必须是 `openmetadata/server:1.12.10`，不能使用 `latest`、1.12.11+ 或其他版本。
- `openmetadata-ingestion` 与 `openmetadata-managed-apis` 必须固定为已经验证的 `1.12.10.x` 精确 patch build。本部署已由当前三库 Gate 验证并固定为 `1.12.10.0`。
- `curl`、`python3` 和有效的 OpenMetadata JWT。
- 真实 smoke 需要验收方提供一个临时 source connection JSON。该文件可包含密码，但必须放在仓库外，并在执行后移除。
- 不把 token、密码、真实 JDBC URL query secret、业务数据或完整运行日志提交到仓库。

## 版本检查

```bash
tools/openmetadata/verify-version.sh
```

脚本读取：

1. `GET ${OM_BASE_URL}/v1/system/version`，要求 Server 精确等于 `1.12.10`；
2. 当前 ingestion 容器内 `importlib.metadata` 的 `openmetadata-ingestion` 和 `openmetadata-managed-apis`；
3. 要求两个 Python 包精确等于期望值且属于 `1.12.10.x`。

默认值与当前部署一致：

```bash
OM_BASE_URL=http://127.0.0.1:8585/api
INGESTION_CONTAINER=openmetadata_ingestion
EXPECTED_INGESTION_VERSION=1.12.10.0
EXPECTED_MANAGED_APIS_VERSION=1.12.10.0
```

如果不能使用 Docker，可提供 `ACTUAL_INGESTION_VERSION`、`ACTUAL_MANAGED_APIS_VERSION`，但必须保留独立的运行时证据；脚本不会把缺失值当成通过。

## 真实 Gate smoke

推荐先使用稳定、临时且可清理的 prefix：

```bash
export OM_BASE_URL=http://127.0.0.1:8585/api
# Provide OM_TOKEN out-of-band; never put the JWT value in shell history.
export SMOKE_PREFIX=codex_sprint0_mysql_20260825
export SMOKE_SERVICE_TYPE=Mysql
export SMOKE_CONNECTION_FILE=/secure/openmetadata/mysql-connection.json
tools/openmetadata/smoke-test.sh
```

如果验收库很大，可在 smoke 专用 fixture 中同时指定已由 Metadata 发现的 Database/Table，并用 1.12.10 的 `tableFilterPattern` 缩小 Profiler 工作量；这只是测试加速，不改变产品默认的 Database 粒度：

```bash
export SMOKE_DATABASE_FQN='service.database'
export SMOKE_TABLE_FQN='service.database.schema.table'
export SMOKE_PROFILER_TABLE_FILTER='^table$'
```

`SMOKE_CONNECTION_FILE` 是**连接 config 对象**，不是完整 CreateDatabaseService body。例如 MySQL 的脱敏形状：

```json
{
  "type": "Mysql",
  "scheme": "mysql+pymysql",
  "username": "<username>",
  "authType": {"password": "<secret>"},
  "hostPort": "<host:port>",
  "supportsMetadataExtraction": true,
  "supportsProfiler": true
}
```

Doris、PostgreSQL 使用 OpenMetadata 1.12.10 spec 中对应的 connection schema；Oracle 与 Dameng/Kingbase 当前延期，不要把 Kingbase 连接配置改写为 PostgreSQL 来取得假通过。

脚本步骤：

1. POST/GET/PUT DatabaseService；
2. 通过 PUT/POST 创建或更新 Metadata、Profiler 两个 pipeline；
3. 通过 OM 的 `deploy/{id}`；
4. 通过 OM 的 `trigger/{id}`（无 request body）；
5. 读取 `pipelineStatus`，只接受真实 `success`；
6. 分页读取 Database → DatabaseSchema → Table/Column；
7. 先用 1.12.10 的 `databaseFilterPattern` 更新 Profiler，再 trigger；
8. 读取 `/tables/{fqn}/tableProfile/latest?includeColumnProfile=true`，断言表级和列级 profile 都存在；
9. 可选地用 `SMOKE_ASSERT_MARK_DELETED_FQN` 验证源端已删除对象的 soft-delete；脚本不会替你删除源表；
10. 调用 OM `kill/{id}`，再清理本次 prefix 创建的 pipeline/service，并断言 hard-delete 后 GET 返回 404；如果是在成功等待之后调用，1.12.10 可能返回 400 表示没有活动 run，脚本会如实记录，不把它伪造成 kill 成功。要验证真正的 running kill，在可控测试源设置 `SMOKE_KILL_RUNNING=1`；该专用模式会在首个 Metadata run 处于 queued/running 时 kill，验证它进入 `stopped` 或 1.12.10 的 status 列表已不存在活动 run，然后退出并清理。完整 Metadata/Profile 闭环需用普通模式另跑一次，避免把 kill 后立即重触发的 Airflow 恢复窗口误判为 Connector 失败。

重复执行时脚本按 prefix adopt/upsert；默认 `SMOKE_CLEANUP=1` 只删除本次创建且由 prefix 保护的资源。使用 `SMOKE_CLEANUP=0` 保留资源供人工检查，但不要在共享部署中使用不可识别的名称。

如果只想验证 entity/deploy 路径而不触发真实 source workflow：

```bash
SMOKE_RUN_PIPELINES=0 tools/openmetadata/smoke-test.sh
```

这不构成 Gate 通过证据，因为没有 Metadata/Profiler/Profile read。

## 使用既有 service（不删除 service）

```bash
export SMOKE_EXISTING_SERVICE_FQN='existing-service-fqn'
export SMOKE_PREFIX=codex_sprint0_existing_20260825
tools/openmetadata/smoke-test.sh
```

脚本只清理本次 prefix 的 pipeline，不会删除 `SMOKE_EXISTING_SERVICE_FQN`。

## Fixtures 与契约

`contracts/` 下的 JSON 是 1.12.10 request/response 形状的脱敏模板。尖括号占位符不是可直接提交的生产凭据。精确 endpoint、DTO、默认值和扩展点见：

`docs/openmetadata/openmetadata-1.12.10-api-contract.md`

## Gate 结果原则

当前硬 Gate 为 MySQL、PostgreSQL、Doris，三库必须各自有可复现的真实闭环证据。Oracle、Dameng、Kingbase 按用户范围确认记为 `DEFERRED`，不使用 `CustomDatabase`/`Postgres` 兼容配置冒充通过；延后项不阻塞 Sprint 1，但不代表这些数据库已支持或 MVP 已全部完成。
