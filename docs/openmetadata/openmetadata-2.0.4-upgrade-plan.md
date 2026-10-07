# OpenMetadata 2.0.4 升级计划

更新日期：2026-10-08

## 版本调研

截至更新日期，OpenMetadata 官方 GitHub 将 2.0.4-release 标记为 Latest，发布日期为 2026-10-06。升级目标固定为 2.0.4，不使用浮动的 latest 标签。

- [官方 2.0.4 release](https://github.com/open-metadata/OpenMetadata/releases/tag/2.0.4-release)
- [官方 Docker Compose 部署文件](https://raw.githubusercontent.com/open-metadata/OpenMetadata/2.0.4-release/docker/docker-compose-quickstart/docker-compose.yml)
- [官方升级说明](https://docs.open-metadata.org/v2.0.x/deployment/upgrade)
- [2.0.4 官方 Java SDK 版本](https://raw.githubusercontent.com/open-metadata/OpenMetadata/2.0.4-release/openmetadata-sdk/pom.xml)

2.0.4 ingestion 使用 Python 3.12；官方依赖将 SQLAlchemy 限定在 2.1 以下。自定义 ingestion image 必须跟随 2.0.4/Python 3.12 构建，不能复用 1.12.10/Python 3.10 bundle。

## 现状与目标

- 当前 OM Server、数据库和 ingestion 为 1.12.10，Compose 目录是 /mnt/lc/open_metadata。该栈保持原样并在线供回退使用。
- 当前 SeaTunnel Web 容器使用的旧 OM 地址为 http://192.168.100.95:8585/api。已从生产 Web API 容器验证新端口 18585 可达并返回 HTTP 200。
- 新 OM 目录为 /mnt/lc/open_metadata_2_0_4，使用独立 Compose 项目、容器、数据库和数据卷。新 API 地址固定为 http://192.168.100.95:18585/api；ingestion 为 18082，MySQL 为 13316，Elasticsearch 为 19200/19300。回退地址固定为 http://192.168.100.95:8585/api。
- 新 OM 的 Server、数据库、Elasticsearch 和 ingestion 使用固定的 2.0.4 镜像版本；ingestion 使用包含 Dameng、Kingbase、Vastbase 扩展的自定义 image，当前本地 image digest 为 e9f30c2240df891f279e8e402f9afd3e12e5dd782db1b43df421599c3faed0e9。
- OpenMetadata fork 位于 /home/haruka/workspace/OpenMetadata，新扩展分支 codex/openmetadata-extensions-2.0.4 基于官方 2.0.4-release；旧 1.12.10 分支保留。Dameng 与 Kingbase dialect 已修复 SQLAlchemy 2 裸字符串 execute 调用并在自定义 image 中实测。
- SeaTunnel Web 使用官方 OM Java SDK 2.0.4，并迁移到 Web 数据库克隆验证过的版本。Web API 仍从数据库配置表读取 OM 连接配置。
- 不导入旧 OM 的 openmetadata_db、airflow_db、实体、pipeline 或索引；不做 OM 数据库跨版本迁移。新 OM 最初使用空白独立卷；目前其中已有 staging Web 克隆基于生产数据源记录创建的 service/pipeline 与成功扫描实体，来源不是旧 OM。staging 与生产克隆的 datasource ID/FQN 一致，正式切换前停止 staging Web API，防止与生产 Web 并发写入。生产 Web 按原记录重建/复用对应的新 OM FQN。
- Web 数据库切换前备份记录为 18 个 ACTIVE 数据源、18 条 ACTIVE metadata binding、其中 12 条绑定有旧 OM service ID。staging 克隆最初漏掉一条 HTTP 行；已从隔离恢复的原始备份补回，当前克隆与生产备份均为 18 条。生产切换仍须以新鲜备份重新盘点。
- 生产克隆里有一条 id 23121731725024 的数据源行类型为 POSTGRE_SQL，但其连接实际指向 Vastbase。暂保留 SeaTunnel Web 中的 POSTGRE_SQL 类型，避免改变 SeaTunnel 作业连接行为；另用临时 staging-only VASTBASE 数据源验证 Vastbase custom adapter。未收到用户要求改类型前，不修改该生产数据源。

## 执行顺序

1. 维护窗口前完成新版本的 SeaTunnel Web 与 OM image/Compose 准备，校验镜像标签和 digest、Compose 服务、端口、外部网络与数据卷。旧 OM 的 Compose、容器、数据库、Elasticsearch 数据和卷不改动、不删除。

2. 生产切换前重新盘点全部 Web 数据源和 binding 的期望状态，包括 ACTIVE、DELETED、缺少 binding、源类型与 OM IDs；冻结会写入新旧 OM 的 Web 节点、外部任务和人工操作。备份生产 Web MySQL，并保存旧 Web JAR/image、Compose、外部环境配置和旧 OM 地址/token 的受限备份。备份不得提交 Git。HTTP 源 `22645421975136` 原先缺少 `openApiSpecUrl`，新 OM 无法据此创建其 service。已在 staging 克隆验证 `http://82.157.22.233:38000/openapi.json` 返回 HTTP 200 和 JSON，并在 Web 更新接口因“数据源被作业使用”保护而拒绝编辑后，只对隔离 staging 数据库的 `connection_params.openApiSpecUrl` 做了最小字段更新；代码调用路径确认该字段只供 OM HTTP catalog 探查，不参与 SeaTunnel 作业配置。重建后该 binding 为 READY、metadata pipeline 存在且 scan SUCCESS。生产切换时需冻结写入并完成新鲜备份后，再从冻结后的生产库确认该字段原本不存在；若基线已有字段，不得覆盖，需据实重新评估。仅对目标行执行字段更新（逻辑等价于 `JSON_SET(connection_params, '$.openApiSpecUrl', 'http://82.157.22.233:38000/openapi.json')`），断言只命中一行，记录并比对整份 `connection_params` 前后差异；不得改动该源的作业连接参数。

3. 在全新 OM 数据卷上按官方 2.0.4 Compose 初始化。确认 2.0.4 Server 与 ingestion 版本契约、健康检查、日志和搜索 API 正常。修改初始管理员密码，使用专用 ingestion-bot 凭据，不复制旧 OM/Airflow 内容。最终 staging ingestion-bot JWT 于 2026-10-07 08:12:33 UTC 签发；当时早期 staging pipeline 已存在，不能将它描述为“首个 pipeline 创建前生成”。先前同一 bot 的 JWT 因轮换被撤销，早期 pipeline 曾报 Unauthorized；最终 JWT 保存为 Web OM 配置 v5（2026-10-07 16:12:44 本地时间）后显式重建 bindings/pipelines，随后 custom 数据库扫描成功。JWT 的 `exp` 为 null（无到期时间），文件 `/mnt/lc/open_metadata_2_0_4/secrets/seatunnel-web-staging-final.jwt` 权限为 600。当前 staging Web 数据库配置与该文件 token 的 SHA-256 一致，新 OM Bearer API 验证返回 HTTP 200，Airflow DAG import error 为 0。生产切换复用已验证 token，不再为同一 bot 生成新 JWT；轮换会撤销 pipeline 中的旧 token。当前新 OM 已用于隔离 staging 克隆预演，含克隆生产数据源生成的 metadata；正式切换前停止 staging Web API，并在 Airflow 确认没有 QUEUED/RUNNING staging DAG run；若有任务则等其结束或取消并核实终态。确认临时测试 FQN 无残留，避免测试端与生产 Web 并发写入。

4. 每一种切换路径都执行相同的启动隔离门禁，包括端口变化和同 URL 替换：
   - 停止全部生产 Web API 实例，先完成数据库及旧环境配置备份。
   - 新版 API 首次启动前，确保 OM 配置数据库行不存在或为 disabled、Base URL/token 为空；同时清空 Compose 环境中的旧 OM URL/token。不能只清环境变量而让数据库里的旧配置仍生效。
   - Quartz 自动启动关闭。新版 API 在 OM 未配置时启动；确认配置页显示未配置、旧 OM 的 8585 API 没有来自新版 API 的请求，再继续。
   - Web 的 MetadataReconcileScheduler 和 MetadataStatusScheduler 是 Spring 定时任务，不由 Quartz 开关控制；未配置时它们应跳过 OM 请求。保存新 OM 配置后，只允许这些受控的 binding reconcile/status 和 metadata scan 运行。Quartz、外部写入方和人工扫描继续暂停到验收结束。

5. 在新版 Web 配置页保存最终新 OM 地址和最终 JWT，并勾选“此地址对应一个新的 OpenMetadata 实例，重建现有数据源绑定”。生产地址从 8585 改到 18585 会触发 endpoint-change 重建；仍勾选显式选项以便操作记录清楚。若使用同一个 Base URL 替换 OM 实例，该复选框是必选项。重建后 ACTIVE 绑定清旧 OM IDs/FQNs 并排队创建；DELETED 保留删除意图且不可恢复为 ACTIVE；缺少的 binding 由启动 backfill 补齐。

6. 允许受控的 reconcile/status 定时任务完成服务、metadata pipeline 和适用的 profiler pipeline upsert。确认全部 18 条 ACTIVE binding 均达到 READY 并引用新 OM ID；DELETED 仍处于删除状态；新 OM 未创建旧 OM 内容。用户确认本次扫描硬门禁为全部 12 条数据库及 Kafka 共 13 条，必须 SUCCESS；另 5 条非数据库源必须重建 binding，但允许 scan 未通过。Metadata scan 在 binding READY 且 metadataTriggeredVersion 小于 syncedConfigVersion 时会自动触发，因此将目标范围扫描视为切换验收的一部分，不在此期间恢复其他写入方或人工任务。

7. 在 staging Web 克隆和新 OM 上验证 custom 数据库源的只读探查、pipeline 执行、实体发布和搜索。当前最终 token 下的实测为：
   - Dameng 数据源 22831123853216：pipeline SUCCESS，CustomDatabase 82 条、OpenMetadata 83 条，0 errors、0 warnings；table search 命中 76 条。
   - Kingbase 数据源 22642998680672：pipeline SUCCESS，CustomDatabase 28 条、OpenMetadata 29 条，0 errors、0 warnings；table search 命中 13 条。
   - 临时 staging-only VASTBASE 数据源 23121731725026：pipeline SUCCESS，CustomDatabase 4 条、OpenMetadata 5 条，0 errors、0 warnings。扫描后经 Web API 删除该临时数据源；Web 数据库中的数据源和 binding 均为 0 条，table search 命中回到 0。未改动生产克隆中 POSTGRE_SQL 类型的 Vastbase 行。
   - staging 克隆包含 18 条生产 ACTIVE 数据源和 18 条 ACTIVE binding。当前汇总为 15 条 scan SUCCESS、3 条 FAILED、0 条 NEVER；13 条数据库 + Kafka 均为 SUCCESS。全部 18 条 binding 均为 READY，且均有新 OM service ID 和 metadata pipeline ID。实际生产 ID `23121731725024` 的 `POSTGRE_SQL`→Vastbase 行为 `READY / SUCCESS`，因此生产配置实际使用的类型路径已验证。Dameng、Kingbase 和 Vastbase 自定义探查的实体、搜索和结果详见下文。
   - 其余 5 条非数据库源为 2 条 Elasticsearch、2 条 HTTP、1 条 MinIO：当前 2 条 SUCCESS、3 条 FAILED。失败项为 Elasticsearch `22851711421952`、HTTP `22851715128448`、MinIO `22851715198976`，`scan_last_error` 均记录 `PIPELINE_EXECUTION_ERROR`；源 `22851715128448` 的 OpenAPI URL 仍是 `localhost:9527`，但该扫描失败按用户确认的范围可接受。成功项为外部 Elasticsearch `22590960840928` 和外部 HTTP `22645421975136`。`last_sync_error_code=CONNECTOR_NOT_SUPPORTED` 是另一 binding 同步字段，不能用作 scan 失败原因。staging Web JAR 含 Elasticsearch、HTTP、MinIO adapter 类。用户已明确接受这 5 条 scan 可失败；正式切换仍要求它们全部有 READY binding、新 OM service 和 metadata pipeline，不要求为扫描通过而更改其作业连接参数。

8. 完成实体及搜索验收后，人工检查 Web 登录、探查配置页和受影响的数据库探查 happy path。只有目标范围内所有数据源都满足既定验收条件时，才恢复 Quartz、外部任务和人工操作。生产 OM 写入从此只指向 18585；旧 OM 8585 保持在线且未被新版 Web 修改。

9. 切换后观察新 OM Server、MySQL、Elasticsearch、ingestion 与 Web API 健康状态、pipeline 状态和错误率；保留备份和完整部署记录。新 OM 中的元数据不回填旧 OM。

## Token 维护约束

staging 浏览器验证发现：同 URL 保存新 JWT 且不勾选重建，会递增 Web OM 配置版本以刷新 SDK client cache，最初克隆的 17 条 binding 及其 OM service/pipeline IDs 均未改变；但 OpenMetadata 为同一个 bot 生成新 JWT 会撤销旧 JWT，既有 ingestion pipeline 的 `openMetadataServerConnection` 仍保存旧 token，Airflow 随后出现 Unauthorized/DAG import error。最终 JWT 在早期 pipeline 创建之后签发，随后通过 Web 配置 v5 显式 rebuild 更新 bindings/pipelines；这一顺序纠正了先前记录中的时间线错误。当前配置表 token 与受限文件 SHA-256 一致，Bearer API 返回 200，`airflow dags list-import-errors` 检查为 0；截至 2026-10-07 16:52 本地时间，Airflow DAG run 47 SUCCESS / 14 FAILED、task instance 46 SUCCESS / 14 FAILED，QUEUED/RUNNING 均为 0。通过 token 更新后 custom 数据库扫描成功作为 pipeline 使用当前凭据的运行验证。目标新 OM 的最终 token 于 2026-10-07 08:12 UTC 签发且无到期时间；本次复用它，因此没有短期过期风险。该 token 是长期凭据，必须限制访问并纳入后续有计划的轮换流程。

因此，生产正式切换必须在第一次创建 service/pipeline 前生成并保存最终 JWT；staging 中最终 JWT 晚于早期 pipeline 的已知经历是验证中发现并纠正的轮换问题，不作为生产操作顺序。切换期间不轮换 token。未来若要轮换 token，必须先准备能原位更新所有既有 pipeline connection token 的操作并验证成功，单独在 Web 配置页保存 token 不算完成轮换。不要用“新 OM 实例”复选框掩盖 token 生命周期问题。

## 回退

- 保留旧 OM Compose、MySQL 数据目录、Elasticsearch 数据和所有旧卷；回退不删除或覆盖新 OM 数据。
- 切换前保存 Web MySQL 完整备份、旧 Web JAR/image、Compose、外部环境配置以及旧 OM URL/token。备份的初始克隆记录中，OM 配置表没有旧配置行，旧 endpoint/token 由旧 Web 外部环境提供；因此数据库备份不是旧配置凭据的唯一恢复材料。
- 在验收结束并作出继续/回退决定前，不恢复 Quartz、外部写入方或人工操作。若在该窗口内回退，冻结 API 并恢复完整切换前 Web MySQL 备份，再恢复旧 Web JAR/image、Compose 和外部配置指向 http://192.168.100.95:8585/api，然后启动旧 Web。
- 若恢复写入后才发现需回退，先冻结 Web、Quartz、外部写入方和人工操作并保存一份当前完整 Web DB 备份。不能直接覆盖切换前的全库备份：这会丢弃升级后创建或修改的 SeaTunnel Web 数据。对比基线与当前数据库，保留全部升级后业务数据变化；回退时在当前库中清空/禁用新的 OM 配置、恢复旧 OM endpoint/token 外部配置，并恢复基线中已有 binding 的旧 OM IDs/FQNs，保留其他业务表和数据源改动。对 HTTP `22645421975136`，仅当基线确认原字段不存在且当前值仍等于本次写入值时，才移除 `connection_params.openApiSpecUrl`；若基线已有值或切换后被改动，依据基线及业务变化恢复，不得直接删除。核对升级期间新增、修改或删除的数据源后再启动旧 Web；新数据源在旧 OM 中按恢复后的程序路径重新绑定。隔离回退演练已证明当前生产旧 JAR 可以在 V1_0_35 schema 上启动：Flyway 验证 36 项成功，对未来版本 1.0.35 仅警告、未尝试降级，Web health endpoints 返回 200。验证日志保存在 `/mnt/lc/open_metadata_2_0_4/rollback-test/old-web-startup-summary.log`。无法确认业务变化与 OM 绑定均已保留时，不得把全库快照恢复称为安全回退。
- 旧 OM 始终在线、旧 Web 配置有受限备份且旧 OM 未被新版 API访问或写入，因而回退只需恢复 Web 组合。新 OM 生成的实体不会导入旧 OM；若新 OM 收到写入，回退会舍弃这些新实体，应先冻结写入并按业务需要对账。
- 新 OM Compose 和数据卷失败后保留供排查，不删除旧 OM 或新 OM 文件。

## 验收门禁

- OpenMetadata 2.0.4 为官方最新版本；生产 Web 容器可达新 API 18585，旧 API 8585 保持可达供回退。
- 新 OM 使用 Compose 独立部署；数据库/搜索/Airflow 卷最初为空，当前 OM 内只有 staging 克隆扫描生成的预演数据，没有导入旧 OM 的数据库、实体、pipeline 或索引。临时 VASTBASE 测试 FQN 已确认搜索为 0；切换前停止 staging Web API并核对生产 datasource FQN 可复用、无并发写入。
- 新版 Web API 首次启动前，数据库 OM 配置及环境 URL/token 均为空，Quartz 自动启动关闭；在未配置时核实没有访问旧 OM 的请求。
- 生产 Web MySQL 备份、旧 Web/JAR/Compose/外部配置和回退步骤均可用；旧生产 JAR 已在隔离的 V1_0_35 schema 克隆上完成兼容启动演练，health endpoints 返回 200；回退副本及日志保存在 `/mnt/lc/open_metadata_2_0_4/rollback-test`。staging scratch schema 恢复演练通过。
- 同 URL 显式 rebuild 的 Chrome happy path 在最初 17 条克隆数据源上成功；正常 token 保存不重建 bindings，但发现了 pipeline 内嵌 token 的生命周期限制，已按“Token 维护约束”处理，不作为正常升级步骤。补回的第 18 条 HTTP 源最初因缺少 `openApiSpecUrl` 返回 `SOURCE_CONNECTION_ERROR`；staging UI 编辑被作业引用保护拒绝后，在数据库备份基础上只向隔离克隆的 `connection_params` 写入已验证的 OpenAPI spec URL，reconcile 随后建立 READY binding 和 pipeline，scan SUCCESS。最终 JWT 时间线已核准：08:12:33 UTC 签发、16:12:44 本地时间保存为 config v5；当前配置 hash 相同，Bearer API 返回 200，Airflow import errors 为 0。
- Dameng、Kingbase 与临时 VASTBASE custom connector 在最终 token 下均有真实只读扫描成功、0 errors、0 warnings、实体入库和 search 证据；临时 VASTBASE 行清理后无 OM 搜索结果。
- 用户确认 scan 硬门禁为全部数据库及 Kafka 共 13 条必须成功；另 5 条非数据库源允许 scan 失败，但全部 18 条 ACTIVE binding 都必须 READY 并引用新 OM service/pipeline。当前 staging 实测为 18/18 READY 且都有 service/pipeline ID；13/13 数据库及 Kafka scan SUCCESS，另 5 条为 2 SUCCESS、3 FAILED、0 NEVER。HTTP `22645421975136` 的 spec URL 已在 staging 克隆中补齐并 scan SUCCESS。另一 HTTP 源的 `localhost:9527` endpoint 可导致扫描失败，但该 scan 在本次范围内不设为门禁；不得因此改写其作业连接参数。
- 新 OM ingestion 已增加 host-gateway 映射，验证 host.docker.internal 端口连通；TCP 连通性仅为连接诊断，不作为 scan 成功证据。
- Quartz、外部写入方和人工任务只在实体、搜索、custom 扫描及配置页 happy path 均通过后恢复。Web 与 OM 回退作为一个切换单元验收。

## 审核与执行记录

- 用户确认旧 OM 历史数据不必全量迁移，新 OM 可由 SeaTunnel Web 重建当前数据源，并明确接受 13 条数据库 + Kafka 为 scan 硬门禁，5 条非数据库源可 scan 失败但必须重建 binding/pipeline。luna max 因范围未明确曾暂缓生产切换；用户明确范围后，luna max 批准受控切换和上述验收门禁。2026-10-07 已按该范围执行生产切换，结果见下方“正式生产执行结果”。
- 生产 Web MySQL 初始备份保存在 /mnt/lc/open_metadata_2_0_4/backups/seatunnel-web-db-initial-20261007.sql.gz；已校验 gzip。恢复演练将它恢复到隔离 `seatunnel_web_restore_test` schema，核对 18 个 ACTIVE 数据源、18 条 binding、12 条旧 service ID；没有启动 scratch Web API，也未连接或写入旧 OM。
- staging Web Compose 项目独立，端口 UI 39002、MySQL 13317，Quartz 自动启动为 false。首次新版 API 启动前 OM DB 配置为空；未发现 staging API 访问旧 OM。新 OM 健康地址为 18585，旧版 1.12.10 服务和数据卷未变。
- Chrome staging 配置页同 URL 显式 rebuild：OM configVersion v2 到 v3，17 个 ACTIVE binding 重建并最终 READY。普通 token 保存且复选框关闭：configVersion v3 到 v4，17 个 binding 的 service/pipeline IDs 完全不变；随后证实旧 JWT 被撤销、既有 Airflow pipeline 因内嵌旧 JWT 出现 Unauthorized。生成最终 staging JWT 后，在任何 pipeline 更新前保存并显式 rebuild；configVersion 到 v5，binding 重建并收敛 READY。
- 运行自定义数据库扫描时观察到绑定达到 READY 后由 MetadataStatusScheduler/MetadataPipelineOperationService 自动排队扫描；因此 Quartz 关闭并不关闭 Web 的 Spring metadata scheduler。通过最终 token 的 pipeline 检查确认 Dameng、Kingbase 成功。OpenMetadata API 搜索验证 Dameng 76、Kingbase 13、临时 Vastbase 2 个 table 命中。
- 临时 staging-only VASTBASE 数据源扫描后返回 SUCCESS，CustomDatabase 4 条、OpenMetadata 5 条，0 errors、0 warnings。通过 SeaTunnel Web API 删除测试数据源后，Web 数据库中对应 source/binding 均为 0，OpenMetadata table search 对该临时 FQN 返回 0。
- staging 克隆最初少了生产 ACTIVE HTTP 数据源 `22645421975136`。确认原始备份恢复的该行存在后，已先备份 staging DB 到 `/mnt/lc/open_metadata_2_0_4/backups/seatunnel-web-stage-pre-restore-active-http-20261007.sql.gz`，再仅向 staging DB 补入 datasource 行并重启 staging Web API；生产 Web 数据库未改动。补入后总量为 18 条 ACTIVE datasource / 18 条 ACTIVE binding，其中 17 条 READY，新增行 `ERROR / NEVER`，错误码 `SOURCE_CONNECTION_ERROR`，未建 OM pipeline。
- 隔离 staging 数据库在 HTTP 表单 schema 刷新、UI 编辑尝试和 `openApiSpecUrl` 字段修复前备份到 `/mnt/lc/open_metadata_2_0_4/backups/seatunnel-web-stage-pre-http-plugin-schema-refresh-20261007.sql.gz`（gzip 有效、权限 600）。UI 编辑被“数据源被作业使用”保护拒绝；stage-only `JSON_SET` 只写入 OM 探查字段，之后 binding reconcile 成功，未修改生产 Web 数据库。
- 补齐初期 18 条扫描曾为 14 SUCCESS、3 FAILED、1 NEVER；完成 HTTP `openApiSpecUrl` staging-only 修复及 reconcile 后，最新汇总为 15 SUCCESS、3 FAILED、0 NEVER，全部 12 条数据库及 1 条 Kafka 成功，18/18 ACTIVE binding READY 且均有新 OM service/pipeline。实际 Vastbase 生产行 `23121731725024` 保留 `POSTGRE_SQL` 类型，stage 中 `READY / SUCCESS`。5 条非数据库源当前为 2 SUCCESS、3 FAILED；失败项不属于用户指定的 13 条扫描门禁。staging 的 HTTP 字段修复有独立的修复前数据库备份；生产只需在正式切换冻结和备份后对同一 metadata-only JSON 字段做受控修改，并记录差异，作业使用的连接字段保持原样。
- 旧生产 JAR `seatunnel-web-api.jar` SHA-256 为 `a9d2a18524f86284d124b84021645ba7ce104264655d9e419da8a4982b19e14d`，副本保存在 `/mnt/lc/open_metadata_2_0_4/rollback-test/old-web-home`。将当前 staging Web DB 克隆为隔离 `seatunnel_web_rollback_test`，清除该克隆里的 OM endpoint/token 并关闭 Quartz，用旧 JAR 以 `SPRING_FLYWAY_ENABLED=true` 启动；Flyway 验证 36 项，识别 schema 1.0.35 高于旧版本 1.0.34但只给 warning，随后 Web 启动成功，`/`、`/api/v1/system/version` 和 `/healthcheck` 均返回 200。测试容器已停止并移除，敏感 scratch schema 和临时 env 已清理；旧 JAR 副本及不含凭据的启动摘要保留在 `/mnt/lc/open_metadata_2_0_4/rollback-test`。
- 一次直接 docker exec 执行 Airflow CLI 时没有继承 Compose entrypoint 注入的 MySQL URL，因而 CLI 检查了默认本地数据库并报告 migration required；这不是 staging Compose Airflow 数据库的迁移失败。按实际 Compose `AIRFLOW_DB` 显式连接后，`airflow dags list-import-errors` 返回 0。直接查 staging Airflow MySQL 后确认当前 DAG run 为 47 SUCCESS / 14 FAILED，task instance 为 46 SUCCESS / 14 FAILED，QUEUED/RUNNING 均为 0。正式切换前停止 staging Web API 后还须重新检查，确认没有新任务排入；官方 ingestion entrypoint 在启动 scheduler 前已运行 airflow db migrate。

## 正式生产执行结果（2026-10-07）

- 新 OM 以 Compose 项目 `/mnt/lc/open_metadata_2_0_4` 部署，Server / ingestion 契约为 2.0.4 / 2.0.4.0。旧 OM 1.12.10 的 Server、MySQL、Elasticsearch、ingestion 和数据卷仍在线且未删除；旧 OM 数据未迁移。生产 Web 连接现在指向 `http://192.168.100.95:18585/api`。
- 生产 Web MySQL 完整快照 `/mnt/lc/open_metadata_2_0_4/backups/seatunnel-web-production-db-post-stop-20261007-1719.sql.gz` 与补丁前快照 `/mnt/lc/open_metadata_2_0_4/backups/seatunnel-web-db-before-stale-scan-fix-20261007-1747.sql.gz` 均已 gzip 校验；旧 Web/JAR、配置和回退记录仍保留。应用回退快照 `/mnt/lc/open_metadata_2_0_4/backups/seatunnel-web-before-startdate-fix-20261007-1801/seatunnel-web-api-active-runtime-before-fix.jar` SHA-256 为 `8b60db7918c6902cfd6de1fbb7a846322a14b5b7f0d04fa646899a4b0278e266`。切换前旧生产 JAR `a9d2a18524f86284d124b84021645ba7ce104264655d9e419da8a4982b19e14d` 及 V1_0_35 回退兼容演练仍在 `rollback-test` 目录。
- 首轮生产扫描中 MySQL `22416919044448` 长期停留 QUEUED。根因是 Web 将 OM 2.0.x 的 `timestamp`（稳定执行标识）当作实际运行时间；旧 OM 运行记录因此被误判为更新。按官方 2.0.x 语义改为优先使用 `startDate` 排序和比较，新增回归用例。提交 `166b98b`，针对性测试 8 项全部通过，Maven package 成功。运行 JAR 已部署到实际启动路径 `/mnt/lc/open_metadata_2_0_4/seatunnel-web-production/app/libs/seatunnel-web-api.jar`，宿主机和容器 SHA-256 均为 `4af2becf32c9562153efd0b1961a6e9dca34e86625ac9463099e826393e55b3c`。首次误放到未被 entrypoint 使用的 `app/seatunnel-web-1.0.0/libs/` 已纠正；运行脚本实际加载 `/opt/seatunnel-web/libs/seatunnel-web-api.jar`。
- 修复后 MySQL 新扫描 `dd502b0e-e9a8-4e5d-b607-54246bebaf91` 于 18:09:15 启动、18:10:09 完成 SUCCESS；之后其余 12 条数据库/Kafka 源仍为 SUCCESS。生产库只读汇总：18/18 ACTIVE binding 为 READY，18/18 有 OM service ID 和 metadata pipeline ID；数据库 + Kafka 13/13 scan SUCCESS、0 QUEUED/RUNNING。另 5 条非数据库源为 4 SUCCESS、1 FAILED（HTTP `22851715128448`），按用户确认的门禁接受。
- Dameng `22831123853216`、Kingbase `22642998680672` 的生产 OM scans 均为 SUCCESS。Chrome 通过 Web“探查结果”读取到 Dameng `STUDENT` 表及 3 列；Kingbase catalog 返回 9 张表，并能显示一张表的 6 列。Kingbase 卡片上的 SeaTunnel Web 连接测试徽标仍显示“连通异常”，但这与已成功的 OM ingestion scan / catalog 读取是不同状态；不把该徽标误当作 OM scan 失败。
- 生产 Web 配置页 Chrome 验证显示控制面已连接，Server 2.0.4、Ingestion 2.0.4.0，Orchestrator UP、版本兼容。Web API 容器 healthy，`/api/v1/system/version`、新旧 OM API 均返回 HTTP 200。Quartz 恢复开启时 4 个到期五分钟 trigger 被 catch-up，其中一项因实际 Compose 挂载目录缺少 `mysql-connector-java-8.0.29.jar` 失败；该驱动已从本部署应用的 JDBC driver 目录补到真实挂载目录 `/mnt/lc/seatunnel-web-docker-new/seatunnel-web-jdbc-drivers/`，SHA-256 `d4e32d2a6026b5acc00300b73a86c28fb92681ae9629b21048ee67014c911db6` 与原包一致。Catch-up 仍暴露批任务问题：job `22580774258016` 提交时报 `Unable to create a source for identifier 'Jdbc'`；8083 对应的 `seatunnel3_master` 使用 SeaTunnel 3.0.0，且 connector 目录存在 `connector-jdbc-3.0.0.jar`，尚未查明客户端/配置层原因。另两条五分钟任务 `22422326661216`、`22424944663776` 于 18:29/18:30 执行后在 JDBC sink 遇到目标表主键重复。只读历史显示这两条任务在切换前 16:15 至 17:15 已反复失败，确认是既有批任务故障，不是本次 OM 变更导致。按原生产状态，已于 18:29 将 `SPRING_QUARTZ_AUTO_STARTUP` 恢复为 `true`；18:30 运行后任务均结束为 FAILED，QUEUED/RUNNING 为 0，API healthy。未修改批作业定义或目标表。批任务问题需另行处理。另有历史 `lake-lifecycle` trigger 自 2026-08-27 起处于 ERROR，与本次 OM 切换及 MySQL driver 缺失无关，保留待后续单独处理。
- 临时维护用 Nginx UI 代理仅绑定 `127.0.0.1:39003`，验收后已停止并移除；生产 UI/API/DB、staging UI/DB 和两套 OM 保留。诊断时一次 Web 数据源详情响应包含连接参数及密码；该单一 MySQL 凭据应视为已暴露并另行轮换，不在此文档记录其值。另有一次本地环境变量过滤失误，使 SeaTunnel Web datasource master key 暴露；应先设计受控解密/重加密轮换，不能直接替换 key 以免已存连接凭据不可读。
- 另发现本地开发进程 PID 1671259（端口 9540）仍连接本地开发 schema，并访问旧 OM 端口 8585；它不是生产 Web API，本次未直接终止或更改。生产 Web API（端口 9527）使用新 OM 18585。若要清理该开发进程，需由其原始开发启动流程管理。

## Kingbase profiler 修复跟进（2026-10-08）

- 生产 Kingbase profiler 首次复测暴露两项 SQL 兼容问题：采样 modulo 表达式原先生成 `%%`，文本列长度原先生成 `LEN(text)`。`9485e00031` 将采样表达式改为 Kingbase 可执行的 `MOD(...)`，`8b55764682` 将 `LenFn` 编译为 `LENGTH(CAST(... AS TEXT))`；对应回归测试 3/3 通过，隔离 bundle 和新镜像内测试均通过。
- 生产 ingestion 已部署重建镜像 `sha256:beca492a9e92cf3f9c1ec5ffc7fd86590f141e704ec9574d558798eb651199b8`。在 Kingbase `public` schema 的新 exploration run `7d66dca8-bf19-48f7-9b25-79cb0d4b6bab` 于 00:00:26 启动、00:00:51 完成 `SUCCESS`，0 warnings。SeaTunnel Web profile API 返回 `equipment_sync` 表 22 行、9 列和实际列指标；Airflow 没有遗留运行任务。
- 测试使用临时 `public` schema 范围。随后调用 `metadata-sync/reconcile` 恢复默认 profiler pipeline 配置；生产 binding 为 `READY`，`config_version/synced_config_version=7/7`，profile 状态为 `SUCCESS`，pipeline FQN 为 `st_ds_22642998680672.st_ds_22642998680672_profiler`。
- 共享 Chrome 尚未完成生产 UI happy path：`127.0.0.1:39001` 的 Basic Auth 拒绝当前会话。表列表 API 对这 9 张表仍返回 `profileAvailable=false`，但直接 profile API 已返回新 profile；待登录后还需核实页面展示和该列表标记。

## OM 2.0.4 探查兼容性复测（2026-10-08）

- 82 表 MySQL 补测前的生产 Web API 只读快照：18/18 数据源 binding 为 `READY`；12 条数据库绑定均已自动建立 profiler pipeline。Profiler pipeline 未配置定时 schedule，代码将其定义为手动触发，因此“自动建 pipeline”通过，“自动周期执行”不适用。当时 10/12 数据库源有 `SUCCESS` 探查，另 2 条为 `NEVER`：一个 MySQL schema 有 82 张表，另一个 Vastbase 实际源没有可探查表。非数据库源没有 profiler pipeline，`NEVER` 是预期状态。当时所有源无 `QUEUED/RUNNING`。
- 经 SeaTunnel Web `/api/v1/data-source/{id}/explore` 按 schema 范围触发的新增复测均以 `SUCCESS`、0 warnings 结束：Kingbase `de392297-8bc2-454c-9709-3b4a7a39750b`（9 张表）；MySQL `cdf9e7a9-6ece-4aa6-bab7-e7106db47607`（12 张表）、`3a2aa1b9-f087-4eab-92c8-cac1e96d93cf`（22 张表）；PostgreSQL `beb7be35-2829-4b82-a576-b68afa53b3cc`（11 张表）、`6debbe31-8ec1-4df2-9dc8-18c06798420a`（18 张表）、`7b424638-4a4a-4e8f-9f49-bfb1c4cff629`（11 张表）；JDBC `b4e26770-1282-4ed8-b845-3d2e2b96d588`（18 张表）；Doris `ebe138f5-b8c7-4a9d-b0d1-19355daba6a8`（7 张表）。另有既有 Dameng、Oracle 成功记录，以及 Kingbase 既有成功 run `7d66dca8-bf19-48f7-9b25-79cb0d4b6bab`。Vastbase 的 staging-only adapter 复测已在上文记录；生产 Vastbase 源没有 table 结果。
- profile 详情 API 对 Kingbase、MySQL、PostgreSQL、JDBC、Doris 均返回 2.0.4 time-series profile 时间戳及列级指标。示例：Kingbase 22 行/9 列/9 项列指标；MySQL 20 行/7 列/7 项指标；Doris 20 行/6 列/6 项指标。PostgreSQL/JDBC 的抽样表中部分 `rowCount=-1`（PostgreSQL 11 张中 10 张、JDBC 18 张中 17 张），同时列指标存在；这是 OM profiler 返回的未知统计值，不应作为负数展示或纳入总量。
- 82 表 MySQL 补测前的生产 `/api/v1/data-inventory/overview` 快照返回 18 数据源、12 数据库、125 schema、2,072 表、25,870 列、9 个有 profile 的数据库、110 张已探查表、已知行数 1,171、已知体积 2,259,585 字节。聚合逻辑已排除负数行数/体积。
- 发现结果列表的兼容问题：Kingbase `public` 的 9 条 table list 均为 `profileAvailable=false`，但最新 profile detail 已返回 22 行、9 列及列指标。OM 2.0.4 将 profile 存于 `EntityProfile` time-series；普通 table list 不含最新 profile。SeaTunnel Web 本地修复会对当前页缺少 profile 的表通过官方 SDK 并发读取 latest profile（最多 8 路），复用 `OmReadCache`，读取失败时保留列表响应；另把负数 table metrics 映射成未知值，避免 UI 显示 `-1`。`DataExplorationServiceTest` 10 项通过，且使用 `-DskipTests=false` 确认实际执行；本地 API JAR 已构建，SHA-256 `0f430e0d66a16da2cd811185385905ee77054539cadf1e119ac28d65130b71b8`。该 Web API JAR 尚未部署，生产 table list 标记仍待更新后验收。
- 聚合方案评估：OM 2.0.4 的 `GET /v1/entity/profiles/{entityType}` 是隐藏接口，要求 `startTs/endTs`，可选 `profileType`，返回所选时间窗内跨实体的 profile 历史；接口没有分页和 service/schema 过滤，不适合作为全目录“每表最新 profile”汇总。现有 Web 汇总按分页枚举数据库/schema/table，再经官方 SDK 读取 latest profile；并发上限 8、`OmReadCache` 按 service/table 缓存，且只汇总非负已知统计。保留该语义比切换到无分页历史聚合接口更稳妥。
- 共享 Chrome 生产 UI happy path 仍待完成：尝试提供的 OM 用户名/密码组合后，`127.0.0.1:39001` 仍被 Nginx Basic Auth 拒绝。Basic Auth 与 OpenMetadata 应用账号是两层认证；需在共享浏览器中完成代理登录后，验收 profile 指示和详情展示。

### 后续补测（2026-10-08）

- 对 MySQL 数据源 `22416910285280` 的 `st_ds_22416910285280.seatunnel_web.seatunnel_web` schema（82 张表）再次触发探查，状态由 `RUNNING` 正常结束为 `SUCCESS`；开始于 01:24:21，成功于 01:51:47。82 张表均生成了最新 profile。
- 成功后生产状态为 18/18 binding `READY`、11/12 数据库源探查 `SUCCESS`、1 个无表结果的 Vastbase 源为 `NEVER`；6 个非数据库源没有 profiler pipeline，`NEVER` 符合预期，无 `QUEUED/RUNNING`。`data-inventory/overview` 从 110 增至 192 张已探查表、profile 数据库从 9 增至 10；已知行数为 92,729，已知体积为 105,200,257 字节。
- 82 张 MySQL 表列表仍全部显示 `profileAvailable=false`；末表 `t_seatunnel_web_user` 的 latest profile detail 返回 0 行、10 列及 10 项列 profile，说明生产展示缺陷仍待部署 SeaTunnel Web API 修复。生产容器 JAR SHA-256 为 `4af2becf32c9562153efd0b1961a6e9dca34e86625ac9463099e826393e55b3c`，本地修复 JAR SHA-256 为 `0f430e0d66a16da2cd811185385905ee77054539cadf1e119ac28d65130b71b8`。
