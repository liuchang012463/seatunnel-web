# DuckDB 文件资源 Source 接入方案

> 状态：已完成 SeaTunnel Engine 3.0.0 + DuckDB JDBC 1.3.1.0 + MinIO 的 Web 到 sink happy-path E2E（2026-10-09）。
>
> 范围：SeaTunnel Web 1.0.0 的批量文件引接任务；复用既有 Jdbc source 只读查询 MinIO 对象。Engine 节点需预置锁定版本驱动、httpfs 和共享初始化 SQL 目录。

## 结论

**实现复用现有 `Jdbc` source、官方 DuckDB JDBC 与 `session_init_sql_file`：Web 在受控共享目录生成私有初始化脚本，Engine 通过 JDBC URL 加载脚本完成 MinIO 配置、`LOAD → ATTACH`；source query 只包含 `USE` 和用户数据查询。无需新增 SeaTunnel 选项、修改 connector 或实现自定义 JDBC 驱动。**

DuckDB `.db` / `.duckdb` 文件继续作为 MinIO 文件资源管理，用户仍在文件引接任务中选择文件、schema、表或自定义 SELECT。SeaTunnel 节点上的 DuckDB `httpfs` 通过 S3 API 只读访问数据库对象，不向 Web 下载任务输入文件。Web 与可能创建 JDBC 连接的 Engine 节点必须将同一初始化目录挂载到彼此可见的路径。

该方案有明确边界：**锁定已验证的驱动版本；使用顶层 `query`；单 split；不配置 `table_path`、`table_list`、`partition_column` 或 `where_condition`。** 初始化文件保存静态 MinIO 凭证，必须限制目录和文件权限，并按文件资源生命周期清理。

本次 SeaTunnel 3.0.0 门禁发现 `DuckDBCatalog` 的元数据连接未传递 JDBC `properties` 中的自定义 S3 参数；原先“properties + 多语句 query”方案因此无法初始化远程 catalog。`session_init_sql_file` 中创建的 S3 secret 可供 metadata 和 reader 连接初始化；`SCOPE` 设置为所选对象 URI 前缀，用于 DuckDB 选择凭证，不替代 MinIO/IAM 的访问控制。若 Engine 无法访问共享初始化目录，或未来需要并行分片和任意 query 改写，则评估轻量 JDBC 包装驱动；原稿的 `connection_init_sql` connector 补丁仍不是本功能的前置依赖。

## 原方案评审

原稿保留文件资源语义、复用 `Jdbc`、只读访问 MinIO、预置匹配版本扩展的方向正确。主要需要修正以下判断：

| 问题 | 评审结论与修订 |
|---|---|
| 将“需要连接初始化”直接推导为“必须新增 `connection_init_sql`” | 结论过强。3.0.0 部署构建的元数据 catalog 忽略自定义 JDBC properties；改用 JDBC `session_init_sql_file`，不需要修改 connector。 |
| 在初始化 SQL 中写入 AK/SK | 不放入任务 query 或 HOCON。当前 Engine 会记录 prepared query；凭证由私有初始化文件提供。该文件仍属于敏感配置，须 owner-only 权限、受控挂载、生命周期清理，并检查 Engine 日志不得输出脚本内容。 |
| 无条件否定包装 JDBC driver | 包装驱动可只委托官方 driver 并初始化连接，原生库、ResultSet、重连机制均继续复用。它比长期维护 Engine 补丁更独立，适合作为扩展方案。 |
| 认为 `session_init_sql_file` 必然需要 Web/Engine 共享卷 | 动态任务初始化脚本由 Web 生成，必须共享挂载或有等价的节点分发机制。本实现用共享目录，并同时配置 Web 本地目录与 Engine 可见目录。 |
| 将 secret 当作天然隔离的 session 状态 | DuckDB secret、附加 catalog 可能由同一数据库实例的多个连接共享。使用连接独立的无名内存实例，避免不同任务共用 named-memory 数据库。 |
| 声称 Web 不读取 `.db` 内容 | 仅适用于任务提交的数据面。现有 `DuckDbCatalogReader` 的检查/预览仍下载完整文件到 Web 临时目录；本方案保留现状并明确容量影响。 |

## 核对依据与验证边界

本次检查了 Web 仓库及 `/home/haruka/workspace/seatunnel` 的现有源码。后者当前提交为 `fcd8a74ec`；这是本地实现证据，不能替代对已部署 Engine 二进制的确认。

- Web 的 `seatunnel-web-api/pom.xml` 当前声明 DuckDB JDBC `1.3.1.0`。首版以该版本为验证基线，记录实际发布 JAR 的校验和；不能因 `current` 文档更新而隐式升级 driver 或扩展。
- `JdbcConnectionConfig` 接收 `properties`，`SimpleJdbcConnectionProvider.getOrEstablishConnection()` 将其传入所配置 driver 的 `connect(url, info)`。元数据连接通过 `JdbcCatalogUtils` 使用相同 connection provider。
- 实际 Engine 3.0.0 的 `JdbcSource` 构造期间会查询 catalog；DuckDB catalog 的 metadata connection 只复制 JDBC user/password，没有复制自定义 `s3_*` properties。因此 source properties 即使传给普通 JDBC provider，也未能让本次 Engine 的 DuckDB catalog 访问 MinIO。
- DuckDB dialect 接受 `jdbc:duckdb:` 前缀；元数据查询调用 `Connection.prepareStatement(query).getMetaData()`。单 split 的 `ChunkSplitter` 保留原 query 并准备执行；设置 `enable_concurrent_read=false` 跳过分片分析。现有 [`JDBC source` 文档](https://seatunnel.apache.org/docs/3.0.0/connectors/source/Jdbc/)也提供连接 properties 和该分片开关。
- `EngineDriverJarPublisher` 已支持 `driver_location` 中以分号分隔的多个本地 JAR，并复用 Engine 上传接口改写路径。但本地 SeaTunnel 3.0.0 REST API 对 `/driver-jar/upload` 返回 404；DuckDB 配置必须指向所有 Engine 节点都可读的预置 JAR 路径。Web 本机 JAR 只有在目标 Engine 明确支持上传接口时才能直接复用。

本次在独立 JShell 进程中使用本机缓存的 `duckdb_jdbc-1.3.1.0.jar` 做了最小能力探测，没有修改项目代码或启动 SeaTunnel：

探测 JAR 的 SHA-256 为 `612c1e55e45fb496902b8ec7f9bb957a404813e6d1fc1c4531df000d1bab2d07`；发布时必须核对实际 artifact，不能仅凭文件名认定与本次探测一致。

| 探测 | 结果 | 能说明什么 |
|---|---|---|
| `jdbc:duckdb:https://blobs.duckdb.org/databases/stations.duckdb`，只读连接 | 失败；URL 被解析成工作目录下的本地数据库路径 | 当前驱动不能通过替换 JDBC 数据库路径来省掉远程 `ATTACH`。 |
| 无名内存连接，通过 Properties 传入示例 `s3_*` 值，随后 `LOAD httpfs` | `current_setting('s3_endpoint')` 返回示例 endpoint | 当前 driver/httpfs 接受这条参数传递路径；示例未使用真实 AK/SK，也未访问私有 MinIO。 |
| prepare 多语句 `LOAD; ATTACH IF NOT EXISTS; USE; SELECT count(*)`，读取官方公开 HTTPS 数据库 | 两个独立连接，每个连接重复 prepare 两次，均得到 1 列元数据、578 行统计结果 | 初始化先于最终 SELECT 的元数据绑定；重复 prepare 与新连接都能工作。 |

**以上 JShell 表格记录的是初始方案探测；下方实施记录包含后续私有 MinIO 与 SeaTunnel 3.0.0 集群 E2E。** 原生 JDBC 行为仍需随发布 driver 一起锁定和回归，不能推广为任意 JDBC driver 均支持。[官方 `1.3.1.0` PreparedStatement 源码](https://github.com/duckdb/duckdb-java/blob/v1.3.1.0/src/main/java/org/duckdb/DuckDBPreparedStatement.java)是对应实现入口。

## 目标运行链路

```mermaid
sequenceDiagram
    participant U as 用户
    participant W as SeaTunnel Web
    participant E as SeaTunnel Engine / Jdbc
    participant D as 官方 DuckDB JDBC / httpfs
    participant M as MinIO
    U->>W: 选择 DuckDB 文件资源和表/SELECT
    W->>W: 解析资源，写入受限共享目录的初始化 SQL
    W->>W: URL 指向 Engine 可见的 session_init_sql_file
    W->>E: 发布 JDBC JAR，提交批量任务
    E->>D: connect(jdbc:duckdb:;session_init_sql_file=...)
    D-->>E: 返回独立内存数据库连接
    D->>D: LOAD httpfs、创建对象范围 S3 secret、ATTACH READ_ONLY
    E->>D: prepareStatement(USE schema + 用户 SELECT)
    D->>M: S3 鉴权和按需读取数据库块
    D-->>E: 最后一条 SELECT 的元数据 / ResultSet
    E->>E: 交给现有 transform / sink
```

DuckDB JDBC 在建立连接时执行初始化文件，使 catalog 元数据连接也能访问远程对象。初始化文件不能由用户编辑；需要独立执行额外的 catalog 探查或改写查询时，应重新验收连接生命周期或评估包装驱动。

## 首版 HOCON 与 SQL 契约

### 现有选项即可表达

以下为生成契约示例，占位值均由文件资源元数据和运行配置提供：

```hocon
env {
  job.mode = "BATCH"
  parallelism = 1
}

source {
  Jdbc {
    url = "jdbc:duckdb:;session_init_sql_file=/opt/seatunnel/lib/duckdb-init/duckdb-resource-<id>.sql"
    driver = "org.duckdb.DuckDBDriver"
    driver_location = "/opt/seatunnel/lib/duckdb_jdbc-1.3.1.0.jar"
    enable_concurrent_read = false
    properties {
      autoinstall_known_extensions = "false"
      threads = "2"
      memory_limit = "512MB"
      jdbc_stream_results = "true"
    }
    query = """
      USE "duckdb_source"."main";
      SELECT * FROM "duckdb_source"."main"."<table>"
    """
    plugin_output = "<existing-output-route>"
  }
}
```

`properties` 只包含非敏感的运行参数。初始化文件包含 S3 endpoint、region、路径风格、TLS 和静态 AK/SK，并先于 metadata/query 执行。`threads`、`memory_limit` 为示例预算，应按 Engine 容量设置；`jdbc_stream_results` 必须由锁定版本支持并验收。`plugin_output` 仅在现有路由需要时输出。

初始化文件使用 DuckDB/httpfs 的 **S3 Secrets Manager** 创建 MinIO secret，并将 `SCOPE` 设置为当前对象 URI。DuckDB 按路径前缀选择 secret；这不是对象授权机制，AK/SK 本身仍须由 MinIO/IAM 权限限制。该 secret 由每个无名内存连接独立持有；初始化文件中的 AK/SK 仍需通过文件权限、Engine 主机访问控制、日志检查和清理来保护。后续 driver/httpfs 升级必须回归该配置，不能静默把凭证移回 query/HOCON。参数含义见[官方 S3 API 与 secret 文档](https://duckdb.org/docs/current/core_extensions/httpfs/s3api)。

无名内存数据库保持可初始化；**不要设置整个内存连接的 `duckdb.read_only=true` 来代替远程库的只读属性**。源对象的只读约束由 `ATTACH ... (READ_ONLY)` 保证。

### Web 生成规则

保留现有 `sourceMode=FILE_RESOURCE`、`fileFormatType=duckdb` 和 `fileResourceId` 工作流：

1. 校验 `.db` / `.duckdb` 文件资源和静态 MinIO AK/SK；从 `FileResourceResolver` 取得 bucket、object key、endpoint、region、TLS 和 path-style 信息，不为任务提交下载数据库。
2. 插件名生成 `Jdbc`，不生成 `DuckDB { ... }`；配置官方 driver 和 Engine 节点可读的 `driver_location`。
3. 使用 `DuckDbSourceInitSqlFileService` 将 `LOAD httpfs`、对象范围 S3 secret 和只读 `ATTACH` 写入 owner-only 初始化文件；JDBC URL 指向 Engine 节点可见的路径。query 只包含 `USE` 和用户数据查询。endpoint 使用 host:port；不改变共享 JVM 的环境变量或系统属性。
4. 表模式生成完整 catalog/schema/table SELECT；SQL 模式使用用户原本的单条 SELECT 或 WITH 查询，并通过 `USE` 设置所选 schema（默认 main）。过滤条件写入最终 SELECT。
5. 用户提交的 SQL 与最终多语句 query 分开保存。用户仍只能编辑数据查询；初始化段不可编辑。先校验用户查询，再拼固定引导段，不能让用户借该能力提交任意 DDL、ATTACH 或 secret 语句。
6. 对对象 URI 做正确编码和 SQL 字面量转义，对 catalog/schema/table 分别做 identifier 引用；使用配置序列化器生成 HOCON，不能直接拼接未转义的文件名或用户输入。
7. 首版显式设置 `enable_concurrent_read=false`，不输出 `table_path`、`table_list`、分区选项或 `where_condition`。`where_condition` 会把完整 SQL 包成子查询，多语句引导段不适用该包装。
8. 每次执行、重提任务都重新解析文件资源和凭证，沿用现有 `plugin_output`/transform 路由。固定源对象后，同一连接重复 prepare 使用 `ATTACH IF NOT EXISTS`；不得在复用的同一连接中切换到另一个对象。

现有 SQL 校验与权限需要覆盖源对象外的数据访问：单条 SELECT 仍可能包含外部文件函数或引用外部数据的 view；`READ_ONLY` 只限制数据库写入。现有预览在本地打开后关闭 external access 的方式，不能直接复制到依赖 httpfs 的远程 source 上。首版支持范围应限定为所选库中的表和仅引用该库数据的 view/SELECT，并验收实际执行边界。

### 凭证保护

AK/SK 不放入任务 query、HOCON properties 或任务配置快照；它们在 owner-only 初始化 SQL 文件中短暂落盘，并创建成 scope 限定到当前对象的临时 S3 secret。初始化文件仍是敏感数据。

- 初始化目录和脚本采用 owner-only 权限；目录必须仅挂载到受控 Web/Engine 主机。Engine 用户必须有权限读取该脚本。
- 文件资源删除时清理对应脚本；提交配置和日志只出现 Engine 可见路径，不输出脚本内容或 AK/SK。
- 每次 source 构建从当前受控文件资源配置读取凭证并覆盖该资源脚本。Engine 日志/异常链仍须检查不包含脚本内容或 AK/SK。
- 若部署环境无法保证这些文件权限、挂载访问控制或清理，则不能使用该路径；需改用受控 secret 分发和包装驱动，不能把凭证移回 properties。

## 其他实现路径与选择条件

| 路径 | 新增 Engine 源码改动 | 主要成本或限制 | 定位 |
|---|---|---|---|
| 现有 Jdbc + properties + 多语句引导 query | 无 | Engine 3.0.0 DuckDBCatalog 元数据连接未传递自定义 S3 properties | 本次门禁失败，不采用 |
| 轻量 JDBC 包装驱动，connect 内初始化 | 无 | 维护很小的独立 driver artifact；验证类加载及连接生命周期 | **需要改写 query/并行分片或首版门禁失败时优先采用** |
| 官方 `session_init_sql_file`，Web/Engine 共享目录 | 无 | 需要所有相关节点可见；脚本含敏感凭证并需清理 | **本次选用；需满足目录挂载和权限约束** |
| 原稿新增 source-only `connection_init_sql` | 有 | 自维护 connector、重新构建/发布、覆盖全部连接入口及升级回归 | 另有通用 JDBC 初始化需求并计划上游化时再考虑 |
| 提前导出查询结果为 Parquet，再用 `S3File` | 无 | 新增物化步骤、存储与生命周期；数据链路和时效性改变 | 接受预处理且希望 Engine 完全不加载 DuckDB 时可考虑 |
| HTTPS 预签名 URL | 通常仍需 ATTACH 初始化 | HEAD/GET 签名、Range、有效期和恢复限制 | 不是当前 S3 鉴权链路的直接替代 |
| 下载 `.db` 到节点本地再打开 | 无或取决于下载接入 | 整库复制、磁盘容量、节点路径及缓存清理 | 放弃对象直读目标时的降级方案 |

### 包装驱动备选的边界

只实现 JDBC `Driver` 委托，不复制 DuckDB driver，不包装所有 Connection/Statement/ResultSet，也不创建连接池或自行实现重连：

1. SeaTunnel 配置的 `driver` 指向独立包装类；URL 仍为 `jdbc:duckdb:`，让现有 DuckDB dialect 正常识别。自定义参数通过现有 `properties` 传递，不另造 JDBC URL 前缀。
2. 每次 `connect()` 创建一个独立的官方内存连接；从参数或受控引用取得凭证，在该连接依次执行 `LOAD httpfs`、临时 scoped S3 secret、只读 `ATTACH` 和 `USE`。
3. 移除包装参数和认证参数后，仅把允许的 DuckDB 运行配置交给官方 driver；初始化全部成功才返回官方 Connection。
4. 初始化失败关闭连接，输出资源标识、失败阶段和经过过滤的错误；不能把包含密钥的 SQL 或原始异常上下文直接透传到 Engine 日志。
5. 新连接由 SeaTunnel 现有 provider 创建，每次都重新初始化。包装类仅用于 DuckDB 文件资源 source，其他 JDBC source/sink 不选择它。
6. 包装 JAR 与官方 JDBC JAR 分开发布，复用已有分号分隔的 `driver_location`；不 relocate `org.duckdb` 或重打包原生库。验证 metadata/enumerator/reader 的任务 classloader 都能看见二者。

该备选将初始化与用户 SELECT 分开，允许后续分片查询引用已初始化的 catalog，并可转向官方 Secrets Manager。但并行读取的性能、快照一致性和资源预算仍要独立验收。[DuckDB S3 secret 文档](https://duckdb.org/docs/current/core_extensions/httpfs/s3api)说明了 endpoint、URL style、TLS 等配置。

### 初始化文件与预签名 URL 的限制

[`session_init_sql_file`](https://duckdb.org/docs/current/clients/java/connecting)由官方 JDBC driver 读取 Engine 进程可见的本地文件，不是远程 SQL URI，也不是通过 JDBC Properties 传递的初始化文本。统一部署文件能消除 Web 共享卷，但动态任务仍需要节点分发、权限、保留期及清理机制；文件的 per-connection 部分要按 driver 支持的 marker 规则放置。现有 driver JAR 上传流程不能直接当作 SQL 文件分发协议。

预签名 GET URL 同样不能当作任意 HTTP 方法的通行证；MinIO SDK 对 GET/HEAD 分别签名。[官方 MinIO Java API](https://github.com/minio/minio-java/blob/master/docs/API.md)规定签名有效期，需覆盖排队、运行、重试和恢复。DuckDB httpfs 会使用 HEAD/GET 和 Range 请求，必须测试签名兼容；当前直接 JDBC URL 打开远程库的探测也已失败。加代理来处理这些问题会新增数据服务，不属于首版最小路径。

## 驱动、扩展与快照部署要求

- 配置 `seatunnel.web.duckdb.driver-location` / `SEATUNNEL_WEB_DUCKDB_DRIVER_LOCATION` 为所有 Engine 节点都可读的同一绝对 JAR 路径。标准 SeaTunnel REST API 不提供 `/driver-jar/upload`，因此部署时要把锁定版本的 JAR 预置到共享 Engine `lib`，不能配置 Web 本机 Maven 缓存路径；目标 Engine 明确支持上传接口时才可用 Web 本机路径并复用 `EngineDriverJarPublisher`。
- Engine classpath 中不能同时放置多个 DuckDB JDBC 版本。E2E 中 1.1.2 的 `org.duckdb.DuckDBDriver` 被父 classloader 优先加载，即使 HOCON 指向 1.3.1.0；移除冲突项后 session-init URL 才由 1.3.1.0 解析。
- “无需 Engine 源码改动”仍包含运行依赖准备：每个可能创建 JDBC 连接的进程/节点都要能加载 JDBC driver、匹配版本与 OS/架构的 `httpfs`，并访问 MinIO DNS、端口及 TLS 证书。按实际提交、规划、执行阶段确定节点范围。
- httpfs 在受控发布阶段预置到运行用户可发现的扩展目录。任务只 LOAD，关闭自动安装；不能在每个任务中 INSTALL 或依赖外网下载。driver artifact 分发不等于 native extension 分发。
- 上游生成文件时先完成 checkpoint/正常关闭，再发布不可变对象；只上传 `.db` 不能补齐仍在 `.wal` 中的事务。任务期间禁止覆盖源对象；使用不可变 object key 或已验证的版本固定方式。
- 每个无名内存连接有独立的 DuckDB 实例和资源预算。设置 threads、memory_limit，并验证同节点多任务的总 native 内存、临时空间和回收；JVM 堆限制不能代表 DuckDB 的全部资源限制。
- 当前 Web 元数据检查和预览仍会整库下载，需评估文件大小、临时盘和超时。若后续统一改为远程读取，应单独评审；本次不把该变化混入 source 接入。

## 实施与验收结果

- Web 为 DuckDB 文件资源生成现有 `Jdbc` source；`session_init_sql_file` 初始化脚本加载 `httpfs`、创建 `SCOPE` 指向所选对象 URI 的 S3 secret、以 `READ_ONLY` attach 数据库。S3/IAM 权限仍由 MinIO 端控制；AK/SK 不出现在 source query、HOCON properties 或任务日志中。
- `DataSourceSourceBuilderFileResourceTest`：6 个测试通过，覆盖 URL/单 split、SQL 字面量转义、私有文件权限、S3 secret scope、query 脱敏和单条 SELECT 校验。
- Engine 验证栈使用 SeaTunnel `3.0.0`、DuckDB JDBC `1.3.1.0`（SHA-256 `612c1e55e45fb496902b8ec7f9bb957a404813e6d1fc1c4531df000d1bab2d07`）和匹配的 `linux_arm64` httpfs 扩展。共享初始化文件目录在 Web 宿主机和全部 Engine 节点可见，目录权限 `700`，脚本权限 `600`。
- Chrome/Playwright 实际运行离线文件任务 `DuckDB MinIO integration 20261009`（definition `23309485248352`）。任务成功，耗时 11 秒；Engine job `1160954815979716609` 最终为 `FINISHED`，source pipeline 读 4 行、sink 写 4 行，共写入 435 B 到 `kingbase.public.duckdb_minio_orders_20261009`。Engine 记录了 source prepared query 和 sink INSERT；没有凭证内容。
- E2E 期间确认 DuckDB 3.0.0 元数据 catalog 不传自定义 JDBC properties；legacy `s3_*` 设置虽可经 httpfs 读取对象，但远程 S3 database ATTACH 仍报告 database does not exist。改用对象范围 S3 secret 后，同一 JDBC 版本直接读取该对象 4 行，并通过 SeaTunnel Web/Engine/sink 全链路。
- 本次完成的是表模式单表 happy path。自定义 SELECT/WITH、复杂类型、大结果集、并发、重提/恢复以及负向凭证/TLS/扩展场景仍需分别验收；当前结果不声称覆盖这些门禁。

## 参考资料

- [SeaTunnel 3.0.0：JDBC Source](https://seatunnel.apache.org/docs/3.0.0/connectors/source/Jdbc/)
- [SeaTunnel 3.0.0：DuckDB 使用说明](https://seatunnel.apache.org/docs/3.0.0/connectors/source/DuckDB/)
- [DuckDB：远程 DuckDB 数据库只读 ATTACH](https://duckdb.org/docs/current/guides/network_cloud_storage/duckdb_over_https_or_s3)
- [DuckDB：S3 API 与 Secrets Manager](https://duckdb.org/docs/current/core_extensions/httpfs/s3api)
- [DuckDB：ATTACH 语句](https://duckdb.org/docs/current/sql/statements/attach)
- [DuckDB：HTTP(S) 与 Range 请求](https://duckdb.org/docs/current/core_extensions/httpfs/https)
- [DuckDB：Java/JDBC 连接与初始化文件](https://duckdb.org/docs/current/clients/java/connecting)
- [DuckDB Java 1.3.1.0：Driver 源码](https://github.com/duckdb/duckdb-java/blob/v1.3.1.0/src/main/java/org/duckdb/DuckDBDriver.java)
- [DuckDB Java 1.3.1.0：PreparedStatement 源码](https://github.com/duckdb/duckdb-java/blob/v1.3.1.0/src/main/java/org/duckdb/DuckDBPreparedStatement.java)
- [MinIO Java SDK：预签名 GET/HEAD API](https://github.com/minio/minio-java/blob/master/docs/API.md)

以上 `current` 文档用于解释能力和约束；参数与行为是否可用，以锁定的 JDBC JAR、匹配扩展和实际 SeaTunnel 3.0.0 构建验收为准。
