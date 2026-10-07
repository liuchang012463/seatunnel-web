# SeaTunnel 2.3.13 → 3.0.0 升级记录

日期：2026-10-07 ｜ 执行分支：`feat/seatunnel-3.0-engine-support`（已快进合入 develop，ef6f64bb → e3bbd7ce）

## 〇、切换结果（P4，2026-10-07 已执行）

- docker 栈整体升级完成：`libs/seatunnel-web-api.jar`、`conf/application.yml`、`web/`（`yarn build` 重新构建，含 index.html）替换；`conf/nginx/`（.htpasswd）保留原样；Flyway 自动迁移 1.0.20 → 1.0.34（14 个，全部成功）；LLM 环境变量（SPRING_AI_*，MiniMax）接入 compose
- 引擎 client（t_seatunnel_web_client id=1）baseUrl：`http://192.168.100.95:8081` → `http://192.168.100.95:8083`；web api 容器每 30s 对 3.0 引擎探活（实测连接确认）；`client_version` 字段待 UI 下次探活后刷新显示
- **端到端验证**：经 web 提交的 MySQL→MySQL 批任务在 3.0 上完成解析、执行、写入全链路（Jdbc source → Jdbc sink generate_sink_sql，INSERT 到达 MySQL）
- 回退资产：`dist/seatunnel-web-1.0.0.bak-20261007131303`、`/mnt/lc/seatunnel-web-docker-new/seatunnel_web-backup-20261007131303.sql`（mysqldump）、2.3.13 引擎栈原样未动
- 注意：仓库 `seatunnel-web-ui/dist` 曾缺 index.html（陈旧残缺产物），本次已用 `yarn build` 重建；后续发布前端务必走完整构建

### 切换中发现的两个定时任务失败（均为切换前既有问题，非 3.0 回归）

1. **PG 任务（jobDefineId=22424944663776，cron 每 5 分钟）**：自 2026-09-23 18:15 起在 2.3.13 上持续失败（2.3.13 引擎日志含 66 处同签名错误），切换前后失败一致。根因：目标 PG 数据源（82.157.22.233:15432/test）建连失败 —— `org.postgresql.util.PSQLException: Protocol error. Session setup failed.`（PostgresCatalog 阶段失败，疑似该服务端 9-23 前后变更了协议/实例）。**需修复数据源侧连通性，与引擎版本无关**。
2. **MySQL 任务（jobDefineId=22580774258016）**：在 3.0 上提交/解析/执行全链路成功，最终 FAILED 是 `Duplicate entry '1' for key 'equipment.PRIMARY'` —— 任务配置 `data_save_mode=APPEND_DATA` 写入已有数据的表，重复执行必撞主键（任务幂等设计问题，2.3.13 重复执行同样会失败）。

### 切换中发现的 3.0 行为差异（非缺陷，已记录）

- 3.0 的 `FactoryException`（API-06）响应与日志均不携带根因 cause；可用 3.0 新增的运行时日志接口 `POST /loggers/{name}?level=TRACE&scope=cluster` 开 DEBUG 后复现取根因（本次即用此法定位 PG 连接问题；该覆盖重启后失效，无需清理）
- 3.0 镜像默认注释 `rootLogger.appenderRef.file.ref = routingAppender`（混合日志模式），需按第一章配置启用按任务分文件（/mnt/lc/seatunnel-300/config 已配置）

## 一、升级决策

| 项 | 决策 |
|---|---|
| 目标版本 | SeaTunnel 3.0.0（2026-09-29 发布，官方镜像含 arm64） |
| 部署方式 | docker-compose 独立栈 `/mnt/lc/seatunnel-300/`，与 2.3.13 并行 |
| 2.3.13 | **全程零改动**（/mnt/lc/seatunnel/arm64），随时可回退 |
| 切换策略 | 并行验证后整体切换（P4，与用户约定窗口执行） |
| ArangoDB connector | 暂留 2.3.13（自维护补丁，不重编译到 3.0） |
| AI CLI | P5 试点，LLM 使用 MiniMax（OpenAI 兼容 provider） |

## 二、3.0.0 独立栈部署

- 镜像：`apache/seatunnel:3.0.0`（arm64，内含 114 个 connector jar 全量 + 3.0 版 lib）
- compose：`/mnt/lc/seatunnel-300/docker-compose.yml`（项目名 seatunnel-300，master + worker1 + worker2）
- 端口：REST v2 宿主 `8083→8080`；Hazelcast 宿主 `5802→5801`（8080/8081/8082 已被 spark/2.3.13/OpenMetadata 占用）
- 配置（以镜像自带为基底）：
  - `hazelcast-{master,worker,client}.yaml`：cluster-name 改 `seatunnel-300`，member-list 改 `master/worker1/worker2:5801`
  - `seatunnel.yaml`：checkpoint storage `fs.defaultFS: file:///opt/seatunnel/checkpoints`、`namespace: /seatunnel/checkpoint_snapshot/`（与 2.3.13 的 /tmp 隔离）；其余键与 2.3.13 完全一致（backup-count/dynamic-slot/http.enable-http/telemetry）
  - `log4j2.properties`：**启用 `rootLogger.appenderRef.file.ref = routingAppender`**（3.0 镜像默认注释该行，改为混合模式，job 日志不再按任务分文件；seatunnel-web 依赖 `/log/job-{id}.log`，必须启用）
- lib 补充：从 2.3.13 lib 拷贝 `kingbase8-8.6.0.jar`、`Vastbase-G100-2.16_pg_2026062910.jar`（3.0 镜像 lib 未内置国产驱动；其余驱动镜像已带，2.3 特有的 seatunnel-hadoop3-uber/hadoop-aws 由 3.0 的 seatunnel-shade-hadoop3-uber-3.1.4-3.0.0.jar / seatunnel-shade-hadoop-aws-3.1.4-3.0.0.jar 取代）

## 三、REST API v2 兼容性验证（P2）

验证环境：3.0.0 集群 `localhost:8083`；对照 2.3.13 集群 `localhost:8081`。

| 端点 | 3.0.0 实测 | 兼容性 |
|---|---|---|
| GET /overview | projectVersion=3.0.0，workers=2 | ✅ 完全兼容 |
| POST /submit-job?format=hocon&jobId&jobName&isStartWithSavePoint | 响应 {jobId,jobName}；批/流/恢复均成功 | ✅ 兼容（3.0 新增 format=sql、restoreMode） |
| POST /submit-job/upload（multipart `config_file`） | 成功 | ✅ 兼容 |
| POST /stop-job {jobId,isStopWithSavePoint} | savepoint 停止返回 SAVEPOINT_DONE（与 2.3.13 行为一致） | ✅ 兼容（3.0 新增 force 参数） |
| GET /running-jobs（无参数） | 返回完整列表，HTTP 200 | ✅ 兼容（3.0 新增可选分页 page/rows） |
| GET /job-info/{jobId} | jobId/jobName/jobStatus/envOptions/jobDag/metrics/errorMsg 均在 | ✅ 兼容（3.0 新增 diagnostics） |
| GET /finished-jobs/{state} | FINISHED/FAILED/CANCELED 可用 | ✅ 兼容 |
| GET /system-monitoring-information | 正常返回节点数组 | ✅ 兼容 |
| GET /log、/log/job-{id}.log | 启用 routingAppender 后正常 | ✅ 兼容（见上文配置修复） |
| GET /metrics、/openmetrics | 空响应/6 字节，与 2.3.13 关闭 Telemetry 时一致 | ✅ 行为一致 |
| GET /jobs/checkpoints/{jobId} | 3.0 返回更丰富（counts/latestCompleted/history） | ✅ 超集，兼容 |
| jobDag.vertexInfoMap[].tablePaths | 存在 | ✅ 兼容 |
| metrics 键名 | SourceReceivedCount/SinkWriteCount/*/QPS/Table* 全部保留（值为超集，新增 FlushSignal* 系列） | ✅ 超集，兼容 |

真实任务验证：

1. FakeSource→Console 批任务：FINISHED，SourceReceivedCount=16 ✅
2. MySQL(Jdbc 192.168.100.95:33306)→Console 批任务：FINISHED，SourceReceivedCount=1 ✅（3.0 connector-jdbc + 既有 mysql-connector-java-8.0.27 驱动）
3. FakeSource→Console 流任务：RUNNING，checkpoint 正常（/jobs/checkpoints 返回 completed）✅
4. 流任务 savepoint 停止 → 同 jobId + isStartWithSavePoint=true 恢复：RUNNING(isStartWithSavePoint=True) ✅

结论：**seatunnel-web 现有 REST 客户端代码无需任何修改即可对接 3.0.0**，唯一代码改动为版本白名单。

## 四、seatunnel-web 侧改动（P3）

- `SeaTunnelClientVersionPolicy`：白名单 `{"2.3.13"}` → `{"2.3.13","3.0.0"}`（提交 a78bae23）
- 新增 `SeaTunnelClientVersionPolicyTest`（4 用例，全部通过）
- 验收（dev 环境，scripts/dev-restart.sh 重启后 Chrome/Playwright 实测）：
  - 引接引擎管理页新增 client `ZETA-3.0-8083`（192.168.100.95:8083）→ 创建成功
  - 详情面板显示 **v3.0.0 / 健康**，核心指标（CPU/堆内存/线程数）经后端 REST 网关正常拉取
  - 原 2.3.13 client 并行存在且仍健康（双引擎共存）
- **docker 栈（/mnt/lc/seatunnel-web-docker-new）dist 替换推迟到 P4 切换窗口执行**：该栈当前运行的是 9月1日 旧构建，其 `conf/application.yml` 为旧版定制（`spring.ai.minimax.*`、kingbase-tunnel 等键），与当前代码的配置键已分叉；单独替换 fat jar 会导致 LLM 配置键不匹配，且新构建首启会对该栈共享库自动执行新 Flyway 迁移。因此 P4 切换时按"conf + jar 同步升级 + 提前备份 + 迁移评审"整体操作。

## 五、切换与回退手册（P4，与用户约定窗口执行）

切换步骤（窗口内执行）：
1. 2.3.13 上运行中的流任务带 savepoint 停止（savepoint 落 2.3.13 的 /tmp/seatunnel/checkpoint_snapshot，不受影响）
2. docker 栈整体升级：备份 `/mnt/lc/seatunnel-web-docker-new/dist/seatunnel-web-1.0.0` → `.bak-<ts>`，替换为新构建 dist（libs/seatunnel-web-api.jar + conf/application.yml 同步为新版，保留 jdbc-drivers/logs/web），重启 seatunnel-web-api；评审新构建 Flyway 迁移对 seatunnel_web 库的影响（均为增量建表/加列）
3. 引擎 client baseUrl 切至 `http://192.168.100.95:8083`（t_seatunnel_web_client.id=1，或 UI 修改）
4. 流任务从 savepoint 恢复（引擎换成 3.0 时以 3.0 的 checkpoint 目录重新起跑；如需完整保留 2.3.13 状态，可在切换前于 2.3.13 完成 savepoint 后按需重跑）
5. 观察任务/指标/日志

回退步骤（任意时刻）：
1. seatunnel-web 引擎 client baseUrl 改回 `http://192.168.100.95:8081`
2. 2.3.13 栈未动，任务从其原有 savepoint/checkpoint 目录恢复
3. （可选）`cd /mnt/lc/seatunnel-300 && docker-compose down` 停止 3.0 栈

## 六、AI CLI 试点（P5，2026-10-07 已执行 ✅）

- 3.0.0 镜像内含 `bin/seatunnel-ai.sh` + `cli/`（Python ≥3.10），但引擎容器自带 Python 3.9、宿主仅 3.7，均不满足要求
- sidecar 方案：CLI 提取至 `/mnt/lc/seatunnel-300/ai-cli/`（含持久 .venv），经 `/mnt/lc/seatunnel-300/run-ai-cli.sh`（python:3.12-slim，已加入 seatunnel-300 网络）运行
- **宿主内核 4.19 无 clone3 系统调用，旧 Docker seccomp 对未知系统调用返回 EPERM，导致新 glibc（python:3.12-slim）容器内无法创建线程**（"can't start new thread"）；已通过 `--security-opt seccomp=unconfined` 解决（run-ai-cli.sh 已内置；已 docker exec 验证引擎容器不受影响）
- 试点结果（AI_PROVIDER=openai + MiniMax-M3，key 来自 repo .env 的 SPRING_AI_API_KEY）：
  - 单次生成模式：自然语言生成 FakeSource→Console 批任务配置，137s 完成，含自动校验-修正循环（自行把 schema 调整为 tables_configs 多表写法）
  - 生成配置经 REST 提交 3.0 引擎：**FINISHED，SourceReceivedCount=40**（20 行 × parallelism 2）✅
- 环境变量约定（写入 `~/.bashrc`，不进仓库/.env）：

  ```bash
  export OPENAI_API_KEY=<MiniMax Subscription Key>       # 必填
  export OPENAI_BASE_URL=https://api.minimaxi.com/v1     # 国际站 https://api.minimax.io/v1（注意 CLI 需带 /v1）
  export OPENAI_MODEL=MiniMax-M3
  ```

  （CLI 的 openai provider 读取 `OPENAI_API_KEY`/`OPENAI_BASE_URL`/`OPENAI_MODEL`/`OPENAI_SMALL_FAST_MODEL`，见 cli/seatunnel_cli/llm_provider.py）
- 已知限制：sidecar 内无 SeaTunnel 发行版，引擎级 dry-run 校验不可用（CLI 仅做 LLM 侧校验，最终以引擎提交结果为准）

## 七、遗留事项

- HOCON 生成器审计：现有 `docs/hocon-source-sink-audit-2026-09-25.md` 按 2.3.13 对齐；切换后需按 3.0 文档重做（P2 已验证 env/job.mode/parallelism/checkpoint.interval/plugin_input/plugin_output 不变）
- ArangoDB 相关任务迁移：待自维护补丁适配 3.0 后再评估
- 3.0 日志默认模式差异（混合模式 vs 按任务分文件）已通过 log4j2 配置修复，若后续新增引擎节点需沿用本目录 config
