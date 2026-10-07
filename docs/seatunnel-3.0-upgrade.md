# SeaTunnel 2.3.13 → 3.0.0 升级记录

日期：2026-10-07 ｜ 执行分支：`feat/seatunnel-3.0-engine-support`

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

## 六、AI CLI 试点（P5）

- 3.0.0 镜像内含 `bin/seatunnel-ai.sh` + `cli/`（Python ≥3.10），但引擎容器自带 Python 3.9、宿主仅 3.7，均不满足要求
- 已部署 sidecar 方案：CLI 提取至 `/mnt/lc/seatunnel-300/ai-cli/`（含持久 .venv），经 `/mnt/lc/seatunnel-300/run-ai-cli.sh`（python:3.12-slim，已加入 seatunnel-300 网络）运行；CLI 启动、--help、无 key 优雅报错均验证通过
- MiniMax 环境变量配置指导（写入 `~/.bashrc`，不进仓库/.env）：

  ```bash
  export OPENAI_API_KEY=<MiniMax Subscription Key>       # 必填
  export OPENAI_BASE_URL=https://api.minimaxi.com/v1     # 国际站 https://api.minimax.io/v1
  export OPENAI_MODEL=MiniMax-M2
  ```

  （CLI 的 openai provider 读取 `OPENAI_API_KEY`/`OPENAI_BASE_URL`/`OPENAI_MODEL`/`OPENAI_SMALL_FAST_MODEL`，已在 llm_provider.py 确认）
- 待用户配置 key 后执行验收：自然语言生成 fake→console 配置 → `/check` → `/run` 提交到 3.0 引擎
- 已知限制：sidecar 内无 SeaTunnel 发行版，引擎级 dry-run 校验不可用（CLI 仅做 LLM 侧校验）；若 MiniMax 与 CLI 的 chat-completions 用法不兼容，记录结论不阻塞升级

## 七、遗留事项

- HOCON 生成器审计：现有 `docs/hocon-source-sink-audit-2026-09-25.md` 按 2.3.13 对齐；切换后需按 3.0 文档重做（P2 已验证 env/job.mode/parallelism/checkpoint.interval/plugin_input/plugin_output 不变）
- ArangoDB 相关任务迁移：待自维护补丁适配 3.0 后再评估
- 3.0 日志默认模式差异（混合模式 vs 按任务分文件）已通过 log4j2 配置修复，若后续新增引擎节点需沿用本目录 config
