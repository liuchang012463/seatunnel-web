# Sprint 7 验收记录：Dameng/Kingbase CustomDatabase 闭环

日期：2026-08-27  
分支：`codex/openmetadata-data-exploration-mvp`

## 固定版本与边界

- OpenMetadata Server 与 Java SDK：`1.12.10`。
- `openmetadata-ingestion` / `openmetadata-managed-apis`：`1.12.10.0`，仍固定在
  `1.12.10.x` 版本线；未升级到 `1.13.x`。
- SeaTunnel Web 的 OpenMetadata 交互继续使用官方 Java SDK；SDK 未暴露的
  1.12.10 管道控制能力使用 SDK 自带网络客户端，不直连 Airflow。
- 探查仍是用户触发的一次性操作，不新增定时 Profile 调度。

## CustomDatabase 扩展

`openmetadata-ingestion-extension/customdatabase` 实现 1.12.10 官方
`ServiceSpec`、`BaseConnection` 和 source 工厂扩展点，并由
`/mnt/lc/open_metadata/docker-compose.yml` 只读挂载到 ingestion 容器。容器内完成：

```text
BaseSpec.get_for_source(Database, "customdatabase")
```

解析成功；Dameng 与 Kingbase 的 SQLAlchemy engine 分别在独立进程中建立成功。

## 真实环境结果

| 数据源 | Metadata Scan | Profiler/Profile | 备注 |
| --- | --- | --- | --- |
| Dameng | SUCCESS | SUCCESS | `TEST.STUDENT` 返回真实表级/列级 Profile；最新运行无 warning |
| Kingbase | SUCCESS | SUCCESS | `public.equipment_sync` 返回真实表级/列级 Profile；最新运行无 warning |
| Oracle | 保留 Metadata | DEFERRED | 按当前范围暂不支持 Profiler，未将失败伪装成成功 |

Kingbase 使用 SSH 隧道将远端 `54321` 转发到本机 `25432`，ingestion 容器与 API
容器均可达。由于现有 Kingbase 数据源记录仍被任务引用，未直接改写其原有连接参数，
避免破坏既有任务；OM Metadata Connector 通过部署隧道端点完成探查。未保存的隧道连接
测试返回成功。

## 自动化回归

| 检查 | 结果 |
| --- | --- |
| `./mvnw test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false`（JDK 21） | 39 个 Maven 模块 BUILD SUCCESS |
| Metadata 定向测试（`MetadataPipelineOperationServiceTest`、`MetadataConnectorRegistryTest`） | 12 tests，0 failures |
| `yarn run tsc` | PASS |
| `npm test -- --runInBand` | 21 suites，90 tests，0 failures |
| `/api/v1/metadata-integration/health` | `version=1.12.10`、`ingestionVersion=1.12.10.0`、`versionCompatible=true` |
| Dameng/Kingbase `metadata-status` 与最近运行记录 | Scan/Profile 均 SUCCESS |

凭据仅保存在权限为 `600` 的运行时 `.env` 中，未写入仓库或测试报告。
