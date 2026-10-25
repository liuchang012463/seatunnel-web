# Kingbase BYTE 字符字段与批量任务状态修复、内网更新计划

日期：2026-10-22。适用版本：SeaTunnel Web 1.0.0、Engine 3.0.0。

## 根因与证据

1. 截图 DDL 为 `CHARACTER VARYING(n BYTE)`，Engine 日志报告 JDBC 类型 `varcharbyte`，异常路径为 `JdbcSource → JdbcCatalogUtils → KingbaseTypeConverter`，错误码 `COMMON-21`。报错发生在创建 source、解析表元数据阶段，尚未提交 Engine 作业。
2. Engine 的 Kingbase 转换器先委托 Postgres 转换器，再处理 Kingbase 特有类型；两处都没有 `VARCHARBYTE`。本地运行目录 `/mnt/lc/seatunnel-300/connectors` 和离线交付目录 `seatunnel-web-docker/seatunnel/connectors` 的 JDBC JAR 均缺少该映射。
3. 找到了 10 月 8 日的旧验收任务 `codex-cast-type-repro-20261008`：查询显式使用 `CAST(varcharbyte_value AS varchar(128))`。这证明该任务通过了 SQL 转换路径，不代表 Engine 原表 catalog 已支持 `varcharbyte`。目前没有内网容器文件的校验值，不能断言内网实际加载的 JAR 与任一本地副本完全相同。
4. Web 把多数提交 HTTP 500 视为结果不明，写入 `SUBMISSION_UNKNOWN`，并暴露“确认未运行”按钮；该状态不在自动恢复的运行状态集合中，因此用户不操作就会一直停留。实际 Engine 还可能只返回外层 `API-06 / Factory initialize failed`，隐藏内层 `COMMON-21`，导致明确拒绝也进入人工确认流程。

## 最终行为

批量任务、复用该组件的文件任务列表和执行历史统一展示以下四类。Engine 协议内部的过渡状态继续保留，用于准确对账，不作为额外的用户状态。

| 页面状态 | 内部状态/条件 |
| --- | --- |
| 运行中 | STARTING、INITIALIZING、CREATED、PENDING、SCHEDULED、RUNNING、FAILING、DOING_SAVEPOINT、CANCELING |
| 失败 | FAILED、UNKNOWABLE、CANCELED/CANCELLED；取消原因仍保留在日志中 |
| 成功 | FINISHED、SUCCESS、SAVEPOINT_DONE |
| 未执行 | 没有执行实例或状态为空、UNSTARTED |

- 删除 `SUBMISSION_UNKNOWN` 枚举、人工确认 API、列表按钮和特殊标签；保留启动、终止、上下线操作本身的确认提示。
- 创建实例时持久化唯一的 Engine 作业 ID，调用 `/submit-job/upload?jobId=...` 使用该 ID。正常提交仍由 watcher 跟踪。
- 明确的 catalog 拒绝、source/sink 工厂初始化拒绝、连接拒绝、HTTP 4xx 自动结束为失败，保存脱敏的 Engine 响应原因。
- 读取超时或无法确认的网络异常继续显示“运行中”，不重复提交。提交心跳停止超过 10 分钟后，由每分钟运行的恢复任务按预存 ID 查询运行、详情及历史；找到终态自动收敛，找到运行任务继续跟踪。Engine 不可达或历史查询失败时留到下一轮，不据此判失败。
- Flyway 新增 `V1_0_47`（MySQL/H2），旧 migration 保持不变。历史待确认实例有 Engine ID 的转入自动对账；无 ID 的自动结束为失败，补结束时间并记录历史提交缺少 ID 的原因。历史无 ID 的请求不能可靠还原成某个 Engine 作业，更新不会自动重跑这些任务。
- Engine 新增 `VARCHARBYTE`、`CHARBYTE`、`BPCHARBYTE` 及相应 BYTE 文本别名到 `STRING` 的映射，保留长度、可空性、源类型和注释，不把字符字段当成二进制字段。

## 交付物与构建

Engine 修复提交：`aeae9d2b0`，位于 `/home/haruka/workspace/seatunnel`。Web 修复提交：`650a9401`。

仓库工具 `tools/seatunnel/package-kingbase-byte-patch.py` 读取**目标部署的原始 JDBC JAR**和修复后构建的 JAR，只替换 `KingbaseTypeConverter.class`。逐项校验其他 ZIP 内容不变，并生成 `manifest.json` 和 `SHA256SUMS`。不会写部署目录、执行 Compose 或重启服务。替换类的 class version 为 52，兼容 Engine 截图中的 Java 8，无需升级 Engine Java。

完整离线更新包：`artifacts/seatunnel-update-20261022.tar.gz`，含 `web/libs/seatunnel-web-api.jar`、`web/web/` 静态资源、`engine/` JDBC 补丁、更新计划、源码补丁和页面验收截图；另有逐文件 SHA256SUMS，不包含配置、密钥或数据库内容。`web/` 下的内容对应现有发行目录，Engine 文件对应 connectors 目录，按下文顺序分别更新。

本次基于本地离线交付 JAR 打包的补丁：

```text
artifacts/kingbase-byte-20261022/connector-jdbc-3.0.0.jar
artifacts/kingbase-byte-20261022/manifest.json
artifacts/kingbase-byte-20261022/SHA256SUMS

原始 JAR SHA-256：d6b9007c0c2c81e12689168bc59e1f03f412ee7dcf08466381726dcaee02bc4c
补丁 JAR SHA-256：ad478f5d252409b6ffc68cb5dbabd02bc73498805217e249c966545c5c517791
```

内网原 JAR 校验值不同，应先复制内网原 JAR 出来，再以它为基底重新打包，避免覆盖已有的其他连接器修复。不得把不同版本的 `connector-jdbc` 并排放进 connectors。

构建示例（本机 Java 使用 `/opt/jdk-21.0.11+10`）：

```bash
# 在 Engine 仓库中构建。本仓库旧 Spotless 2.29.0 / google-java-format 1.7
# 在 JDK 21 下不能正常运行，已实际尝试并记录；此处只跳过该检查，不跳过测试。
./mvnw -pl seatunnel-connectors-v2/connector-jdbc -Dskip.spotless=true \
  -Dtest=KingbaseTypeConverterTest -DfailIfNoTests=false test
./mvnw -q -pl seatunnel-connectors-v2/connector-jdbc \
  -DskipTests -Dskip.spotless=true verify

# 返回 Web 仓库打包，输出目录必须是新目录。
python3 tools/seatunnel/package-kingbase-byte-patch.py \
  --base-jar /待打包/内网原始/connector-jdbc-3.0.0.jar \
  --fixed-jar /home/haruka/workspace/seatunnel/seatunnel-connectors-v2/connector-jdbc/target/connector-jdbc-3.0.0-2.12.15.jar \
  --output-dir /补丁输出目录

# Web 后端（迁移脚本随 JAR 交付）、前端分别构建，不隐式部署。
./mvnw -pl seatunnel-web-api -am -DskipTests -Dskip.frontend.build=true package
cd seatunnel-web-ui
npm run build
```

## 内网执行顺序：沿用当前镜像、更新挂载文件

以下是交付后的执行计划，本次未在 `/mnt/lc` 或内网运行部署、Compose、容器重启命令。

1. 确认截图环境实际是 Engine 3.0.0；记录 Web/Engine 当前镜像 ID、挂载路径及原文件校验值。备份 Web 数据库、后端 JAR、静态资源目录和原 JDBC JAR。暂停调度，等待或按现有流程结束运行任务，避免更新过程中引入新执行。
2. 更新 Engine：保留现有 `image:` 和其他 lib/连接器。当前交付 Compose 已给 master、worker1、worker2 挂载 `./connectors:/opt/seatunnel/connectors`，因此只需更新这个宿主机目录中的同名 JDBC JAR；先停集群，替换文件，再启动所有成员，**所有节点都必须加载同一补丁**。不能只替换 Web 插件，也不能只重启 master。

   运维窗口示例（在实际 Engine Compose 目录执行）：

   ```bash
   # 校验收到的补丁包，确认 manifest 中原始校验值与本机一致。
   sha256sum connectors/connector-jdbc-3.0.0.jar
   cd /收到的补丁目录
   sha256sum -c SHA256SUMS

   cd /实际的/Engine-Compose目录
   docker compose stop worker1 worker2 master
   # 将原 JAR 备份到 connectors 目录之外，再原子替换同名 JAR。
   cp -p connectors/connector-jdbc-3.0.0.jar /备份目录/connector-jdbc-3.0.0.jar
   cp /收到的补丁目录/connector-jdbc-3.0.0.jar connectors/connector-jdbc-3.0.0.jar.new
   mv connectors/connector-jdbc-3.0.0.jar.new connectors/connector-jdbc-3.0.0.jar
   docker compose up -d --pull never master worker1 worker2
   ```

   若现场没有 connectors 目录挂载，可在每个节点增加同一文件映射：`./patches/connector-jdbc-3.0.0.jar:/opt/seatunnel/connectors/connector-jdbc-3.0.0.jar:ro`，保留镜像。此时应重建容器以应用挂载，而非仅 `restart`。文件替换需要重新加载类；本方案不承诺 JVM 无重启热更新。

3. 更新 Web：现有交付将 `./dist/seatunnel-web-1.0.0` 挂载到 `/opt/seatunnel-web`，前端 `web` 子目录挂载给 Nginx。保持 JRE、Nginx、MySQL 镜像不变，停止 Web 后端后替换 `libs/seatunnel-web-api.jar` 和前端 `web/` 静态资源；不要覆盖 conf、驱动目录或 `.env`。后端启动自动执行 Flyway 47，检查迁移成功日志；前后端一起切换，刷新浏览器缓存。
4. 验收：先检查 Engine `/overview` 与全部节点健康；在 Web 使用原来的 Kingbase BYTE 字段表运行单表任务，再运行多表任务，核对记录数与包含中文的字段值。页面应自动经过运行中到成功，失败配置应自动到失败，可查看错误原因；状态筛选仅四项，不出现人工确认入口。验收通过后恢复调度。
5. 回滚：暂停调度、停止 Web 和 Engine，恢复原 JDBC JAR、后端 JAR及前端资源，再启动原镜像。新迁移不删除业务数据，保留 Flyway 47 的记录，不改历史表、不修改旧脚本；已经归并的历史状态不反向伪造。如需完整恢复数据库，用更新前备份按现场恢复流程执行。

## 本地验证与边界

- 37 项 Kingbase 转换器测试通过，JDBC 模块 verify 通过。
- 原始离线 JDBC JAR 可复现 `varcharbyte` 不支持；实际交付的类级补丁 JAR 对 `varcharbyte`、`charbyte`、`bpcharbyte` 均通过 STRING(32)、不可空验证，其他 ZIP 内容保持不变。
- 52 项后端定向回归通过，覆盖自动恢复、HTTP 500 明确拒绝、网络异常、失败历史查询、条件更新、防重复提交、状态筛选与 OpenLineage 事件状态兼容。
- 前端 22 项状态测试、TypeScript 检查和正式构建通过。
- Playwright 实际连接本地 Web/Engine：状态筛选仅四项；“未执行”筛出无实例任务；手动启动零行 Kingbase 查询，`未执行 → 运行中 → 成功`，最新版本重启后再次验证成功，Engine 使用预存 ID `23454816346080`，目标表未写入记录。
- 故意使用无效字段的实例 `23454818207200` 收到 Engine API-06 后自动失败；此前遗留提交实例 `23454716116192` 在重启后自动对账为失败，无人工确认。源码中新迁移与已执行版本校验值一致，重启时 Flyway 48 个脚本校验通过，数据库版本 1.0.47。
- 本地验收创建的两个任务已下线，保留执行记录供复核。
- 页面截图保存在 `artifacts/kingbase-byte-20261022/status-failed.png` 与 `status-success-and-options.png`。
- 原始 Kingbase BYTE 表在内网的真实数据同步，需要上面的挂载更新后验收；本次没有修改本地或内网 Engine 部署文件、没有重启容器，也没有声称完成内网端到端验证。
