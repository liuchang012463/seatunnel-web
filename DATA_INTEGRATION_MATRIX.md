# 系统数据引接方式与数据库类型传输矩阵

## 一、引接方式与支持类型详情

### 1. 离线数据导入（传输方式：RESTful API / S3File）
* **支持类型**：
  1. **结构化数据导入**（传输方式：RESTful API / S3File）
     - Excel 文件（`.xlsx`、`.xls`）
     - CSV 文件（`.csv`）
     - JSON 文件（`.json`）
     - 文本文件（`text` / `.txt`）
     - 二进制文件（`binary`）
  2. **离线数据包导入**（已在采报工具项目实现接入）
     - 离线三级采报工具导出的数据包文件
  3. **DuckDB 数据导入**（传输方式：RESTful API / JDBC）
     - DuckDB 数据库文件（`.db`、`.duckdb`）
  4. **DMP 数据导入**（人大金仓、达梦）—— **【TODO 项】**
  5. **SQL 数据导入**（人大金仓、达梦、Oracle 等）—— **【TODO 项】**
* **数据流向**：
  - **来源 (Source)**：Web 本地上传 / 平台湖文件资源池（MinIO / S3 托管）
  - **去向 (Sink)**：支持导入至系统所纳管的关系型数据库（MySQL、Oracle、PostgreSQL、达梦、人大金仓、海量数据库、ZeoneDB、通用 JDBC）及 OLAP 数据库（Doris、Elasticsearch）。

---

### 2. 数据库引接（传输方式：JDBC / RESTful API）
* **支持类型**：
  1. **关系型数据库**（传输方式：JDBC）
     - **MySQL**：支持作为【来源】和【去向】
     - **Oracle**：支持作为【来源】和【去向】
     - **PostgreSQL**：支持作为【来源】和【去向】
     - **人大金仓（KingbaseES）**：支持作为【来源】和【去向】
     - **达梦数据库（Dameng DM8）**：支持作为【来源】和【去向】
     - **海量数据库（Vastbase）**：支持作为【来源】和【去向】
     - **ZeoneDB-D**：支持作为【来源】和【去向】
     - **通用 JDBC**：支持作为【来源】和【去向】（支持扩展接入任意具备 JDBC 驱动的关系型数据库）
  2. **非关系型数据库**（传输方式：RESTful API）
     - **Elasticsearch**：支持作为【来源】和【去向】
  3. **数据仓库 / OLAP**（传输方式：HTTP Stream Load / JDBC）
     - **Apache Doris**：支持作为【来源】和【去向】（支持高吞吐 Stream Load 导入）
     - **StarRocks**：支持作为【去向】
* **任务模式**：
  - 单表全量同步、单表增量同步、单表自定义 SQL 过滤同步、多表/整库批量同步、自定义 HOCON 脚本模式。

---

### 3. 实时数据引接（传输方式：Binlog / WAL 日志流 / TCP / RESTful API）
* **支持类型**：
  1. **数据库日志变更捕获 (CDC)**（传输方式：Binlog / WAL 日志流）
     - **MySQL-CDC**：支持作为【来源】（基于 Binlog 实时增量解析）
     - **PostgreSQL-CDC**：支持作为【来源】（基于 WAL 逻辑复制槽实时增量解析）
  2. **消息队列**（传输方式：TCP / Kafka 协议）
     - **Kafka**：支持作为【来源】和【去向】
  3. **实时搜索引擎 / 数仓**（传输方式：RESTful API / Stream Load）
     - **Elasticsearch**：支持作为【来源】和【去向】
     - **Apache Doris**：支持作为【去向】
     - **StarRocks**：支持作为【去向】
  4. **接口数据引接**（传输方式：RESTful API）
     - **HTTP / API**：支持作为【来源】（支持 GET/POST 请求、定时轮询与分页拉取）
  5. **流式执行引擎**
     - **SeaTunnel Engine 3.0.0**：平台原生分布式流执行引擎，提供分布式 Checkpoint 与 Exactly-Once 语义保障。

---

### 4. 文件数据同步（传输方式：RESTful API / S3 协议 / 文件传输协议）
* **支持类型**：
  1. **对象存储**（传输方式：S3 协议 / RESTful API）
     - **MinIO**：支持作为【来源】和【去向】（平台内置湖存储底座与外部对象存储）
     - **Amazon S3**：支持作为【来源】和【去向】
  2. **FTP 文件服务**（传输方式：FTP / SFTP 协议）
     - **FTP**：支持作为【来源】和【去向】
     - **SFTP**：支持作为【来源】和【去向】（支持密码与私钥证书认证）
  3. **平台文件资源池**（传输方式：RESTful API / S3File）
     - **本地/湖文件资源池 (FILE_RESOURCE)**：支持作为【来源】和【去向】（统一文件目录分级、上传下载与预览）
* **任务模式**：
  - 全量文件迁移同步、增量文件同步（同构文件系统按修改时间增量同步）。

---

## 二、系统引接能力全景矩阵速查表

| 引接方式 | 类别 | 具体类型 / 协议 | 传输方式 | 来源 (Source) | 去向 (Sink) | 状态 | 备注说明 |
| :--- | :--- | :--- | :--- | :---: | :---: | :---: | :--- |
| **离线数据导入** | 结构化文件 | Excel (`.xlsx`, `.xls`) | RESTful API / S3File | ✅ | - | **已支持** | 支持 Sheet 选取与表头推断 |
| | 结构化文件 | CSV (`.csv`) | RESTful API / S3File | ✅ | - | **已支持** | 支持自定义编码与分隔符 |
| | 结构化文件 | JSON (`.json`) | RESTful API / S3File | ✅ | - | **已支持** | 支持数组与对象行解析 |
| | 结构化文件 | TXT / Text | RESTful API / S3File | ✅ | - | **已支持** | 纯文本行列切分 |
| | 结构化文件 | 二进制（Binary） | RESTful API / S3File | ✅ | - | **已支持** | 支持大文件分块切片 |
| | 采报数据包 | 离线三级采报工具包 | 采报导入协议 | ✅ | - | **已支持** | 已在采报工具项目实现接入 |
| | 嵌入式数仓 | DuckDB (`.db`, `.duckdb`) | RESTful API / JDBC | ✅ | - | **已支持** | 通过 DuckDB JDBC 驱动加载 |
| | 数据库导出 | DMP 文件 (人大金仓/达梦) | 专有工具协议 | ❌ | - | **TODO** | 专有 Dump 格式解析规划中 |
| | 脚本文件 | SQL 脚本文件 | SQL 解析执行 | ❌ | - | **TODO** | 离线 SQL 文件导入待支持 |
| **数据库引接** | 关系型数据库 | MySQL | JDBC 协议 | ✅ | ✅ | **已支持** | 单表、增量、多表/整库 |
| | 关系型数据库 | Oracle | JDBC 协议 | ✅ | ✅ | **已支持** | 单表、增量、多表/整库 |
| | 关系型数据库 | PostgreSQL | JDBC 协议 | ✅ | ✅ | **已支持** | 单表、增量、多表/整库 |
| | 国产关系型 | 人大金仓（KingbaseES） | JDBC 协议 | ✅ | ✅ | **已支持** | 国产化适配，Schema 隔离支持 |
| | 国产关系型 | 达梦数据库（Dameng DM8） | JDBC 协议 | ✅ | ✅ | **已支持** | 国产化适配，大小写兼容 |
| | 国产关系型 | 海量数据库（Vastbase） | JDBC 协议 | ✅ | ✅ | **已支持** | 国产化适配，openGauss 协议 |
| | 国产关系型 | ZeoneDB-D | JDBC 协议 | ✅ | ✅ | **已支持** | 国产关系型适配 |
| | 通用驱动 | 通用 JDBC 连接器 | JDBC 协议 | ✅ | ✅ | **已支持** | 支持任意 JDBC 数据库驱动 |
| | 非关系型 | Elasticsearch | RESTful API (HTTP) | ✅ | ✅ | **已支持** | 索引映射及批量读写 |
| | 实时数仓 | Apache Doris | HTTP Stream Load / JDBC | ✅ | ✅ | **已支持** | 高吞吐 Stream Load 导入 |
| | 实时数仓 | StarRocks | Stream Load / JDBC | ❌ | ✅ | **已支持** | 支持流式 Sink 及 JDBC 写入 |
| **实时数据引接** | 变更捕获 | MySQL-CDC | Binlog 日志流 | ✅ | ❌ | **已支持** | 实时增量解析与断点续传 |
| | 变更捕获 | PostgreSQL-CDC | WAL / Replication Slot | ✅ | ❌ | **已支持** | 逻辑复制槽实时增量解析 |
| | 消息队列 | Kafka | TCP / Kafka 协议 | ✅ | ✅ | **已支持** | 实时流式读写 |
| | 搜索引擎 | Elasticsearch | RESTful API (HTTP) | ✅ | ✅ | **已支持** | 实时流式索引同步 |
| | 实时数仓 | Apache Doris | HTTP Stream Load | ❌ | ✅ | **已支持** | 实时流写入 |
| | 实时数仓 | StarRocks | Stream Load | ❌ | ✅ | **已支持** | 实时流写入 |
| | 接口数据 | HTTP / API 轮询 | HTTP / RESTful API | ✅ | ❌ | **已支持** | 支持 GET/POST、轮询与分页拉取 |
| | 执行引擎 | SeaTunnel Engine 3.0.0 | Zeta 分布式引擎 | - | - | **已支持** | 原生流引擎，Exactly-Once 保证 |
| **文件数据同步** | 对象存储 | MinIO | S3 协议 / RESTful API | ✅ | ✅ | **已支持** | 支持全量与增量同步 |
| | 对象存储 | Amazon S3 | S3 协议 | ✅ | ✅ | **已支持** | 支持全量与增量同步 |
| | 文件服务 | FTP | FTP 协议 | ✅ | ✅ | **已支持** | 支持主动与被动模式 |
| | 文件服务 | SFTP | SSH / SFTP 协议 | ✅ | ✅ | **已支持** | 支持密码与私钥认证 |
| | 湖文件管理 | 本地/湖文件池 (FILE_RESOURCE) | RESTful API / S3File | ✅ | ✅ | **已支持** | 目录分级、上传下载与预览 |
