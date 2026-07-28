# Amazon S3 / MinIO 二进制文件同步设计

## 1. 范围与版本基线

本设计面向 SeaTunnel Web 的 `FILE_SYNC` 模式，目标运行时固定为 SeaTunnel Engine 2.3.13。

首版范围：

- Amazon S3 与 MinIO 均支持 Source 和 Sink；
- 只同步 bucket/prefix 下的二进制对象，不引入表、字段、Schema 或 SQL；
- FTP、SFTP、S3、MINIO 四种文件数据源可以任意组合进行全量复制；
- 复用数据源 CRUD、连接测试、文件 Catalog、FILE_SYNC 保存与预览接口；
- Catalog 只执行读操作，不创建、删除或覆盖对象。

SeaTunnel 2.3.13 的 S3File 支持 binary Source/Sink，但增量 update 未覆盖 S3File。因此只要任一端是 S3 或 MINIO，任务必须使用 `FULL`；FTP/SFTP 现有的同数据源增量语义保持不变。

## 2. 固定标识与模块

| Web 类型 | `dbType` | `connectorType` | `pluginName` | SeaTunnel Connector |
| --- | --- | --- | --- | --- |
| Amazon S3 | `S3` | `S3File` | `S3File` | `S3File` |
| MinIO | `MINIO` | `S3File` | `S3File` | `S3File` |

Web 侧实现位于 `seatunnel-web-datasource-s3`。两个 Processor 共享一个 `S3FileHoconBuilder`，但连接参数、认证约束和动态表单保持独立。

## 3. 连接参数与认证

公共参数：

| 参数 | 约束 |
| --- | --- |
| `endpoint` | 必须是无用户信息、查询串和 fragment 的 HTTP(S) URL |
| `region` | 必填；MinIO 默认 `us-east-1` |
| `bucket` | 只接受 bucket 名称，不含协议、冒号或路径 |
| `basePath` | UI 绝对路径，默认 `/`，作为该数据源可访问的根 Prefix |
| `connectTimeoutMs` | 正整数，默认 10000 |
| `requestTimeoutMs` | 正整数，默认 30000 |

Amazon S3：

- `STATIC`：要求 Access Key 与 Secret Key；
- `INSTANCE_PROFILE`：使用实例角色，不接受也不持久化 Access Key/Secret Key；
- path-style 默认为关闭，可为兼容服务显式开启。

MinIO：

- 固定 `STATIC`，要求 Access Key 与 Secret Key；
- 固定 path-style=true；
- 根据 endpoint 的 `http`/`https` 协议自动生成 S3A SSL 配置。

Access Key 与 Secret Key 的表单类型均为密码。参数对象的 `toString()`、日志和校验异常不得包含密钥值。连接失败只返回服务端错误类型和非敏感上下文，不拼接认证参数。

连接测试通过 AWS SDK for Java v1.12.692 发起最大一条结果的 `ListObjectsV2`，同时验证 endpoint、认证、bucket 与 `ListBucket` 权限。Web 连接测试成功只说明 Web 到对象存储的网络与权限可用，不代表 Engine 已具备 Connector 或依赖。

最小权限为目标 bucket 上的 `s3:ListBucket`；真实 Source 运行还需要 `s3:GetObject`，真实 Sink 运行需要 `s3:PutObject`。若使用事务临时路径，生产策略还应覆盖该临时 Prefix 的读写与清理权限。

## 4. 路径和 Catalog

页面路径统一表示为 `/prefix/...`，不直接展示 `s3a://bucket`。`basePath` 是数据源根边界：

- 拒绝 `..`、反斜杠、相对路径和越过根 Prefix 的访问；
- UI `/` 映射到当前数据源的根 Prefix，而不是 bucket 物理根；
- 目录请求使用 `ListObjectsV2`、`delimiter=/` 和 continuation token 分页；
- common prefix 映射为目录，object 映射为文件；
- 只返回当前层级；空 Prefix 返回空列表；
- object key 与 common prefix 必须再次经过根边界校验。

接口仍为：

```text
GET /api/v1/data-source/catalog/files/{id}?path=/prefix
```

Catalog 不创建“目录占位对象”，也不执行 PutObject、CopyObject 或 DeleteObject。

## 5. S3File HOCON

所有 Source/Sink 固定：

```hocon
bucket = "s3a://<bucket>"
fs.s3a.endpoint = "<endpoint>"
file_format_type = "binary"
```

Amazon S3 静态认证 Source 示例：

```hocon
S3File {
  path = "/incoming"
  bucket = "s3a://archive-bucket"
  fs.s3a.endpoint = "https://s3.cn-north-1.amazonaws.com.cn"
  fs.s3a.aws.credentials.provider = "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
  access_key = "***"
  secret_key = "***"
  file_format_type = "binary"
  file_filter_pattern = ".*\\.(zip|bin)"
  binary_chunk_size = 1048576
  binary_complete_file_mode = true
  hadoop_s3_properties {
    "fs.s3a.path.style.access" = "false"
    "fs.s3a.connection.ssl.enabled" = "true"
  }
}
```

实例角色模式使用：

```hocon
fs.s3a.aws.credentials.provider = "com.amazonaws.auth.InstanceProfileCredentialsProvider"
```

并完全省略 `access_key` 与 `secret_key`。

MinIO Sink 示例：

```hocon
S3File {
  path = "/archive"
  tmp_path = "/archive-seatunnel-tmp"
  bucket = "s3a://seatunnel-data"
  fs.s3a.endpoint = "http://minio.internal:9000"
  fs.s3a.aws.credentials.provider = "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
  access_key = "***"
  secret_key = "***"
  file_format_type = "binary"
  is_enable_transaction = true
  sink_columns = ["path", "content"]
  hadoop_s3_properties {
    "fs.s3a.path.style.access" = "true"
    "fs.s3a.connection.ssl.enabled" = "false"
  }
}
```

Source 支持路径、`file_filter_pattern`、`filename_extension`、`binary_chunk_size` 与 `binary_complete_file_mode`。Sink 固定事务写入、临时路径和 `path/content` 列。Builder 收到 `INCREMENTAL` 时必须直接报错，不生成伪增量配置。

## 6. FILE_SYNC 页面与后端约束

- 页面数据源分组为“文件与对象存储”，开放 FTP、SFTP、S3、MINIO；
- S3/MINIO 均映射到 `S3File`，FTP/SFTP 的 Connector 名称和已保存节点结构不变；
- 任一端选择 S3/MINIO 时，前端自动切回 `FULL` 并禁用增量；
- 保存/预览时后端再次检查：增量只允许 FTP/SFTP 且必须为同一个数据源；
- 页面标题、路径选择与接口说明使用“文件/对象存储”通用表述。

## 7. Engine 部署

SeaTunnel Web 使用 `com.amazonaws:aws-java-sdk-s3:1.12.692` 完成连接测试和 Catalog。每个 SeaTunnel Engine 2.3.13 节点必须另行部署：

1. SeaTunnel 2.3.13 的 `connector-file-s3`；
2. `${SEATUNNEL_HOME}/lib/hadoop-aws-3.1.4.jar`；
3. `${SEATUNNEL_HOME}/lib/aws-java-sdk-bundle-1.12.692.jar`。

所有节点的 Jar 版本和文件摘要应一致。修改后重启 Engine，并用真实 Source/Sink 任务验证类加载、网络、认证和对象权限。只在 Web POM 中加入 AWS SDK 不能替代 Engine 部署。

## 8. 生命周期、回滚与验收

发布顺序：

1. 备份数据库并执行 `V1_0_7__init_s3_minio_file_connector_param.sql`；
2. 部署 Web 模块和前端；
3. 在全部 Engine 节点部署 S3 Connector 与依赖并重启；
4. 创建测试数据源，验证连接与根 Prefix 浏览；
5. 分别运行 S3 Source、MinIO Sink 和跨类型全量任务。

回滚时先停用引用 S3/MINIO 的任务，再回滚 Web/前端版本和 Engine Connector。Flyway 迁移不改名、不删除、不执行 `flyway repair`；如需撤销参数元数据，使用后续唯一版本的补偿迁移。

自动化验收：

- S3 静态/实例角色、MinIO 静态参数校验与敏感信息脱敏；
- endpoint、bucket、prefix、`..`、反斜杠和根边界；
- Catalog 虚拟目录、文件、空 Prefix、分页与认证/权限失败；
- S3 Source、IAM Source、MinIO Sink、跨类型全量配置；
- 所有涉及 S3/MINIO 的增量请求均被前后端拒绝；
- 两个 Processor、一个 S3File Builder、Source/Sink 分析和回显；
- FTP/SFTP 原有保存、路由与增量行为不回归。

真实环境验收：

- S3 → 本地/FTP/SFTP/MinIO 的二进制 Source 任务成功；
- 本地/FTP/SFTP/S3 → MinIO/S3 的二进制 Sink 任务成功；
- 大文件、空 Prefix、嵌套 Prefix、同名覆盖策略和失败重试满足运行要求；
- 日志、API 错误与任务预览不泄露密钥。

若没有可用的 AWS/MinIO 和 SeaTunnel 2.3.13 Engine，真实 Source/Sink E2E 必须记录为“未执行”，不得用单元测试或 Web 连接测试替代运行时验收。
