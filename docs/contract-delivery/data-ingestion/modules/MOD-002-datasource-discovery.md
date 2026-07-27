# MOD-002 数据源管理与数据探查

## 1. 目标

复用 SeaTunnel-Web 的数据源连接与运行配置能力，集成 OpenMetadata 形成统一的数据目录、清查、画像和质量探查视图。

## 2. 指标与投标模块映射

- 指标：F-02.03、F-04.01、F-04.02、F-13.01。
- 投标模块：多源数据表探查、源业务系统数据清查、数据湖管理、数据源接入探测。

## 3. 当前能力和证据

- `DataSourceController` 已提供数据源增删改查、连接测试、批量测试和 JDBC 驱动上传。
- 数据源插件已覆盖 JDBC、MySQL、Oracle、PostgreSQL、Doris、Kingbase、Dameng、Kafka，并包含 MySQL/PostgreSQL CDC。
- `DataSourceProcessor` 与各 Catalog 提供库表字段和部分预览能力。
- OpenMetadata 尚未接入，当前统一目录、画像、质量和跨源清查属于缺口。

## 4. 实施边界

### Reuse

- 复用现有数据源凭据、连接测试、Catalog 和任务引用关系。
- 复用 OpenMetadata 官方数据库连接器、目录、画像、质量和搜索。

### Modify

- 为数据源增加 OpenMetadata Service/Entity 映射和同步状态。
- 统一“数据源连接配置”与“资产元数据”的页面跳转和错误提示。

### Add

- 数据源与 OpenMetadata 实体映射、同步任务、状态回写和清查统计。
- 单位、系统、模型等项目特有属性的映射规则。

### Integrate

- OpenMetadata Ingestion、REST API/SDK、事件或 WebHook。

### Limited or Mock

- 缺失注释智能推断一期只生成候选建议并要求人工确认。

### Out of scope

- 不复制 OpenMetadata 全量数据目录 UI。
- 不直接读写 OpenMetadata 内部数据库。
- 不用 OpenMetadata 代替 SeaTunnel 连接和数据搬运。

## 5. 主要流程与接口边界

```text
SeaTunnel-Web 注册/测试数据源
→ 建立 OpenMetadata Service 映射
→ OpenMetadata Ingestion 扫描
→ 平台展示目录/画像/质量摘要
→ 跳转或 API 钻取完整资产详情
```

SeaTunnel-Web 管连接与任务使用；OpenMetadata 管通用资产元数据事实。

## 6. 依赖和实施顺序

1. MOD-007 提供 OpenMetadata 服务账号和密钥。
2. 完成一个关系库连接器、画像权限、统一 SSO 衔接和 REST API PoC。
3. 再扩展到合同要求的数据源类型和清查报表。

## 7. 验收场景

- 两类数据源完成注册、连接测试和 OpenMetadata 扫描。
- 展示库、表、字段、样例/画像、质量结果和清查统计。
- 新增、修改、删除资产后可追踪同步结果。
- 数据源停用后禁止新任务引用，并保留历史资产/任务审计。

## 8. 风险与未验证项

- OpenMetadata 版本、搜索后端、部署资源和画像查询压力。
- Connector 对所有国产数据库的覆盖程度。
- 凭据复用需避免跨系统明文传递和双份不受控存储。
