# Sprint 0 Connector Matrix（OpenMetadata 1.12.10）

审计日期：2026-08-25 至 2026-08-26。矩阵只记录真实 OM 资源或明确缺失；`PASS` 不会因 connector 枚举存在而推断。按用户 2026-08-26 的范围确认，本轮硬 Gate 为 MySQL、PostgreSQL、Doris；Oracle 与 Dameng/Kingbase 记为 `DEFERRED`，不能伪造通过，也不阻塞 Sprint 1。

## 结果定义

- `PASS`：有当前 1.12.10 Server/ingestion 环境的真实请求响应证据。
- `UNVERIFIED`：已有部分资源，但当前证据不能覆盖该列的完整闭环。
- `BLOCKED`：环境中没有可用 source/凭据，无法安全执行真实 smoke。
- `DEFERRED`：当前 1.12.10 环境不是一等受支持 connector，按本轮范围延后；不得用 Postgres 或 CustomDatabase 冒充。
- `NOT RUN`：Sprint 0 不改变 SeaTunnel Web 业务代码，该项需要后续 SeaTunnel 集成测试。

## 矩阵

| DB | Connect | Metadata Service | Database | Schema | Table | Column | Constraints | Table Profile | Column Profile | Top20 | Gate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MySQL | PASS（既有 `test-sxp`） | PASS（新 smoke） | PASS | PASS | PASS | PASS | PASS（`ods_user.id` PK） | PASS（`rowCount=3`） | PASS（4 列 profile） | NOT RUN | **PASS** |
| PostgreSQL | PASS（既有 `32室_arm_82.157.22.233_postgres`） | PASS（新 smoke） | PASS | PASS | PASS | PASS | UNVERIFIED（本轮不作为最小 Gate 项） | PASS（`personnel`） | PASS（8 列 profile） | NOT RUN | **PASS** |
| Oracle | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | NOT RUN | **DEFERRED** |
| Doris | PASS（既有 `doris_test`） | PASS（新 smoke） | PASS | PASS | PASS | PASS | UNVERIFIED（本轮不作为最小 Gate 项） | PASS（`metric_data`） | PASS（6 列 profile） | NOT RUN | **PASS** |
| Dameng | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | NOT RUN | **DEFERRED** |
| KingbaseES | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | DEFERRED | NOT RUN | **DEFERRED** |

### MySQL 证据

当前 OM service：`test-sxp`，service type `Mysql`。本次使用 `SMOKE_PREFIX=codex_sprint0_mysql_20260825b` 执行了 1.12.10 OM smoke：metadata 与 profiler 均经历 `queued → running → success`；`test-sxp.default.test.ods_user` 返回 4 列、`tableConstraints`/列约束中 `id=PRIMARY_KEY`，`GET /tables/{fqn}/tableProfile/latest?includeColumnProfile=true` 返回 `rowCount=3` 和 4 个 column profile。完成后的 `kill` 返回 400（无活动 run）并被脚本如实记录，临时 pipeline 已清理。

可复核命令（JWT 仅从执行环境提供，不写入命令、仓库或报告）：

```bash
export OM_BASE_URL=http://127.0.0.1:8585/api
# Provide OM_TOKEN out-of-band; do not record its value.
SMOKE_EXISTING_SERVICE_FQN=test-sxp \
SMOKE_PREFIX=codex_sprint0_mysql_20260825b \
SMOKE_DATABASE_FQN=test-sxp.default \
SMOKE_TABLE_FQN=test-sxp.default.test.ods_user \
SMOKE_PROFILER_TABLE_FILTER='^ods_user$' \
SMOKE_RUN_PIPELINES=1 SMOKE_WAIT_SECONDS=300 SMOKE_POLL_SECONDS=5 \
tools/openmetadata/smoke-test.sh
```

MySQL 的 Metadata + Profiler + Profile read 核心链路为 `PASS`。通用生命周期随后在同一 1.12.10 部署上完成真实验证：MySQL 临时表 `codex_sprint0_mark_same_pipeline_20260825` 源端删除后 OM 返回 `deleted=true`；Doris 专用运行验证了 queued 状态 kill 和 hard-delete 404。Top20 属于后续 SeaTunnel 集成测试，不是 Sprint 0 OM Gate 项。

### PostgreSQL 证据

当前 OM service：`32室_arm_82.157.22.233_postgres`，service type `Postgres`。首次自动抽样命中了 information_schema，未计为通过；随后以 `SMOKE_PREFIX=codex_sprint0_postgres_business_20260825` 和业务表 `"32室_arm_82.157.22.233_postgres".mydb.public.personnel` 重跑，metadata 为 `queued → running → success`，profiler 为 `queued → success`，latest Profile 返回表级 profile 与 8 个 column profile，两个临时 pipeline 硬删除后 GET 均为 404。因此 PostgreSQL Gate 为 `PASS`。

可复核命令（JWT 仅从执行环境提供）：

```bash
export OM_BASE_URL=http://127.0.0.1:8585/api
# Provide OM_TOKEN out-of-band; do not record its value.
SMOKE_EXISTING_SERVICE_FQN='32室_arm_82.157.22.233_postgres' \
SMOKE_PREFIX=codex_sprint0_postgres_business_20260825 \
SMOKE_DATABASE_FQN='"32室_arm_82.157.22.233_postgres".mydb' \
SMOKE_TABLE_FQN='"32室_arm_82.157.22.233_postgres".mydb.public.personnel' \
SMOKE_PROFILER_TABLE_FILTER='^personnel$' \
SMOKE_RUN_PIPELINES=1 SMOKE_WAIT_SECONDS=300 SMOKE_POLL_SECONDS=5 \
tools/openmetadata/smoke-test.sh
```

### Doris 证据与通用生命周期

复用用户已配置的 `doris_test`（service type `Doris`），`codex_sprint0_doris_final_20260825` 完成 Metadata/Profiler/Profile read：`doris_test.ods.ods.metric_data` 返回表级 profile 与 6 个 column profile。专用临时表完成“同一 Metadata pipeline 发现 → 源端删除 → 重扫”，OM 返回 `deleted=true`；专用 kill 模式在状态 `queued` 时调用 OM `kill/{id}` 获得 HTTP 200，随后 status 列表无活动 run。普通模式对两个临时 pipeline hard-delete 后 GET 均返回 404。整个过程仅调用 OM REST，既有 `doris_test` 未删除。

Oracle 按用户 2026-08-26 的决定与 Dameng、Kingbase 一起延后，记为 `DEFERRED`，不阻塞 Sprint 1。

### Dameng / Kingbase 延后证据

- `dameng_local` 当前 service type 为 `CustomDatabase`，已有 metadata pipeline 成功，但没有可读的 profiler 结果；镜像中的自定义代码没有受控 Dockerfile/lock/provenance。
- `KingBaseES_local` 当前 service type 为 `Postgres`，其一条 metadata pipeline 的最近状态为 `failed`；“兼容 PostgreSQL”不等价于 Kingbase 一等 connector。

因此 Oracle、Dameng、Kingbase 不在本轮三库 Gate 中，不计入通过数；后续实现时必须基于 1.12.10 精确扩展点、driver/dialect 锁定和独立 Metadata/Profiler 测试。

## Gate 判定

当前范围的 **Sprint 0 Gate 已通过**：MySQL、PostgreSQL、Doris 均有真实 Metadata + Profiler + latest Table/Column Profile 证据；同一部署还验证了 markDeleted、queued/running kill 语义和 hard-delete 404。Oracle、Dameng、Kingbase 已明确 `DEFERRED`，不得把延期写成已支持。可以进入 Sprint 1；这不代表延期数据库或 Top20 已完成。
