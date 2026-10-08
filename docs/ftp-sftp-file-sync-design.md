# FTP/SFTP 二进制文件同步设计

## 1. 范围与标识

本功能面向远程目录中的二进制文件，不建立表、字段或 SQL 概念。固定标识如下：

| 协议 | `DbType` | SeaTunnel 2.3.13 Connector | 角色 |
|---|---|---|---|
| FTP | `FTP` | `FtpFile` | Source / Sink |
| SFTP | `SFTP` | `SftpFile` | Source / Sink |

SeaTunnel 2.3.13 与 3.0.0 均使用 `sync_mode=update`、`target_path`、`update_strategy` 和 `compare_mode` 实现增量更新（[2.3.13 FtpFile](https://seatunnel.apache.org/docs/2.3.13/connectors/source/FtpFile/)、[2.3.13 SftpFile](https://seatunnel.apache.org/docs/2.3.13/connectors/source/SftpFile/)、[3.0.0 FtpFile](https://seatunnel.apache.org/docs/3.0.0/connectors/source/FtpFile/)、[3.0.0 SftpFile](https://seatunnel.apache.org/docs/3.0.0/connectors/source/SftpFile/)）。下文这些字段适用于两个版本；较早草稿中的 `read_update_info`、`only_add` 和 `file_details_info` 不属于这两个版本的官方 FtpFile/SftpFile 参数。

任务模式固定为 `FILE_SYNC`，只用于批任务。页面采用来源端点、文件策略、去向端点三段式设计，不进入原单表/多表页面。

## 2. 生命周期

1. 新建 FTP/SFTP 数据源，配置主机、端口、用户名、密码和可访问根目录。
2. Web 节点使用 Commons Net 或 JSch 登录并列出根目录，完成连接测试。
3. 文件同步页选择来源/去向数据源，并通过目录浏览接口选路径。
4. 配置文件名正则、扩展名、二进制分块和完整文件模式。
5. 预览 HOCON，保存 `FILE_SYNC` 定义；手动执行或配置 Cron。
6. SeaTunnel Engine 使用 `FtpFile`/`SftpFile` Source 和 Sink 传输二进制流。

## 3. 同步语义

- 全量：支持 FTP/SFTP 任意组合。
- 增量：仅支持二进制模式，使用 `sync_mode=update`、`target_path`、`update_strategy=distcp|strict` 和 `compare_mode=len_mtime|checksum`；`checksum` 只允许与 `strict` 搭配。当前 Web 只允许来源和目标使用同一数据源，避免 Engine 无法读取目标端认证配置。
- 目标端只复制新增或长度/修改时间发生变化的文件，不删除已有文件。
- `file_format_type` 固定为 `binary`，Sink 开启事务并使用独立临时目录。
- 数据源根目录是浏览安全边界；拒绝 `..` 和边界外绝对路径。

## 4. 安全与限制

- 密码不会写入日志或 `toString()`。
- FTP 固定进入二进制传输模式，支持主动/被动连接及远端校验开关。
- Web 的 SFTP 表单目前只支持密码认证；3.0.0 Connector 还支持私钥 `keyfile`，但此能力尚未接入 Web。Web 目录浏览当前关闭主机密钥严格校验，因此生产环境应通过受控网络和可信主机接入。
- Web 直连测试成功只代表 Web 节点可访问远端；实际执行仍要求所有 SeaTunnel Engine 节点部署与所选 Engine 版本匹配的 `connector-file-ftp`/`connector-file-sftp` 插件并具备网络权限。

## 5. 验收清单

- [ ] FTP、SFTP 数据源表单可生成且密码不回显。
- [ ] 连接测试能够登录并列出配置根目录。
- [ ] 目录浏览不能越过数据源根目录。
- [ ] FTP→FTP、FTP→SFTP、SFTP→FTP、SFTP→SFTP 全量任务可生成 HOCON。
- [ ] 跨数据源增量任务在保存前被拒绝。
- [ ] HOCON 中固定为 `binary`，且不包含 Web 内部字段。
- [ ] 手动执行和 Cron 调度均可保存。
- [ ] 页面无数据库表、字段映射和 SQL 控件。
- [ ] 在真实 SeaTunnel 2.3.13 和 3.0.0 Engine 上分别完成至少一次二进制文件校验（大小及哈希一致）。

