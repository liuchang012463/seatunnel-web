# Sprint 6 文件级改造计划：验收加固

## 目标

完成当前三库范围的发布前加固：固定版本健康可见性、外部不可用时的安全状态、日志脱敏、分页/导出回归和全量 metadata 模块回归。Oracle、Dameng、Kingbase 仍按用户决定延期，不在本 Sprint 伪造 Connector 或验收通过。

## 改造文件

- `seatunnel-web-api/src/main/java/org/apache/seatunnel/web/api/metadata/client/OpenMetadataHealth.java`
  - 定义由 OpenMetadata 1.12.10 返回的 Server/managed-ingestion 健康投影。
- `.../metadata/client/OpenMetadataClient.java`
  - 增加 `health()` 读操作；业务层仍不暴露 Airflow client。
- `.../metadata/client/OpenMetadataRestClient.java`
  - 仅调用 `/api/v1/system/version` 和 `/api/v1/services/ingestionPipelines/status`，读取实际版本/状态；不记录 token、连接参数或响应正文。
- `.../metadata/MetadataIntegrationHealthService.java`
  - 将健康状态归一为 `UP/DOWN/DISABLED`，严格比较 Server `1.12.10` 与已验证 ingestion/managed `1.12.10.0`。
- `.../controller/MetadataIntegrationHealthController.java`
  - 提供运维接口 `GET /api/v1/metadata-integration/health`，沿用统一 `Result` 包装，不向普通产品页面下发 OM 凭据。
- `seatunnel-web-spi/src/main/java/org/apache/seatunnel/web/spi/bean/vo/MetadataIntegrationHealthVO.java`
  - 健康接口 typed DTO，只有版本和状态，不含 token/password/原始异常。
- `seatunnel-web-api/src/main/java/org/apache/seatunnel/web/api/service/impl/DataSourceServiceImpl.java`
  - 将列表查询失败日志从完整 DTO 改为白名单字段，避免把 `connectionParams`/password 写入日志。

## 验证文件

- `.../client/OpenMetadataRestClientTest.java`
  - 验证健康检查只访问 OM `/api` 精确路径，并返回版本兼容信息。
- `.../metadata/MetadataIntegrationHealthServiceTest.java`
  - 覆盖健康、版本不匹配、禁用和不暴露异常场景。
- Sprint 0–5 已有的 reconciler、status、cursor、inventory、topology、export、exploration 测试继续作为回归集合。

## 固定范围与不做事项

- OpenMetadata Server 不升级，固定 `1.12.10`。
- Airflow 侧不被 SeaTunnel Web 直接调用；managed/ingestion 仍固定已验证的 `1.12.10.0`（`1.12.10.x`）。
- MySQL、PostgreSQL、Doris 是本轮实际支持范围；Doris 已使用 OM 中的 `doris_test` 测试源完成闭环。
- Oracle、Dameng、Kingbase 后续与一等 Connector 一起实现；当前只保留既有数据源插件和明确的 `CONNECTOR_NOT_SUPPORTED/DEFERRED` 语义。
- 不新增元数据明细镜像表，不引入第二套数据源 CRUD，不修改无关 datasource plugin。

## 完成门槛

后端全 metadata 测试集、前端 TypeScript/Jest、shell 静态检查和 `git diff --check` 通过；健康接口在版本不兼容/OM 不可用时返回稳定状态；日志扫描不得出现把 DataSource DTO 整体写出的路径。
