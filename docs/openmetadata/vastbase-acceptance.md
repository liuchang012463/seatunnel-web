# Vastbase 验收记录

日期：2026-09-23  
分支：`codex/vastbase-support`

## 驱动与版本

- 厂商驱动：`/mnt/djc/vastbase/Vastbase-G100-2.16_pg_2026062910.jar`
- SHA-256：`f1008c31e105127aaa5968bc87290243a6e3d1e91ccde1897e81bd703814accb`
- JDBC URL：`jdbc:postgresql://...`
- 驱动类：`org.postgresql.Driver`
- `seatunnel-web-datasource-vastbase` 不声明 PostgreSQL 原生 JDBC 依赖；原生
  PostgreSQL 驱动仍保留给普通 `POSTGRE_SQL` 数据源。
- OpenMetadata Server：`1.12.10`
- `openmetadata-ingestion` / `openmetadata-managed-apis`：`1.12.10.0`

厂商 Jar 已通过 JDBC 连接 Vastbase、读取 `public.acceptance_file`，并确认
包含 `org/postgresql/Driver.class`。Vastbase 的 Web、Engine 与 OpenMetadata
配置均使用 PostgreSQL 兼容的 URL/类名，但实际加载的是上述厂商 Jar。

## OpenMetadata 扩展

扩展源码位于 `tools/openmetadata/ingestion-extension/`，通过既有
`openmetadata_ingestion` 只读 bind mount 加载。标准安装命令为：

```bash
VASTBASE_JDBC_DRIVER_SOURCE=/mnt/djc/vastbase/Vastbase-G100-2.16_pg_2026062910.jar \
  tools/openmetadata/install-customdatabase-extension.sh
```

它只同步外置扩展包和厂商 Jar，不构建或重启镜像；更新代码或驱动时重复执行
即可。实际部署目录和密码未写入仓库。

## Web 探查验收

数据源：`codex_vastbase_acceptance_20260923`（ID `23125418729440`）

- 连接测试：通过，状态为“连通正常/已启用”。
- Metadata：13 条记录，0 warning，0 error，100%。
- Profiler：21 项处理，0 warning，0 error，100%。
- 结果：`public.acceptance_file` 被发现并完成探查；6 列，174 行，表大小约
  56.0 KB。

## SeaTunnel 批量引接验收

任务：`codex_vastbase_batch_20260923`（定义 ID `23125560820064`，实例 ID
`23125613539552`）

- source：Vastbase `postgres.public.acceptance_file`
- sink：Vastbase `postgres.public.codex_vastbase_acceptance_copy_20260923`
- source/sink 连接测试：通过。
- 表校验、发布、上线、启动：通过。
- Engine 结果：`FINISHED`，读取 174 行，写入 174 行。
- 使用厂商 Jar 复核目标表：目标表 174 行。

## 可复现检查

```bash
tools/openmetadata/verify-version.sh
./mvnw -pl seatunnel-web-datasource-plugins/seatunnel-web-datasource-vastbase -am test
./mvnw -pl seatunnel-web-api -am -DskipTests compile
```

前端生产构建 `yarn build` 已通过。`yarn tsc` 仍报告仓库原有
`src/app.tsx` 的动态 import/history 类型错误，与 Vastbase 新增文件无关。
