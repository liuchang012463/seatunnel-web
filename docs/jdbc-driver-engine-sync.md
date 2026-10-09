# JDBC 驱动隔离：Web 与 Engine 使用同一份驱动

## 一、要解决的两个问题

1. **同名驱动冲突**：Vastbase 的驱动是重打包的 PostgreSQL 驱动，SPI 与实现类都叫 `org.postgresql.Driver`。把 Vastbase 的 jar 放进引擎 `lib/` 后，PG 任务会报错——同一个 JVM 里这个名字只能对应一个类。
2. **Web 与 Engine 驱动不同步**：Web 用自己的驱动目录（`/opt/seatunnel-web/jdbc-drivers`）连通数据源，引擎却用 `lib/`、`connectors/` 里碰巧存在的驱动，于是出现「Web 能连、任务跑不通」。

## 二、根因

引擎侧 `SimpleJdbcConnectionProvider.loadDriver()` 原先**先按类名字符串在全局 `DriverManager` 里找**：

- `DriverManager` 以类名索引，同名驱动只能注册生效一个；
- 放在 `lib/`（服务端 classpath）的驱动由所有任务共享，先注册者胜出；
- 因此只要 Vastbase jar 进了 `lib/`，PG 任务就可能拿到 Vastbase 的驱动，反之亦然。

同时引擎没有任何「按任务指定驱动」的入口：Web 知道 `driverLocation`，却从不下发给引擎。

## 三、方案（引擎 fork + Web 两侧改动）

### 引擎（`/home/haruka/workspace/seatunnel`，基于 **v3.0.0 tag** 的分支 `3.0.0-driver-isolation`）

> 已部署引擎 `/overview` 返回 `gitCommitAbbrev=5056e3e`，正是上游 `v3.0.0` tag 的提交，因此改动从该 tag 起分支，
> 而不是从领先 140 个提交的 `dev` 起（fork 里的 tag 需要先从 apache/seatunnel 同步）。

1. **新增 `driver_location` 选项**（`JdbcCommonOptions`，source/sink 的 OptionRule 均已声明）：
   `driver_location = "/path/a.jar;/path/b.jar"`，`;` 分隔，接受绝对路径或 `file://` URI。
2. **驱动 jar 进入任务类加载器**：`JobPluginClasspathHelper` 收集插件配置里的 `driver_location`，
   - 解析阶段：并入 `connectorJarList(...)`，让 connector 在解析期（如 `JdbcSource` 构造函数加载驱动）就能看到该 jar；
   - 运行阶段：并入 source/sink action 的 jar urls（`MultipleTableJobConfigParser`），
     因为任务线程的类加载器由 action jar urls 构建（`JobMaster` → `DefaultClassLoaderService`）。
     只改解析阶段不够——运行期任务会退回服务端 `lib/` 的驱动（这一点由本地端到端验证发现并修正）。
   - 引擎本就为每个任务构建 child-first 的 `SeaTunnelChildFirstClassLoader`，并在每个节点校验 jar 是否存在，
     缺失时报出具体节点名。
3. **`loadDriver` 改为优先任务类加载器**：先 `Class.forName(driver, true, 任务线程 TCCL)`，
   再退回连接器自身的类加载器（source/sink 各自独立，连接器类可能由另一个加载），最后才是 `DriverManager`。
   任务声明的驱动一定胜过全局注册的同名驱动；未声明驱动的任务行为不变（仍用 `lib/` 的驱动）。
4. **驱动 jar 分发（新增 REST 端点）**：`POST /driver-jar/upload?fileName=x.jar`（jar 作为原始请求体）——
   `UploadDriverJarServlet` → `DriverJarService`：按内容摘要存到 `<connector-jar-storage-path>/driver-jars/<digest8>-<name>.jar`，
   再经 Hazelcast `SendConnectorJarToMemberNodeOperation` 推送到**每个集群成员**，返回所有节点一致的路径。
   这样 master / worker 跨主机部署时，任务落在哪个节点都能读到同一个 jar；Web 与引擎不需要共享文件系统。
   - 只向**尚未持有该 jar 的成员**推送（`CheckConnectorJarExistsOperation` 先查询）：重复提交不再重复传输，
     而重启过、丢失缓存的节点仍会被补推（自愈保留）。
   - **成员侧落盘需要自建目录**：`ServerConnectorPackageClient.storageConnectorJarFile` 原先直接 `new FileOutputStream(...)`，
     新节点上没有 `<storage>/driver-jars/` 时会静默失败（只记一条 warning，且不带异常），导致任务在该节点找不到驱动。
     已补 `mkdirs()` 并把异常一并打进日志。该缺陷在单机伪集群（共享文件系统）下不可见，已用双容器集群复现并验证修复。

为什么不做「shim / shade / 私有 URLClassLoader 加载驱动」：connector 代码本身引用了驱动里的类型
（如 `org.postgresql.util.PGobject`、`com.mysql.cj.MysqlType`）。把驱动放进一个**私有的**类加载器后，
驱动返回的对象与 connector 编译期看到的类型不再同一个类，写入 timestamptz / geometry 等类型时会 ClassCastException；
父优先的 `URLClassLoader` 又会直接返回父加载器的同名驱动，等于没隔离。把驱动 jar 放进**任务自己的**类加载器
才能同时保证「隔离」和「类型一致」。

### Web（`seatunnel-web`）

`AbstractJdbcHoconBuilder.putConnCommon()` 在生成 JDBC source/sink HOCON 时，把数据源的
`driverLocation` 解析为绝对路径并输出 `driver_location`（多个 jar 用 `;` 连接）：

- 覆盖全部生成路径：脚本任务、向导单表/多表、连通性检查任务（都走同一批 JDBC builder）。
- 相对路径按 Web 的驱动目录解析，与 `AbstractJdbcConnectionProvider` 的规则一致。
- Web 本地读不到的 jar 只记 WARN 并跳过：该数据源在 Web 侧本来也连不通，而引擎会校验每个节点上的 jar，
  跳过可以避免把「本地就缺 jar」的问题升级成任务提交失败。

`EngineDriverJarPublisher`（`seatunnel-web-engine-client`）在**提交任务前**做一次替换：

- 扫描待提交 HOCON 里的 `driver_location`，凡是能在 Web 本地读到的 jar，先 POST 到引擎的
  `/driver-jar/upload`（引擎存储并推送到所有成员），再把该值替换成引擎返回的路径；
- 同时兼容向导生成的带引号写法（`driver_location = "..."`）与脚本模式手写的无引号写法（`driver_location = /path/x.jar`），
  多 jar 的 `;` 分隔在两种写法下都支持；替换时保留原有引号与分隔符；
- 读不到的值原样保留（说明它本来就是引擎/节点可见的路径）；
- 接入点：批量提交 `BatchJobSubmitter`、流式提交 `StreamingJobSubmitter`、连通性检查 `DefaultSeaTunnelTestJobExecutor`。
- 每次提交都重传（幂等，按摘要落盘），因此节点重启或新节点加入后也能自愈；数据库里保存的 runtimeConfig 仍是 Web 侧路径，
  只有真正提交给引擎的配置使用引擎路径。

## 四、部署（**尚未执行，需授权**）

### 1. 引擎：重新构建镜像

```bash
cd /home/haruka/workspace/seatunnel   # 分支 3.0.0-driver-isolation
deploy/docker/build-engine-image.sh   # 默认产出 seatunnel:3.0.0-driver-isolation
```

镜像基于 `apache/seatunnel:3.0.0`（与已部署栈同基础：Debian 11 + OpenJDK 8 + arm64），只覆盖两个产物：

| 产物 | 容器内位置 | 说明 |
|---|---|---|
| `connector-jdbc-3.0.0.jar` | `/opt/seatunnel/connectors/` | `driver_location` 选项与驱动解析 |
| `seatunnel-starter.jar` | `/opt/seatunnel/starter/` | 解析/运行期类加载器接入 + `/driver-jar/upload` 端点 |

随后把 `/mnt/lc/seatunnel-300/docker-compose.yml` 里 master/worker1/worker2 的 `image: apache/seatunnel:3.0.0`
改为 `seatunnel:3.0.0-driver-isolation` 并重建容器。

### 2. Web：替换后端 jar

重新构建 `seatunnel-web-api.jar` 并替换 `/mnt/lc/open_metadata_2_0_4/seatunnel-web-production/app` 下的产物，重启 `seatunnel-web-api`。

### 3. 驱动 jar 的分发（无需共享目录）

驱动 jar 由 Web 在提交前上传给引擎，引擎存储到 `<connector-jar-storage-path>/driver-jars/`（默认在 `SEATUNNEL_HOME` 下）
并推送到每个集群成员。因此：

- **不再需要**把 Web 的驱动目录挂载进引擎容器；
- 跨主机、master/worker 分离部署同样成立（Web → 引擎 REST 是既有方向，引擎内部走 Hazelcast）；
- 若希望驱动缓存跨容器重建保留，可把 `seatunnel.yaml` 的
  `seatunnel.engine.connector-jar-storage.storage-path` 指到一个挂载卷。

## 五、验证

### 已完成：v3.0.0 基线（`3.0.0-driver-isolation` 分支）

在宿主机用**从 v3.0.0 tag 构建**的 `seatunnel-starter.jar` + `connector-jdbc-3.0.0.jar` 起了一个单机集群
（master 15801 / worker 15802 / REST 18080，与已部署栈端口、集群名隔离），验证了与生产一致的 REST 路径：

| 验证项 | 命令 / 观察点 | 结果 |
|---|---|---|
| 上传端点 | `POST /driver-jar/upload?fileName=repackaged-pg-driver.jar`（jar 作为 body） | 返回 `{"path":"/tmp/st-local/driver-jars/4cc1ca7723455df0-repackaged-pg-driver.jar"}`，文件落盘 |
| 上传参数校验 | 缺少 `fileName` / 非 `.jar` 后缀 | 均 400 |
| 跨节点分发 | 上传后查看 **worker** 日志 | worker 出现 `ServerConnectorPackageClient - File storage for an existing file .../driver-jars/4cc1...jar`，说明推送操作在 worker 上执行 |
| REST 提交 + 运行期固定驱动 | `POST /submit-job?format=hocon`，`driver_location` 用上传返回的路径 | `finishedJobs: 1`；master 日志 2 处 pinned 驱动标记（解析期），**worker** 日志 1 处标记 + `Prepared statement: select 1 as id`（任务实际在 worker 上用被固定的驱动执行） |
| 反向：固定一个不能连接的驱动 | `driver_location` 指向不做注册的假驱动 | 任务失败并报 `No suitable driver found`，证明任务确实使用了被固定的 jar |
| 对照：不声明驱动 | 仅 `driver = org.postgresql.Driver` | FINISHED，走 `lib/postgresql-42.4.3.jar` |

### 双容器集群（跨文件系统，复现并验证成员落盘缺陷）

单机伪集群共享文件系统，看不出「成员没有目录」的问题，因此用刚构建的镜像起了一套**与已部署栈隔离**的临时集群
（`--network host`，集群名 `seatunnel-verify`，Hazelcast 35801/35802，REST 38080/38081，容器名 `st-verify-*`，验证后已删除）：

| 步骤 | 修复前（旧镜像） | 修复后（本轮镜像） |
|---|---|---|
| 上传前 worker 上 `/opt/seatunnel/driver-jars` | 不存在 | 不存在 |
| 上传后 worker 侧 | 目录仍不存在，日志 `The connector jar package file .../driver-jars/4cc1....jar storage failed`，jar 丢失 | 目录被创建，jar 落盘（2275 bytes） |
| 同一 jar 再次上传 | 每次都全量推送 | worker 上文件 mtime 不变（已持有则跳过传输） |
| REST 提交任务（`driver_location` 指向该路径） | —— | `finishedJobs=1`，**worker 容器**日志出现 pinned 驱动标记并完成 `select 1` 读取 |


单元测试：`SimpleJdbcConnectionProviderDriverTest`（同名驱动优先级/回退）、`JobPluginClasspathHelperTest`
（`driver_location` 解析与 jar 顺序）、`EngineDriverJarPublisherTest`（上传替换/跳过非本地值/多 jar）、
Web 侧 `JdbcBatchBuilderTest`（`driver_location` 输出与跳过缺失 jar）。

镜像：`deploy/docker/build-engine-image.sh` 产出 `seatunnel:3.0.0-driver-isolation`，
已校验镜像内 starter 含 `UploadDriverJarServlet`/`DriverJarService`、connector-jdbc 含 `SimpleJdbcConnectionProvider`。

### 部署到 3.0 验证栈后的验收步骤

1. Web 中新建/编辑 Vastbase 数据源（`dbType=POSTGRE_SQL`，`driverLocation=Vastbase-G100-2.16_pg_2026062910.jar`），
   执行「连通性检查」：Web 侧通过。
2. 查看该任务提交的 HOCON：`source.Jdbc` 块应含 `driver_location = "/opt/seatunnel-web/jdbc-drivers/Vastbase-....jar"`，
   `driver = "org.postgresql.Driver"`。
3. 提交一个 Vastbase → Console 的任务：`FINISHED`。
4. 同时提交一个 PG → Console 的任务（其数据源 `driverLocation` 指向的 jar 不在 Web 驱动目录时会跳过 `driver_location`）：
   `FINISHED`，且引擎 `lib/postgresql-42.4.3.jar` 未做任何改动、`lib-driver-conflict-backup/` 里的 Vastbase jar 不再需要放回 `lib/`。
5. 反向验证：把 Vastbase jar 放回 `lib/` 会再次打挂 PG 任务——不要这么做，用 `driver_location` 取代它。

## 六、已知限制

- 同一个任务里，source+transform 共用一个类加载器、sink 单独一个。因此同名驱动最多只能在 source 侧和 sink 侧各固定一个；
  同一侧（例如 source 里同时连 PG 和 Vastbase）只能有一个驱动生效。
- `driver_location` 是 3.0 fork 的能力：未打补丁的 3.0 与 2.3.13 会忽略该键（其 OptionRule 不做未知键校验），
  任务仍按各自 `lib/` 的驱动运行，不会因为 Web 输出该键而失败；但 Web 侧的上传端点只有补丁版引擎才有，
  因此在未升级的引擎上提交带 `driver_location` 的任务时，Web 的上传会失败——需要引擎与 Web 同步升级。
- Web 驱动目录里缺 jar（例如 PG 的 `postgresql-42.5.1.jar` 当前不在目录中）时，该数据源在 Web 与引擎两侧都不会被固定驱动，
  需要把对应 jar 上传到 Web 驱动目录才能实现「两侧同一份驱动」。
- 引擎上的驱动缓存（`driver-jars/`）目前只增不删，容量取决于驱动 jar 体积（每个数据源一份，按摘要去重）；
  如需回收，可清理该目录，下一次提交会自动重传。
- 每次提交仍会把 jar 从 Web 传到引擎（引擎侧只向缺失的成员转发）。没有在 Web 侧加缓存是刻意的：
  缓存会让「节点重启/新增后自愈」失效，而省下的只是 Web→引擎这一跳（成员转发才是大头，已在引擎侧按需跳过）。
  若后续确有高频提交压力，可再加「缓存 + 引擎 ensure（不传字节、仅按需转发）」的组合。
