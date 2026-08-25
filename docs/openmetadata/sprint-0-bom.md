# Sprint 0 BOM：OpenMetadata 1.12.10

审计日期：2026-08-25 至 2026-08-26
部署根目录：`/mnt/lc/open_metadata`
验证范围：只检查 OpenMetadata Server、其 ingestion 镜像内的 Python 运行时和 OpenMetadata REST API；本项目脚本不直接访问 Airflow 管理端口。

## 版本锁定

| 组件 | 实际运行值 | 证据 | Gate 状态 |
| --- | --- | --- | --- |
| OpenMetadata Server tag | `openmetadata/server:1.12.10` | `/mnt/lc/open_metadata/docker-compose.yml`、`docker inspect` | PASS |
| OpenMetadata Server digest | `sha256:f2fb66b1ea6420a84c986e1035a55308948807352efe9d87ce510612d0dd404c` | `docker image inspect openmetadata/server:1.12.10` | PASS |
| Server revision | `3bc20e698abf222742908c2aa5d0eaa736e7cfbd` | 镜像 label `org.open-metadata.commit-id` | PASS |
| Runtime Server API | `1.12.10` | `GET /api/v1/system/version` 返回 `{"version":"1.12.10",...}` | PASS |
| Ingestion image tag | `openmetadata/ingestion:1.12.10-kingbase` | compose 与运行容器 | VERIFIED FOR CURRENT GATE；构建可复现性风险见下文 |
| Ingestion image ID | `sha256:1b45e15733ebd266b9a843435ff77c297cb19cbdf7c3ccd95e676cc7e231cbb3` | `docker image inspect` | VERIFIED FOR CURRENT GATE |
| `openmetadata-ingestion` | `1.12.10.0` | 运行容器 `/home/airflow/.local/bin/python` 的 `importlib.metadata` | VERIFIED；固定值 |
| `openmetadata-managed-apis` | `1.12.10.0` | 同上 | VERIFIED；固定值 |
| Airflow | `3.1.5` | 容器环境、`airflow version`、由 OM 转发的 deploy/trigger/status/kill/delete 闭环 | PASS FOR CURRENT GATE |
| Python | `3.10.19` | 运行容器 | RECORDED |
| SQLAlchemy | `1.4.54` | ingestion 运行时 | RECORDED |

`1.12.10.0` 属于允许的 `1.12.10.x` 版本线，并已通过当前 MySQL/PostgreSQL/Doris Gate。本仓库固定该精确 patch build，不会自行切换到 `.1` 或任何 1.13.x 版本。

## 驱动与扩展运行时

以下是当前自定义 ingestion 镜像中实际可见的版本/文件，不代表对应数据源 Gate 已通过：

| 数据库 | 运行时资产 | 结论 |
| --- | --- | --- |
| MySQL | `PyMySQL 1.1.2`、`mysqlclient 2.1.1`、`mysql-connector-python 9.5.0` | 官方 1.12.10 connector 可用，须以 smoke 为准 |
| PostgreSQL | `psycopg2 2.9.12` | 官方 1.12.10 connector 可用，须以 smoke 为准 |
| Oracle | `oracledb 1.4.2`、`cx_Oracle 8.3.0`、`/instantclient` | 用户决定与 Dameng/Kingbase 一起 DEFERRED |
| Doris | `pydoris-custom 1.1.0` | 已用 `doris_test` 完成 Metadata/Profiler/Profile/lifecycle smoke |
| Dameng | `jaydebeapi 1.2.3`、`JPype1 1.7.1`、`/opt/dameng_jdbc/DmJdbcDriver*.jar` | `/opt/dameng_connector` 是镜像外加代码；按本次 Gate 标记 DEFERRED |
| Kingbase | `jaydebeapi 1.2.3`、`/opt/kingbase_jdbc/kingbase8-8.6.0.jar`、`/opt/kingbase_connector` | 当前 OM service 实际声明为 `Postgres`，不是一等 Kingbase connector；按本次 Gate 标记 DEFERRED |

当前自定义扩展文件 SHA-256（用于识别现有镜像，不能代替源码锁定）：

```text
/opt/dameng_connector/dameng_source.py  42bbcd1a07067e23a445164489d72913b091c04b8b5b023d110a866cea415203
/opt/dameng_connector/connection.py      62fa986afca53644e5f0cd156ff91a0eb72f6c754d8426c7f9e76b59a5a909c4
/opt/dameng_connector/dm_dialect.py     1c5ca73e89e3838047f2c4fe4cae05a61dbce228f20def1f82874c4d18eac07d
/opt/kingbase_connector/kingbase_source.py  b2748516003ab35a78085b974de86aa7c99396ff1134f91c0902e682f030cda3
/opt/kingbase_connector/connection.py      ab4e5b392a0a2d0eb31dd229f5cd277e5176fb857475e193c027ede2364d1afd
/opt/kingbase_connector/kingbase_dialect.py 3acd6344e705dee8a006b70ec2c030f321354b2c18007c6b85accdc40a8e9460
```

## 可复现性缺口

当前 ingestion tag 没有 registry digest，`docker image inspect` 的 `RepoDigests` 为空；`/mnt/lc/open_metadata` 没有构建该镜像的 Dockerfile、requirements lock 或源码 provenance。`docker history` 只能证明构建参数包含 `RI_VERSION=1.12.10.0`，不能证明未来可以从同一输入重建镜像。因此版本组合已是当前 Gate 的验证 BOM，但镜像仍不是完整可复现构建；Sprint 1 可以继续，发布前仍需补齐 provenance。

恢复可复现性所需的后续工作：

1. 将自定义扩展源码、JDBC/SQLAlchemy driver 来源和精确版本纳入受控源码目录。
2. 固定基础镜像 digest、Python wheels/digests、OpenMetadata 两个 `1.12.10.0` 包和 Airflow constraint 文件。
3. 生成构建后的镜像 digest，并在部署 compose 中 pin digest；不能改 Server 的 `1.12.10` tag/digest。
4. 保留 MySQL、PostgreSQL、Doris smoke 作为 `1.12.10.0` 固定版本回归；后续启用 Oracle/Dameng/Kingbase 时分别补同等级证据，不改变已固定版本线。

## Gate 解释

按用户 2026-08-26 的范围确认，本轮硬 Gate 只要求 MySQL、PostgreSQL、Doris，三库均已通过。Oracle、Dameng、Kingbase 明确记为 `DEFERRED`；后两者不以 PostgreSQL/CustomDatabase 伪装通过，三者均不阻塞 Sprint 1，也不构成这些数据库已支持的证据。
