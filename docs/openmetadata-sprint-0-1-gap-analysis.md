# OpenMetadata 数据源探查 MVP：现状差异与 Sprint 0/1 文件级计划

> 审计基线：`develop` @ `dcb34f8fa2988928edcb43e56dfd75414d514999`  
> 审计日期：2026-08-25  
> 设计基线：`docs/seatunnel_openmetadata_mvp_design_for_codex_om_1.12.10.md`  
> 固定版本：OpenMetadata Server **1.12.10**；Airflow ingestion/managed APIs 仅允许验证通过并锁定的 **1.12.10.x** patch build。

## 1. 审计边界和不可变约束

- 复用现有 `/api/v1/data-source`、`DataSourceService`、DAO、插件 SPI 和 `/data-source` 页面；不创建第二套数据源管理。
- SeaTunnel Web 只调用 OpenMetadata Server，不直接调用 Airflow；前端不直接调用 OpenMetadata 或 Airflow。
- OpenMetadata Database/Schema/Table/Column/Profile 不落 SeaTunnel 明细镜像表。
- 数据预览继续复用 `DataSourceCatalogController` 的 Top20 能力。
- OpenMetadata Server 不升级、不切换，始终为 1.12.10；不得拿 `main` 或 1.13.x 契约实现。
- Sprint 0 Gate 未通过前，不进入大规模 UI 或 Sprint 2+ 状态机开发。
- 用户范围调整：当前硬 Gate 为 MySQL/PostgreSQL/Doris；Oracle、Kingbase、Dameng 明确标记 `DEFERRED` 并后续补齐，不阻塞 Sprint 1；不得以 Postgres/CustomDatabase 冒充完整支持。

## 2. develop 实际结构

### 2.1 DataSource 主链路

| 层 | 实际文件与行为 | 判断 |
|---|---|---|
| Entity | `seatunnel-web-dao/.../entity/DataSource.java` 映射唯一的 `t_seatunnel_web_datasource`；字段含 `name`、字符串 `dataSourceUnit`、`dbType`、两份连接 JSON、连接/生命周期状态和环境 | 唯一主数据入口已存在，必须增量扩展 |
| DTO | `seatunnel-web-spi/.../dto/DataSourceDTO.java` 同时承载创建、更新、分页过滤；`dataSourceUnit` 是字符串，没有 `businessSystemId` | 需兼容性扩展，不另造 DataSource 请求体系 |
| VO | `seatunnel-web-spi/.../vo/DataSourceVO.java` 直接复制 Entity 字段并补 `jdbcUrl/environmentName`；无单位/系统 ID、名称和 metadata 状态 | Sprint 1 先补主数据派生字段；metadata 状态留后续 Sprint |
| Controller | `DataSourceController.java` 保留 CRUD、分页、连接测试、批量、状态、单位字符串列表和驱动上传；基路径为 `/api/v1/data-source` | 路径与统一 `Result/PaginationResult` 可直接复用 |
| Service | `DataSourceServiceImpl.java` create/update 均同步校验连接并写字符串单位；update 会拒绝被任务引用的数据源；delete 当前校验任务引用后立即物理删除 | Sprint 1 不改变不相关语义；OM desired-state 删除切换属于 Sprint 2 |
| DAO | `DataSourceDaoImpl` 使用 MyBatis-Plus 单表分页，按字符串单位过滤；`DataSourceMapper` 用 DISTINCT SQL 提供单位下拉 | 主数据化后改为 system/unit 关联查询或批量装配，旧接口仅作兼容 |
| ID/审计 | `BaseEntity` 使用 `IdType.INPUT` 和 `CodeGenerateUtils.genCode()`，时间由 `initInsert/initUpdate` 填充 | 新表禁止 AUTO_INCREMENT，服从现有 ID 约定 |

补充现状：

- `selectById` 当前直接返回 `DataSource`，不是脱敏详情 VO；本次 Sprint 1 不借机重构整个 API，但 OM 日志/绑定不得复制或输出密码、token。
- `GET /{id}` 的 Swagger summary 误写为 `deleteDataSource`，属于可在触达该文件时修正的小问题，不扩大重构。
- DataSource 核心 Service 缺少覆盖 create/update/delete/校验的直接单元测试；DAO 仅有 `DataSourceDaoImplTest`。

### 2.2 Catalog / Top20

`DataSourceCatalogController` 已提供：

- `GET /api/v1/data-source/catalog/list/{id}`；
- `POST .../column/{id}`；
- `POST .../getTop20Data/{id}`；
- `POST .../count/{id}`；
- 以及文件、HTTP、SQL 模板能力。

`DataSourceCatalogServiceImpl` 通过现有 `DataSourceProcessor -> DataSourceCatalog/JdbcCatalog` 读取真实源库。设计要求的预览 facade 后续只应校验 OM Table 归属并委托此 Service，不能重写 JDBC 预览，也不能改用 OM SampleData。

### 2.3 DbType 与插件

`seatunnel-web-spi/.../DbType.java` 已含六种 P0：

- `MYSQL`
- `POSTGRE_SQL`（注意项目现有拼写）
- `ORACLE`
- `DORIS`
- `DAMENG`
- `KINGBASE`

同时含非 MVP 类型。Sprint 1 不修改枚举；后续 Metadata Adapter Registry 只为六种 P0 注册能力并显式把 `POSTGRE_SQL` 映射到 OM `Postgres` connector。

### 2.4 数据库脚本

- Flyway 实际目录：`seatunnel-web-dao-plugin/seatunnel-web-dao-mysql/src/main/resources/db/migration/mysql`。
- develop 源码原先最高版本是 **`V1_0_14`**，但当前部署 MySQL 库已由 `codex/lake-ingestion-management-design` 应用 `V1_0_15`～`V1_0_19`。
- `V1_0_11` 以及已应用的 Lake `V1_0_15`～`V1_0_19` 都是历史事实，绝对不能复用、改名或修改；本分支保留这些迁移原文仅用于 Flyway 解析既有历史。
- OpenMetadata 主数据与 Binding 迁移改用 **`V1_0_20`**，后续新迁移从 `V1_0_21` 继续。
- `tools/database/mysql/legacy_full_init.sql` 是 legacy 全量初始化脚本；Flyway 是已有环境升级事实源。Sprint 1 是否同步 legacy 脚本应单独验证启动方式，不能只改 legacy 而漏 Flyway。

### 2.5 前端数据源管理

- 路由仍是 `/data-source`，页面位于 `seatunnel-web-ui/src/pages/data-source`。
- 现有 UI 是卡片分组布局，复用 `PageHeader`、`SearchBar`、`DataSourceCard`、Ant Design 表单/Modal 与当前 Tailwind/LESS 色彩、圆角和间距。
- `DynamicDataSourceForm` 当前强制填写 `dataSourceUnit`，使用 `DataSourceUnitSelect`；后者来自 `GET /api/v1/data-source/units` 的字符串集合。
- `index.tsx` 的分页过滤仍提交字符串 `dataSourceUnit`；Card 也只展示字符串单位。
- API 集中在同目录 `service.ts`，类型集中在 `types.ts`；已有 `service.test.ts`、registry/form-utils 测试，但没有主数据级联测试。

结论：前端必须在现有页面和弹窗内将“单位字符串”替换为“单位 -> 业务系统”级联选择，并保留历史 `businessSystemId == null` 的“待归属”展示；不能新增第二个 DataSource 录入页面。

## 3. OpenMetadata 1.12.10 真实部署与契约

### 3.1 当前部署 BOM

来源：`/mnt/lc/open_metadata/docker-compose.yml`、运行容器和镜像内包。

| 组件 | 当前实际值 | Gate 判断 |
|---|---|---|
| Server | `openmetadata/server:1.12.10`，运行 API 返回 `1.12.10`，revision `3bc20e698abf222742908c2aa5d0eaa736e7cfbd` | 版本符合，禁止改动 |
| Server image digest | `sha256:f2fb66b1ea6420a84c986e1035a55308948807352efe9d87ce510612d0dd404c` | 应写入 Sprint 0 BOM |
| Ingestion image | `openmetadata/ingestion:1.12.10-kingbase` | 当前三库 Gate 已验证；部署目录缺可复现 Dockerfile/BOM 仍是发布风险 |
| `openmetadata-ingestion` | `1.12.10.0` | 当前三库 Gate 已验证并固定，禁止自行切到 `.1` |
| `openmetadata-managed-apis` | `1.12.10.0` | 同上 |
| Airflow | `3.1.5` | 必须纳入真实 deploy/run/kill/delete 回归 |
| Kingbase connector | Python connector module中未发现 `database/kingbase` | 镜像 tag 不能证明 Connector Gate 通过 |
| Dameng connector | 未发现一等 Connector/BOM | Gate 未满足 |

### 3.2 IngestionPipeline REST（1.12.10 JAR 内 `assets/swagger.json`）

服务部署的 `/api` 是 base path；OpenAPI path 以下列 `/v1` 开头，因此实际 HTTP 路径是 `/api/v1/...`。

| 动作 | 1.12.10 精确路径 | 请求/响应 |
|---|---|---|
| list | `GET /api/v1/services/ingestionPipelines` | `IngestionPipelineList` |
| get by FQN | `GET /api/v1/services/ingestionPipelines/name/{fqn}` | `IngestionPipeline` |
| create | `POST /api/v1/services/ingestionPipelines` | body `CreateIngestionPipeline` |
| upsert | `PUT /api/v1/services/ingestionPipelines` | body `CreateIngestionPipeline` |
| delete | `DELETE /api/v1/services/ingestionPipelines/{id}?hardDelete=...` | 无 Airflow 直连 |
| deploy | `POST /api/v1/services/ingestionPipelines/deploy/{id}` | `PipelineServiceClientResponse` |
| trigger | `POST /api/v1/services/ingestionPipelines/trigger/{id}` | **无 request body**；返回 `PipelineServiceClientResponse` |
| kill | `POST /api/v1/services/ingestionPipelines/kill/{id}` | `PipelineServiceClientResponse` |
| latest/history status | `GET /api/v1/services/ingestionPipelines/{fqn}/pipelineStatus?startTs=&endTs=&limit=` | `PipelineStatusList`；无时间范围默认最近 5 次 |
| one status | `GET /api/v1/services/ingestionPipelines/{fqn}/pipelineStatus/{id}` | `PipelineStatus` |
| orchestrator health through OM | `GET /api/v1/services/ingestionPipelines/status` | 由 OM 转发/检查，不由 SeaTunnel 直连 Airflow |

关键契约差异：设计伪接口 `runPipeline(id, runtimeConfig)` 不能直接照搬。1.12.10 `trigger/{id}` 没有 runtime body；Database 粒度 Profiler 必须先按 1.12.10 DTO 更新 pipeline 的 `sourceConfig.config.databaseFilterPattern`，再 trigger。

### 3.3 DTO / JSON Schema（镜像内 `openmetadata-spec-1.12.10.jar`）

`CreateIngestionPipeline` 必填：

- `name`
- `service`
- `pipelineType`
- `sourceConfig`
- `airflowConfig`

`sourceConfig` 结构是 `{ "config": <oneOf pipeline schema> }`，不是扁平对象。`PipelineStatus.pipelineState` 枚举为：`queued/success/failed/running/partialSuccess/stopped`；SeaTunnel 后续 mapper 必须显式处理 `partialSuccess` 和 `stopped`。

Metadata schema 的 1.12.10 默认值与设计目标不一致：

- `markDeletedSchemas=false`
- `markDeletedDatabases=false`
- `includeViews=true`

所以 Adapter 必须显式发送 `markDeletedTables=true`、`markDeletedSchemas=true`、`markDeletedDatabases=true`、`includeTables=true`、`includeViews=false`，不能依赖默认值。

Profiler schema 的数据库过滤字段确认为 `databaseFilterPattern`；采样字段确认为 `profileSampleType` 和 `profileSample`，不是设计示意配置名。

### 3.4 1.12.10 Connector 扩展点

1.12.10 release 源码确认：

- 动态入口约定：`metadata.ingestion.source.{service_type}.{service_name}.service_spec.ServiceSpec`；
- `BaseSpec` 定义 metadata/profiler/sampler/test/connection 等 class path；
- SQL 数据库应使用 `DefaultDatabaseSpec`，其默认提供 `SQAProfilerInterface`、`SQASampler`、`SQATestSuiteInterface`；
- Metadata source 基类为 `DatabaseServiceSource` / `CommonDbSourceService`；
- 每个一等 connector 至少需要 `connection.py`、`metadata.py`、`service_spec.py`，并配合 Server 侧 connection JSON Schema/generated models/注册与 secret converter；
- 官方 MySQL/Postgres 使用 `DefaultDatabaseSpec`；Doris 也使用 `DefaultDatabaseSpec`。

因此 Kingbase/Dameng 不能只靠 JDBC URL 或“兼容 PostgreSQL”宣称完成；必须在固定 1.12.10 Server + ingestion 依赖栈验证 Metadata、反射/类型/约束与默认 Profiler/Sampler 全链路。

## 4. 现状与设计差异清单

| ID | 设计要求 | develop / 部署现状 | Sprint |
|---|---|---|---|
| GAP-001 | Unit、BusinessSystem 独立主数据 | 只有 DataSource 上自由字符串 `dataSourceUnit` | 1 |
| GAP-002 | Unit 1:N System 1:N DataSource | 无主表、无 `business_system_id` | 1 |
| GAP-003 | 历史数据兼容、显示“待归属” | 新增/修改和前端都强制字符串单位 | 1 |
| GAP-004 | MetadataBinding 一源一条、三组正交状态/版本 | 无表、Entity、DAO、enum、service | 1 |
| GAP-005 | 稳定技术名 `st_ds_{id}` | 无 naming helper/test | 1 |
| GAP-006 | 新迁移服从现有版本 | Lake 分支已占用 V1_0_15～V1_0_19；主数据迁移若继续使用 V1_0_15 会触发 checksum mismatch | 1：V1_0_20 |
| GAP-007 | 1.12.10 精确 BOM 可复现 | Server 固定；ingestion `.0` 已运行，但自定义镜像缺 Dockerfile/lock/provenance | 0 |
| GAP-008 | 当前三库可重复 smoke，延期库有真实结论 | 已新增统一脚本/报告；MySQL/PostgreSQL/Doris 通过，Oracle/Dameng/Kingbase 明确延期 | 0 |
| GAP-009 | 精确 deploy/run/status/kill/delete contract | JAR/OpenAPI 已核实，但仓库尚无契约文档/测试资产 | 0 |
| GAP-010 | markDeleted 与 Profiler Profile Read 闭环 | 未形成真实六库证据；schema 默认值与设计目标不同 | 0 |
| GAP-011 | 不直接调用 Airflow | 当前 SeaTunnel Web 未调用 Airflow，符合；后续必须保持 | 全程 |
| GAP-012 | Top20 复用 Catalog | 现有能力完整，符合；后续只加 facade | 4 |
| GAP-013 | 前端保持现有风格且单一入口 | 当前卡片/Modal 风格可复用；缺主数据级联和 metadata 状态 | 1 起 |
| GAP-014 | create/update/delete desired-state 生命周期 | 当前 create/update 仅 DataSource；delete 立即删除 | 2（Sprint 1 仅准备 Binding 原语） |
| GAP-015 | 状态机测试 | DataSource Service 核心分支目前无直接覆盖 | 1 起 |

## 5. Sprint 0 文件级改造计划（Gate）

Sprint 0 不改 SeaTunnel 业务功能。先在 `luna max` 子代理中按下列边界实施并验证。

### 仓库内新增

| 文件 | 内容 |
|---|---|
| `docs/openmetadata/sprint-0-bom.md` | 固定 Server image/digest/revision、ingestion/managed `.0`、Airflow、Python、SQLAlchemy、driver/dialect 和自定义镜像 digest |
| `docs/openmetadata/openmetadata-1.12.10-api-contract.md` | 记录本次 JAR/OpenAPI 核实的 endpoint、DTO、schema 字段、真实请求/响应（脱敏） |
| `docs/openmetadata/sprint-0-connector-matrix.md` | 六库 Connect/Service/DB/Schema/Table/Column/Constraint/Table Profile/Column Profile/Top20 真实结果；Kingbase/Dameng 可为有证据的 `DEFERRED` |
| `tools/openmetadata/README.md` | smoke 前置条件、环境变量、运行和清理说明；明确只调用 OM API |
| `tools/openmetadata/smoke-test.sh` | 可重复 create/upsert/deploy/trigger/status/profile/markDeleted/kill/delete 流程；严格 fail-fast 与脱敏 |
| `tools/openmetadata/contracts/` | 1.12.10 请求模板和脱敏响应 fixture；不保存 token/业务密码 |
| `tools/openmetadata/verify-version.sh` | 校验 Server==1.12.10 且 ingestion/managed 属于锁定的 1.12.10.x 精确值 |

### 部署目录 `/mnt/lc/open_metadata`（若 Gate 需要修改）

| 文件 | 内容 |
|---|---|
| `docker-compose.yml` | 只 pin 已验证镜像 digest/tag；不改 Server 版本 |
| `images/ingestion-1.12.10/Dockerfile` | 可复现 Dameng/Kingbase ingestion 镜像，基线固定 1.12.10 |
| `images/ingestion-1.12.10/requirements.lock` | 精确锁定 ingestion/managed、SQLAlchemy、drivers/dialects |
| `extensions/{dameng,kingbase}/...` | 按 1.12.10 `ServiceSpec + DefaultDatabaseSpec + CommonDbSourceService` 扩展，禁止引用 1.13.x |
| Server extension Dockerfile/schema/generated sources（如验证确认必需） | 固定 1.12.10 Server 基线，增加一等 DatabaseConnection/ServiceType 接受能力 |

### Gate 测试顺序

1. 先冻结当前 BOM 和 endpoint contract。
2. MySQL/PostgreSQL/Doris 分别完成全链路 smoke，这三库是当前硬 Gate。
3. Oracle/Dameng/Kingbase 记录 `DEFERRED`、现状证据、缺失扩展点和后续任务；后续启用时执行同等 smoke，不能降低验收标准。
4. 已纳入当前 Gate 的每库验证一次源端对象删除后的 `markDeleted` 行为。
5. 验证 deploy、trigger、status、kill、pipeline delete 和 service recursive hard delete。
6. MySQL/PostgreSQL/Doris 三库矩阵全部有真实证据才标记 Sprint 0 通过；Oracle/Kingbase/Dameng 延期但必须有证据和补齐计划。三库任一失败不得进入 Sprint 1 实施。

## 6. Sprint 1 文件级改造计划（主数据与 Binding）

只有 Sprint 0 Gate 通过后执行。

### 数据库

| 文件 | 改造 |
|---|---|
| `.../db/migration/mysql/V1_0_20__add_metadata_master_data_and_binding.sql` | 创建 unit/system/binding，DataSource 加 nullable `business_system_id` 和索引；按旧单位 distinct 回填 Unit，不伪造默认 System；ID 不用 AUTO_INCREMENT |
| `.../db/migration/mysql/V1_0_15__init_lake_ingestion.sql` ～ `V1_0_19__add_lake_lifecycle_cold_storage.sql` | 保留 Lake 分支已经应用的历史迁移原文，不改 SQL、不新增 Lake 业务实现，仅避免既有数据库出现“applied migration not resolved locally” |
| `tools/database/mysql/legacy_full_init.sql` | 仅在确认仍为受支持的新环境初始化入口后同步最终表结构；不能代替 Flyway |

### DAO / Entity

新增：

- `DataSourceUnit.java`、`BusinessSystem.java`、`MetadataSourceBinding.java`；
- 对应 `Mapper`、`Dao`、`DaoImpl`；
- DAO 测试：唯一性、引用检查、分页/按 Unit 查询、binding 一源一条、待 reconcile 查询。

修改：

- `DataSource.java` 增加 `businessSystemId`，保留并 deprecate `dataSourceUnit`；
- `DataSourceDao/DataSourceDaoImpl/DataSourceMapper` 支持 system/unit 过滤与批量装配，避免列表 N+1。

### Common / SPI

新增：

- `MetadataDesiredState`、`MetadataSyncStatus`、`MetadataRunStatus`；
- Unit/System create/update/query DTO 与 VO；
- `MetadataSourceBinding` 相关内部 DTO（不向前端透传 OM 原始 JSON）；
- `MetadataStableName` helper 及测试，固定 `st_ds_{id}`、`_metadata`、`_profiler`。

修改：

- `DataSourceDTO` 增加 `businessSystemId`、`unitId` 查询条件，旧 `dataSourceUnit` 仅兼容；
- `DataSourceVO` 增加 system/unit ID 和名称；Sprint 1 不伪造 OM READY/运行状态。

### API / Service

新增：

- `DataSourceUnitController/Service/ServiceImpl`；
- `BusinessSystemController/Service/ServiceImpl`；
- `MetadataBindingCommandService` 的纯本地原语与测试。

修改：

- `DataSourceServiceImpl`：校验启用的 BusinessSystem，写 `businessSystemId`，查询派生 Unit/System；历史 null 显示“待归属”；
- create/update 与 Binding 的本地事务挂接应由测试证明原子性；不在事务中调用 OM；
- delete desired-state 和外部清理完整切换留到 Sprint 2，避免 Sprint 1 在没有 Reconciler 时让删除永久卡住；
- `DataSourceController` 只扩展现有 DTO/VO，不创建第二套 DataSource Controller。

### 前端（保持现有风格）

修改：

- `pages/data-source/service.ts`、`types.ts`：增加 Unit/System API 与字段；
- `DynamicDataSourceForm/index.tsx`：在现有表单内换成 Unit -> BusinessSystem 级联，提交 `businessSystemId`；
- `AddOrEditDataSourceModal.tsx`：编辑回显与历史待归属兼容；
- `DataSourceCard.tsx`、`index.tsx`、`SearchBar.tsx`：展示/过滤单位和业务系统；
- 复用当前 Modal/Card/Tailwind/LESS tokens，不新建另一套数据源页面。

新增轻量组件（仍属于现有 DataSource 页面）：

- `components/BusinessSystemSelect.tsx`；
- Unit/System 轻量维护 Modal/Drawer（具体拆分以现有组件粒度为准）；
- service/component tests，覆盖级联、禁用项、待归属和提交字段。

### Sprint 1 验收

- migration 在全新库、已到 V1_0_14 的库以及已到 Lake V1_0_19 的库均成功；Flyway 无重复版本且不修改历史 checksum。
- Unit 删除受 System 引用时拒绝；System 删除受 DataSource 引用时拒绝。
- 新建/修改 DataSource 必须绑定启用 System；历史 null 可读且显示待归属。
- DataSource 仍由原 CRUD/页面管理，连接测试、任务引用保护和 Top20 回归通过。
- Binding 唯一、默认状态/版本正确，本地事务失败时不留半条数据。
- 后端编译/单测、前端 typecheck/test/build 通过后，才进入 Sprint 2。

## 7. 明确不在 Sprint 0/1 做

- 不实现 Reconciler、OpenMetadata REST client、自动扫描、Profiler UI、数据清查看板、拓扑或导出。
- 不调用 Airflow API。
- 不复制 OM metadata/profile 明细。
- 不升级 OpenMetadata，不把 ingestion 切到未验证 patch build。
- 不对现有 DataSource、Catalog、插件体系做无关重构。
