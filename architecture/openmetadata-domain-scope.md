# OpenMetadata Domain 读写范围

SeaTunnel Web 在「探查引擎管理 → OpenMetadata 配置」复用 `defaultDomainFqn` 作为可选的单 Domain 范围。此配置是 Web 应用侧的读写限制，OpenMetadata Bot 仍需具备相应的管理员权限。

- 留空时，SeaTunnel Web 不按 Domain 限制 OpenMetadata 资产；新建数据源服务不会自动关联 Domain。
- 选择 Domain 后，资产列表、详情、样例、画像和血缘只返回该 Domain 内的对象。对象有直接 Domain 时按对象的 Domain 判定；对象未关联 Domain 时按其所属服务的 Domain 判定。未关联所选 Domain 的服务、对象和摄入管道不可由 Web 查询或修改。
- 受范围约束的写操作包括数据源服务更新/删除、数据表元数据及 Domain 更新、摄入管道配置和运行操作。新建数据源服务会自动归入所选 Domain。
- 健康检查、连接配置和 Domain 选项属于控制面，不受资产范围过滤。
- 数据库存量聚合及 OpenMetadata 分页/画像缓存按连接配置版本和 Domain 范围分区，切换配置不会复用另一范围的缓存。
- SeaTunnel 本地数据传输任务不因 OpenMetadata Domain 范围而被拒绝；本地任务事件和列血缘仍按既有流程处理。

后端在 `OpenMetadataRestClient` 处统一执行范围判定。OpenMetadata 读写继续使用官方 Java SDK 2.0.4；该版本未提供对应 typed method 的调用只使用 SDK 自带网络客户端。
