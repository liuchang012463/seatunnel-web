# Sprint 0 Gate 测试记录

审计日期：2026-08-25 至 2026-08-26。所有 HTTP 验证只通过 OpenMetadata `http://127.0.0.1:8585/api`，没有向 Airflow `:8082` 发送请求。JWT 只从本地环境/临时命令读取，未写入仓库。

## 运行时与契约

| 命令 | 实际结果 | 判定 |
| --- | --- | --- |
| `tools/openmetadata/verify-version.sh` | HTTP 200，`version=1.12.10`，revision `3bc20e698abf222742908c2aa5d0eaa736e7cfbd`；ingestion/managed 均为 `1.12.10.0` | PASS |
| `docker image inspect openmetadata/server:1.12.10` | digest `sha256:f2fb66b1...dd404c`，release label `1.12.10` | PASS |
| `docker exec openmetadata_ingestion /home/airflow/.local/bin/python ... importlib.metadata.version(...)` | ingestion `1.12.10.0`，managed APIs `1.12.10.0` | VERIFIED（当前三库 Gate） |
| `docker exec openmetadata_server unzip -p ... openmetadata-service-1.12.10.jar assets/swagger.json` | 精确找到 list/get/create/upsert/deploy/trigger/status/kill/delete paths | PASS |
| `docker exec openmetadata_server unzip -p ... openmetadata-spec-1.12.10.jar json/schema/metadataIngestion/databaseServiceMetadataPipeline.json` | 证实 markDeleted schema/database 默认 false、includeViews 默认 true | PASS；Adapter 必须显式覆盖 |
| `docker exec openmetadata_ingestion ... find metadata/ingestion/source/database` | MySQL/Postgres/Oracle/Doris 官方目录；Dameng/Kingbase 仅镜像外加目录 | 证据已记录 |

## 既有 OM 资源抽样

| 数据库 | 命令/资源 | 实际结果 | 阻塞/备注 |
| --- | --- | --- | --- |
| MySQL | `codex_sprint0_mysql_20260825b` 与删除夹具 | `ods_user` 返回 `rowCount=3`、4 个列 profile；临时表删除后 `deleted=true` | PASS |
| PostgreSQL | `codex_sprint0_postgres_business_20260825` | `public.personnel` 返回表级 profile 与 8 个列 profile；临时 pipeline hard-delete 后 404 | PASS |
| Dameng | `GET /v1/services/ingestionPipelines/dameng_local.dameng_local_metadata/pipelineStatus?limit=3` | metadata 最近 `success`；`dameng_local.default.TEST.STUDENT` 无 profile | 自定义 `CustomDatabase`，本轮 DEFERRED |
| KingbaseES | `GET /v1/services/ingestionPipelines/KingBaseES_local.82311.../pipelineStatus?limit=3` | metadata 最近 `failed`；service type 实际为 `Postgres` | 不是一等 connector，本轮 DEFERRED |
| Oracle | 用户范围确认 | 与 Dameng/Kingbase 一起后续实现 | DEFERRED |
| Doris | `doris_test` + `codex_sprint0_doris_final_20260825` | `metric_data` 返回表级 profile 与 6 个列 profile；markDeleted、queued kill、hard-delete 404 均有真实证据 | PASS |

## 脚本验证

本目录新增：

- `tools/openmetadata/verify-version.sh`：校验 Server 与 ingestion/managed 精确版本线；
- `tools/openmetadata/smoke-test.sh`：只调用 OM REST API，覆盖 service create/upsert、pipeline create/upsert、deploy、trigger、status、profile read、markDeleted 断言、kill、pipeline delete、service recursive hard delete；
- `tools/openmetadata/contracts/`：脱敏请求/响应 fixture。

执行方式（真实 smoke 需要验收方提供临时 JWT 和 source connection JSON，不得把 secret 写入仓库）：

```bash
export OM_BASE_URL=http://127.0.0.1:8585/api
# Provide OM_TOKEN out-of-band; do not record its value.
tools/openmetadata/verify-version.sh
SMOKE_SERVICE_TYPE=Mysql \
SMOKE_CONNECTION_FILE=/secure/path/mysql-connection.json \
tools/openmetadata/smoke-test.sh
```

## 实际执行记录

以下命令均不包含 token 值；JWT 由执行环境在进程外提供，未写入仓库。

### 版本校验

```text
tools/openmetadata/verify-version.sh
Server: 1.12.10 (revision=3bc20e698abf222742908c2aa5d0eaa736e7cfbd)
openmetadata-ingestion: 1.12.10.0
openmetadata-managed-apis: 1.12.10.0
Version guard: PASS (Server=1.12.10, ingestion/managed line=1.12.10.x)
```

结果：Server 固定版本与 `1.12.10.x` patch build 校验通过；MySQL/PostgreSQL/Doris 当前范围闭环完成后，`1.12.10.0` 固定为本项目已验证 patch build。自定义 ingestion 镜像缺少可复现构建来源仍是独立部署风险，不允许借机升级版本。

### MySQL 新 smoke

```text
SMOKE_EXISTING_SERVICE_FQN=test-sxp
SMOKE_PREFIX=codex_sprint0_mysql_20260825b
SMOKE_DATABASE_FQN=test-sxp.default
SMOKE_TABLE_FQN=test-sxp.default.test.ods_user
SMOKE_PROFILER_TABLE_FILTER=^ods_user$
SMOKE_RUN_PIPELINES=1 SMOKE_WAIT_SECONDS=300 SMOKE_POLL_SECONDS=5
tools/openmetadata/smoke-test.sh
```

结果：metadata 与 profiler 均为 `queued → running → success`；层级读取成功；latest table profile 返回 table profile 与 4 个 column profiles。随后同一 pipeline 对专用临时表完成发现和源端删除重扫，OM 实际返回 `deleted=true`。脚本已增加专用资源名前缀约束、布尔值规范化和 hard-delete 404 断言。

### PostgreSQL 新 smoke

```text
SMOKE_EXISTING_SERVICE_FQN=32室_arm_82.157.22.233_postgres
SMOKE_PREFIX=codex_sprint0_postgres_business_20260825
SMOKE_DATABASE_FQN='"32室_arm_82.157.22.233_postgres".mydb'
SMOKE_TABLE_FQN='"32室_arm_82.157.22.233_postgres".mydb.public.personnel'
SMOKE_PROFILER_TABLE_FILTER=^personnel$
SMOKE_RUN_PIPELINES=1 SMOKE_WAIT_SECONDS=300 SMOKE_POLL_SECONDS=5
tools/openmetadata/smoke-test.sh
```

首次自动抽样到 information_schema 的运行不计通过。随后指定真实业务表 `"32室_arm_82.157.22.233_postgres".mydb.public.personnel` 重跑，metadata/profiler 均成功，latest Profile 返回表级 profile 与 8 个 column profile，临时 pipeline 删除后 GET 返回 404，因此 PostgreSQL Gate 为 PASS。

### Doris 与生命周期 smoke

`doris_test` 上 `metric_data` 返回表级 profile 与 6 个 column profile。专用临时表以同一 Metadata pipeline 完成发现、源端删除和重扫，OM 返回 `deleted=true`。`SMOKE_KILL_RUNNING=1` 在 Metadata run 为 `queued` 时调用 `kill/{id}` 返回 200，随后 1.12.10 status 列表无活动 run；普通模式 hard-delete 两个 pipeline 后 GET 均为 404。源端临时表已删除，既有 Doris service 未删除。

### 脚本静态检查

```text
bash -n tools/openmetadata/smoke-test.sh tools/openmetadata/verify-version.sh
git diff --check
```

结果：PASS。`smoke-test.sh` 的请求目标只接受 OM `/api`，拒绝 Airflow URL；脚本没有调用 Airflow `:8082`。

### 当前 Gate 状态

- MySQL：PASS。
- PostgreSQL：PASS。
- Doris：PASS。
- Oracle、Dameng、Kingbase：DEFERRED（本轮不阻塞 Sprint 1，不用兼容配置冒充一等 connector）。

因此按用户确认的三库范围，Sprint 0 Gate 为 **PASS**，ingestion/managed APIs 精确固定为已验证的 `1.12.10.0`。延期库仍不得宣称支持。

## 不得伪造的项

1. 不把 `CustomDatabase` Dameng 响应转换成原生 Dameng connector PASS。
2. 不把 `Postgres` Kingbase service 当成 Kingbase connector PASS。
3. 不把 pipeline `success` 当成 Profile 已写入；必须实际读取 latest table/column profile。
4. 不把延期的 Oracle/Dameng/Kingbase 写成已支持。
5. 不把当前三库验证外推为所有 connector 或自定义镜像可复现性已验证。
