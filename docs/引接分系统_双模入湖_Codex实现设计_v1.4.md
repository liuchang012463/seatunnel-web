# 引接分系统双模入湖 MVP — Codex 实现设计 v1.4

> 状态：实施基线（取代 v1.3）  
> 目标分支基线：`develop@943a17a0`  
> SeaTunnel Web：1.0.0；SeaTunnel Engine：2.3.13  
> OpenMetadata Server / Java SDK：1.12.10  
> Doris 部署：`/mnt/lc/doris`，镜像标签 4.1.2，实际运行版本
> `doris-4.1.2-rc01-aec169d2025`  
> 部署数据库：MySQL 8.0.39  
> 用途：Codex/Luna Max 的直接实现与验收基线。未明确列入 P0 的能力不得自行扩展。

---

## 0. v1.4 修订摘要

v1.4 保留 v1.3 的领域方向，并修复实施前 Review 暴露的阻塞项：

1. 增加 V1.0.15–V1.0.20 旧湖表的兼容与退役策略；新 migration 从 V1.0.21 开始。
2. 普通 Value 字段默认 `STRING`；Doris Key 字段禁止 `STRING`，默认 `VARCHAR(255)`。
3. MANAGED 建表向导的字段映射写入现有结构化任务 `mappings`，不依赖隐式大小写转换。
4. 任务使用 `odsDatabaseBindingId` 选择 ODS DB，由服务端解析实际 database，前端不提交裸库名。
5. AUTO_CREATED 仅对精确单表任务创建表级 Mapping；多表/整库任务登记 Namespace 关系，实际表按 UNMANAGED 发现。
6. Script 任务不得使用平台 Lake Doris DataSource，解决“不解析 Script”与“ODS 禁止 RECREATE”之间的冲突。
7. 生命周期只可在建表时启用分区，或应用到创建时已经启用 Auto Range 的 MANAGED 表。
8. 生命周期 desired state 只有一个权威来源；更新 retention 不再产生自相矛盾的 Contract Drift。
9. 外部 Doris 操作采用“持久化 intent → 外部执行 → 持久化完成”的三段式事务。
10. 删除表必须先解除所有可再次运行的任务关系，不能只检查当前运行实例。
11. Reconcile 由显式 POST 触发；GET 详情不写库；远端不可用与资源不存在严格区分。
12. Catalog 增加安全指纹、credential revision、driver checksum 和只读查询边界。

---

## 1. 实际基线与已验证事实

### 1.1 代码库事实

- 当前 `develop` 已包含 MySQL migration V1.0.15–V1.0.20。
- V1.0.15–V1.0.19 已创建旧方案的物理资源、逻辑映射、生命周期执行等表。
- V1.0.20 已创建单位、业务系统和 OpenMetadata Binding 主数据。
- `DataSource.businessSystemId` 对历史数据允许为空；旧 `unit_code` 可能由显示名称回填，不保证满足 Doris 标识符规则。
- 批量和流式任务均有 SINGLE/MULTI/SCRIPT 等模式；批量另有 SINGLE_INCREMENTAL/FILE_SYNC。
- 单表 Flow 已有 `mappings[{sourceField,targetField,targetType}]`，应复用，不新增平行字段映射系统。
- MULTI/整库任务可以通过表列表、关键字、正则和 `${table_name}` 动态产生多个目标表。
- Doris DataSource 连接参数带一个默认 database；Node config 可以覆盖 database，但当前多表目标 DTO 没有 Lake Binding 字段。

### 1.2 Doris 部署事实

`/mnt/lc/doris/docker-compose.yml`：

- 1 FE，端口 8030/9010/9030；
- 3 BE，均 Alive；
- 镜像标签 `apache/doris:fe-4.1.2` / `be-4.1.2`；
- 实际版本字符串为 `doris-4.1.2-rc01-aec169d2025`；
- 当前已有一个 JDBC External Catalog；
- 当前镜像内确认存在 MySQL Driver：
  `file:///opt/apache-doris/fe/lib/mysql-connector-j-8.0.33.jar`；
- PostgreSQL/Oracle Driver 尚未在 Doris 容器中确认部署，能力必须返回 disabled，不能假装可用。

### 1.3 真实 Doris Spike 结论

已在独立临时 Database 中验证并清理：

- `STRING` 作为 Duplicate/Unique Key 会失败：
  `String Type should not be used in key column`；
- `VARCHAR(255)` 可作为 Key；
- `AUTO PARTITION BY RANGE(date_trunc(...))` 可创建；
- `ALTER TABLE ... SET ("partition.retention_count"="N")` 在当前运行环境可用；
- `information_schema.table_properties` 可读取 `partition.retention_count` 和 `_auto_bucket`；
- `SHOW CREATE TABLE` 会把 Contract 的 `STRING` 输出为 `text`，ContractReader 必须归一化 `TEXT ↔ STRING`；
- `SHOW CREATE TABLE` 会增加大量 Doris 默认属性，Drift 比较必须只比较 Web 管理范围。

所有 Doris 行为仍须在 CI/集成环境使用相同运行版本做回归，不以滚动的 4.x 文档代替实测。

---

## 2. P0 合同与非目标

### 2.1 F6-01 物理入湖

```text
OpenMetadata Table
    ↓
MANAGED ODS 单表建表向导（可选）
    ↓
Doris Internal ODS Table
    ↓
现有结构化 SeaTunnel 批量/增量/CDC 任务
```

旁路：

```text
现有结构化单表任务 + CREATE_SCHEMA_WHEN_NOT_EXIST
    ↓
AUTO_CREATED / PENDING_CREATE
    ↓
显式 Reconcile
    ↓
READY 或 MISSING/ERROR
```

多表/整库旁路：

```text
MULTI/WHOLE structured task
    ↓ namespace job relation
实际产生的 Doris 表
    ↓ 显式 Reconcile
discovered UNMANAGED
```

P0 不把动态多表任务产生的每张表自动猜测关联到 OM Table。

### 2.2 F6-02 逻辑入湖

基于现有业务 DataSource，在 Doris 创建 MANAGED External Catalog；不搬移数据；支持元数据验证、单表只读验证和 UI 生成的跨 Catalog JOIN 验证。

P0 Adapter 代码：MySQL、PostgreSQL、Oracle。运行时只有 Adapter、Driver、网络和源配置都通过 Capability 才可创建。

### 2.3 F15-01 生命周期

生命周期定义为：

```text
配置级时效性校验
+ Auto Range Partition
+ partition.retention_count
```

`retention_count=N` 只保留分区值最大的 N 个历史分区；当前和未来分区不受影响；不等于逐行 TTL 或精确 N 个自然日。

### 2.4 P0 非目标

- 自动通用源类型映射；
- 自动数据清洗和质量修复；
- 行级 TTL Filter；
- Web 定时 DROP/DELETE；
- 后台周期 Reconcile；
- 自动 Schema Evolution/ALTER/Drift 修复；
- Script HOCON 解析；
- AUTO_CREATED/UNMANAGED 自动升级为 MANAGED；
- S3/HDFS 归档和 SSD/HDD 冷热；
- 任意 SQL Playground；
- 多湖实例。

---

## 3. 领域对象与事实边界

### 3.1 核心对象

- Source DataSource：现有连接对象。
- Source Asset：OpenMetadata Table，以 `OM Entity UUID` 为稳定身份，以当前 FQN 为可变定位。
- Lake DataSource：配置指定的 Doris DataSource，P0 单实例。
- ODS Database Binding：一个 Source DataSource 对应一个 Doris Internal Database。
- Physical Projection：OM Table 到 Doris Internal Table 的关系。
- Logical Projection：Source DataSource 到 Doris External Catalog 的关系；TABLE Scope 内可保存 OM Table UUID 引用。
- Job Relation：现有任务与 ODS Database/Table 的结构化关系。

### 3.2 权威数据

| 数据 | 权威来源 |
|---|---|
| OM Table 当前存在性、FQN、Schema | OpenMetadata 1.12.10 |
| Doris DB/Table/Catalog、字段、Key、分区、属性 | Doris |
| ODS 归属、期望结构、管理等级、生命周期意图 | 本地业务库 |
| SeaTunnel 定义、上线状态、调度、运行状态 | 现有任务模块 |
| Doris Catalog 源连接密码 | 现有 DataSource 密文/服务端配置；Lake 表不保存 |

### 3.3 一源一库/一源一 Catalog

P0：

- 一个 Source DataSource 最多一个未删除 ODS Database Binding；
- 一个 Source DataSource 最多一个未删除 External Catalog Binding；
- 同一 OM Table 最多一个未删除标准物理 Projection；
- 物理和逻辑模式不互斥。

---

## 4. ODS 管理等级

### 4.1 MANAGED

由 ODS 向导创建。保存 Source Baseline、Target Structural Contract、字段映射、分区结构和生命周期 Binding。允许生命周期；禁止 SeaTunnel 自动重建。

### 4.2 AUTO_CREATED

仅适用于可在保存时精确识别 Source Table 和 Target Table 的结构化单表任务，且目标不存在、`schema_save_mode=CREATE_SCHEMA_WHEN_NOT_EXIST`。

保存源引用、目标、任务关系和存在性；不保存完整 Target Contract；不允许生命周期。

### 4.3 UNMANAGED

Doris 实际存在但没有 Web Structural Contract，包括 DBA/Script/动态多表任务产生的表。

- 默认只在页面发现，不立即入库；
- 用户可显式关联 OM Table 后创建引用记录，仍保持 UNMANAGED；
- P0 不允许配置生命周期或由 Web DROP；
- 不按名字自动猜测源表。

---

## 5. Source Snapshot 与 Target Contract

### 5.1 Source Snapshot

创建 MANAGED 表时服务端按 `sourceDataSourceId + omEntityId` 重新读取 OM，并复用现有 `requireOwnedTable` 归属校验；不相信前端提交的 Source 类型、nullable、PK 或 FQN。

Schema Hash canonical 内容：字段名、ordinal、OM type/display type、length/precision/scale、nullable、结构约束。约束集合按稳定顺序排序。排除 owner/tag/description/profile/statistics。

远端结果：

- 明确 404/UUID 不存在 → `MISSING`；
- 超时、鉴权、网络、5xx → `UNKNOWN`，不得写成 MISSING。

### 5.2 Target Column 规则

- 非 Key 普通字段默认 `STRING`；
- Key 字段禁止 `STRING/TEXT/FLOAT/DOUBLE/复杂类型`；
- 由 OM PK 预选的 Key 默认 `VARCHAR(255)`，用户可改为其它白名单 Key 类型；
- 生命周期分区字段必须 `DATE`/`DATETIME`、源端和目标端均 NOT NULL；
- 用户可编辑 targetName，但创建任务时必须生成现有 `mappings`，不得依赖隐式同名；
- 目标名称冲突按大小写归一后检测；
- Key 字段在 Doris 物理 DDL 中排在前 K 列。

### 5.3 TargetContract v2

```json
{
  "version": 2,
  "tableModel": "DUPLICATE",
  "columns": [
    {
      "sourceName": "ID",
      "sourceOrdinal": 1,
      "targetName": "id",
      "targetType": {"base": "VARCHAR", "length": 255},
      "nullable": false,
      "key": true,
      "physicalOrdinal": 1
    },
    {
      "sourceName": "PAYLOAD",
      "sourceOrdinal": 2,
      "targetName": "payload",
      "targetType": {"base": "STRING"},
      "nullable": true,
      "key": false,
      "physicalOrdinal": 2
    }
  ],
  "keyColumns": ["id"],
  "partition": {
    "enabled": false,
    "column": null,
    "granularity": null
  },
  "distribution": {
    "type": "RANDOM",
    "columns": [],
    "buckets": "AUTO"
  }
}
```

生命周期 retention 不再放在 Structural Contract 中；它的唯一 desired source 是 `lake_table_lifecycle_binding`。Target Drift 合并展示“Structural Drift”和“Lifecycle Property Drift”，但两者分别读取各自的 desired source。

### 5.4 Canonical Hash

- Jackson 固定字段顺序；Map key 字典序；数组保持语义顺序；
- 标识符按目标实际大小写规范化；
- Doris `TEXT` 与 Contract `STRING` 归一为 `STRING`；
- `DATETIME` 与 `DATETIME(0)` 归一；DECIMAL precision/scale 显式化；
- hash 使用 UTF-8 canonical JSON 的 SHA-256；
- comment 不参与 Structural Drift。

---

## 6. 数据库与 Migration

### 6.1 历史 migration 不可变

- 不修改 V1.0.15–V1.0.20；
- 新实现从 MySQL `V1_0_21__init_lake_dual_mode_v14.sql` 开始；
- 旧湖表保留但新 Service 不读写；页面不展示旧表数据；
- P0 不自动迁移旧设计中的规则/执行记录，因为其语义与 v1.4 不等价；
- 后续确认旧表无保留价值后，另发显式、可回滚的数据归档/删除 migration，不能夹在 P0 初始化中。

### 6.2 新表

1. `t_seatunnel_web_lake_source_object_ref`
2. `t_seatunnel_web_lake_ods_database_binding`
3. `t_seatunnel_web_lake_ods_table_mapping`
4. `t_seatunnel_web_lake_job_relation`
5. `t_seatunnel_web_lake_lifecycle_policy`
6. `t_seatunnel_web_lake_table_lifecycle_binding`
7. `t_seatunnel_web_lake_external_catalog_binding`
8. `t_seatunnel_web_lake_resource_operation`

JSON/Contract 使用 LONGTEXT + Jackson；不加数据库 FK；ID 复用 `BaseEntity.initInsert()`。

### 6.3 关键字段与约束

资源表统一包含：

```text
id
resource_status
lock_version
operation_token
error_code/error_message
last_reconcile_at
create_user_id/update_user_id
create_time/update_time
```

关键唯一约束：

```text
source_object_ref.om_entity_id unique
source datasource -> one ODS database binding
lake datasource + internal database unique
ODS database + target table unique
ODS database + source object unique
source datasource -> one external catalog
lake datasource + catalog name unique
job relation: binding + job + relation scope unique
table lifecycle binding: table mapping unique
```

删除后重建复用原资源记录并增加 `generation`；重置前写 `lake_resource_operation`，保存旧 generation、操作人、结果和脱敏摘要。禁止覆盖后完全丢失历史。

### 6.4 Lake Job Relation

```text
relation_scope: TABLE | NAMESPACE
ods_database_binding_id: required
table_mapping_id: nullable for NAMESPACE
job_runtime_type: BATCH | STREAMING
job_id
job_version
relation_status: ACTIVE | STALE
source_endpoint_snapshot
sink_endpoint_snapshot
schema_save_mode_snapshot
```

TABLE 用于 MANAGED/AUTO_CREATED 精确单表；NAMESPACE 用于 MULTI/WHOLE，不能宣称每张表已自动纳管。

---

## 7. 外部操作事务与状态机

### 7.1 三段式事务

所有 CREATE/DROP/ALTER Doris 操作：

```text
TX1: validate + durable intent + status=PENDING/DELETING + operation_token
COMMIT
    ↓
External Doris operation
    ↓
Read actual state
    ↓
TX2: compare operation_token + finalize READY/DELETED/ERROR
COMMIT
```

不得在尚未提交的本地事务内执行外部 DDL。

### 7.2 Resource Operation

记录：resource type/id/generation、operation type、operation token、request hash、status、started/finished、error code、脱敏摘要、operator。禁止记录密码、Catalog 完整 DDL、DataSource connection JSON。

### 7.3 幂等与接管

- retry 先读 Doris actual；
- actual 与 desired 一致 → finalize READY；
- actual 存在但不一致 → READY + DRIFT，不 DROP；
- stale PENDING 超过配置阈值后只能由显式 Retry 接管，生成新 token；
- finalize 时 token 不一致则忽略旧执行结果；
- unique index 是最终并发保护，Service precheck 只改善错误提示。

---

## 8. ODS Database

### 8.1 命名

```text
ods_{unit_code}_{system_code}_{custom_name}
```

服务端读取主数据 code 并规范化为小写，要求：

```regex
^[a-z_][a-z0-9_]{0,63}$
```

若 DataSource 未绑定 BusinessSystem，或 unit/system code 无法规整到合法值，阻止创建并返回：

```text
LAKE_MASTER_DATA_INCOMPLETE
LAKE_MASTER_DATA_CODE_INVALID
```

不能使用 ID、显示名称或截断 hash 静默替代业务 code。

### 8.2 创建/重试/删除

- 创建前检查 Doris 实际 DB；预先存在且没有本地 intent → name conflict，不自动接管；
- 删除要求没有未删除 Table Mapping，也没有 ACTIVE Namespace Job Relation；
- 不使用 `DROP DATABASE CASCADE`；
- 详情 GET 不执行 Reconcile；UI 显式 POST 后刷新详情。

---

## 9. MANAGED ODS 单表向导

### 9.1 四步

1. 选择 OM Source Table；
2. 目标字段与表模型；
3. 分区和生命周期；
4. 服务端 Preview、确认创建。

### 9.2 Preview

服务端返回：

```json
{
  "valid": true,
  "previewToken": "opaque-token",
  "sourceSchemaHash": "sha256",
  "targetContractHash": "sha256",
  "targetContract": {},
  "fieldMappings": [],
  "ddl": "CREATE TABLE ...",
  "warnings": [],
  "errors": []
}
```

`previewToken` 绑定用户、sourceDataSourceId、omEntityId、bindingId、Source Hash、Contract Hash，有短有效期；Create 必须重新读取 OM 和重新校验，不直接执行前端回传 DDL。

### 9.3 表模型与 Key

- DUPLICATE / UNIQUE；
- Key 必须至少一个；
- Key 列必须为物理前 K 列；
- Key 禁止 STRING/TEXT/FLOAT/DOUBLE/复杂类型；
- Unique 分区字段必须属于 Unique Key；
- Unique Hash Distribution 只使用 Key 列；
- Duplicate 默认 RANDOM BUCKETS AUTO；Unique 默认 HASH(Key...) BUCKETS AUTO。

### 9.4 字段映射闭环

Contract 中每个字段生成：

```json
{"sourceField":"ORDER_NO","targetField":"order_no","targetType":"STRING"}
```

“从 ODS 表创建引接任务”跳转现有单表任务页面并预填：

- sourceDataSourceId / source table；
- lakeDataSourceId；
- odsDatabaseBindingId；
- target table；
- mappings；
- MANAGED 固定 `ERROR_WHEN_SCHEMA_NOT_EXIST`。

用户后续修改字段映射或 endpoint 时，Job Relation/Task Consistency 按结构化定义比较并显示 Drift。

---

## 10. 数据引接桥接

### 10.1 服务边界

```java
validateBeforeJobSave(JobDefinitionSaveCommand command);
syncRelationAfterJobSave(JobDefinitionSaveCommand command, Long jobId, int version);
validateBeforeJobOnline(Long jobId);
validateBeforeJobExecute(Long jobId);
markRelationsAfterJobDelete(Long jobId);
```

保存前、上线前、执行前均校验；关系写入与任务定义保存处于同一本地事务。外部 Doris 不参与任务保存事务。

### 10.2 湖任务识别

结构化 sink 同时满足：

```text
sinkDataSourceId == lake.datasource-id
AND odsDatabaseBindingId resolves to an active binding
```

前端不得自由提交 sinkDatabase；后端用 Binding 覆盖 Node config 的 `database`。

### 10.3 ODS 安全规则

- Source DataSource 必须与 Binding owner 相同；
- MANAGED target 强制 `ERROR_WHEN_SCHEMA_NOT_EXIST`；
- 精确单表、目标不存在、自动建表开启 → AUTO_CREATED/PENDING_CREATE；
- 目标已存在但无 Mapping → UNMANAGED/READY；
- MULTI/WHOLE → NAMESPACE Relation，不自动创建表级 Mapping；
- 任意结构化 ODS 任务禁止 `RECREATE_SCHEMA`；
- Script 任务禁止使用 Lake Doris DataSource；
- 已存在的旧任务在 Online/Execute 时重新校验，不能靠保存 Hook 绕过。

### 10.4 删除保护

MANAGED/AUTO_CREATED 表只有在：

- 没有运行实例；
- 没有 ONLINE/启用调度的 ACTIVE Job Relation；
- 用户已将任务下线、删除或改写 endpoint，并同步为 STALE；

才允许 DROP。AUTO_CREATED 必须特别阻止“删除后被下一次调度自动重建”。

---

## 11. 生命周期

### 11.1 唯一 Desired Source

`lake_table_lifecycle_binding` 保存：partition column、granularity、retention count、policy snapshot、validation、status。Target Structural Contract 只保存 Auto Range 结构，不保存 retention。

### 11.2 可应用条件

- MANAGED + READY；
- Structural Contract 已启用 Auto Range；
- actual Doris 仍为相同 Auto Range；
- 分区字段 DATE/DATETIME；
- source 与 target 均 NOT NULL；
- Policy granularity 与分区粒度相同；
- Unique 表的分区字段已属于 Unique Key。

未分区表不能后补 Auto Range；返回 `LAKE_LIFECYCLE_REQUIRES_PREPARTITIONED_TABLE`。

### 11.3 创建与更新

- 建表时选择 retention：CREATE DDL 带 property；验证后创建 ACTIVE Binding；
- 永久 + Auto Range：不写 retention，后续允许 Apply；
- 永久 + 无分区：P0 永远不能 Apply；
- 更新 retention：三段式 operation，先 ALTER，再读 `information_schema.table_properties` 验证，再更新 Binding；
- retention 减小必须返回受影响历史分区预览并二次确认；
- P0 不支持 Active → 永久，禁止假设 -1/0 为关闭语义。

ACTIVE 只表示 Property 已应用，不表示异步回收已经完成；页面单独显示实际历史分区数量和最后观察时间。

---

## 12. Reconcile 与 Drift

### 12.1 触发方式

只有显式命令：

```http
POST /api/v1/lake/physical/databases/{id}/reconcile
POST /api/v1/lake/physical/tables/{id}/reconcile
POST /api/v1/lake/logical/catalogs/{id}/reconcile
```

页面进入可自动发起一次 POST，但 GET 本身只读缓存状态。

### 12.2 三维一致性

- Source：OM current hash vs creation baseline；
- Target：Structural Contract/Lifecycle Binding vs Doris actual；
- Task：ACTIVE relation snapshot vs current structured job endpoint/mappings/schema mode。

聚合优先级：

```text
MISSING > DRIFT > UNKNOWN > CONSISTENT
```

Task 无 ACTIVE relation → UNBOUND。多个 relation 中任一不一致 → DRIFT；详情展示每个 relation 的独立结果。

### 12.3 Actual 读取

- columns：`information_schema.columns`；
- properties：`information_schema.table_properties`；
- partitions：`information_schema.partitions` 或 `SHOW PARTITIONS`；
- Model/Key/Auto Range/Distribution：有限解析实际 4.1.2 `SHOW CREATE TABLE`；
- 不比较原始 DDL 字符串；
- 只比较 Web 管理的字段，忽略 Doris 默认属性、stats、replication 默认值。

---

## 13. Logical Lake / External Catalog

### 13.1 Capability

```text
Adapter exists
Driver config exists
Driver checksum configured
Source config complete
Lake Doris reachable
Source network reachable from Doris FE/BE
```

缺一项即 `logicalSupported=false`，返回稳定 reason code。

当前部署：MySQL 可进行真实验收；PostgreSQL/Oracle Adapter 可开发，但在 Driver 安装并重启/验证前 UI disabled。

### 13.2 Desired Spec 与凭证

Binding 保存脱敏 desired spec/hash：type、adapter、jdbc endpoint、driver URL/class/checksum、scope、case rules、非敏感 options。

- 不保存密码；
- 保存 `credential_revision`，来自 DataSource 配置版本或服务端 HMAC，不可逆；
- 密码变化要求显式“更新挂载”；
- 更新后刷新/验证，并提示 Doris JDBC 连接池可能继续使用旧凭证，部署运维需按实际环境处理；
- Catalog DDL 和 error 必须脱敏。

### 13.3 Scope

- ALL；
- DATABASE：`include_database_list`；
- TABLE：同时生成 `include_database_list` 和 `include_table_list=db.tbl,...`；
- Adapter 负责 MySQL/PG/Oracle database/schema/table 映射和大小写；
- 无法可靠映射 TABLE Scope 的 Adapter 只开放 DATABASE/ALL。

Scope 是元数据同步范围，不是授权边界。

### 13.4 查询验证

P0 不开放任意 SQL 编辑器。UI 只生成：

- 单表选择字段 + LIMIT 1；
- 两个 Catalog 各选表、字段和等值 Join Key 的跨 Catalog JOIN；
- EXPLAIN。

服务端：

- 使用专用 readonly DataSource；
- SQL 由结构化请求生成；
- 标识符统一校验/quote；
- query timeout、max rows、max bytes、取消；
- 操作审计；
- 禁止返回标记为敏感的字段，或按现有权限脱敏。

JDBC Catalog 只定位为小数据/小表联查验证，不宣传为大规模查询加速。

---

## 14. API 基线

实际返回统一使用现有 `Result`/`PaginationResult`。

### 14.1 Recommendation

```http
POST /api/v1/lake/recommend
```

### 14.2 Physical

```http
POST   /api/v1/lake/physical/datasources/page
GET    /api/v1/lake/physical/datasources/{sourceDataSourceId}
POST   /api/v1/lake/physical/datasources/{sourceDataSourceId}/database
POST   /api/v1/lake/physical/databases/{id}/retry
POST   /api/v1/lake/physical/databases/{id}/reconcile
DELETE /api/v1/lake/physical/databases/{id}

POST   /api/v1/lake/physical/tables/preview
POST   /api/v1/lake/physical/tables
GET    /api/v1/lake/physical/tables/{id}
POST   /api/v1/lake/physical/tables/{id}/retry
POST   /api/v1/lake/physical/tables/{id}/reconcile
DELETE /api/v1/lake/physical/tables/{id}
POST   /api/v1/lake/physical/unmanaged/bind
DELETE /api/v1/lake/physical/unmanaged/{id}/binding
```

### 14.3 Lifecycle

```http
POST /api/v1/lake/lifecycle/policies/page
POST /api/v1/lake/lifecycle/policies
PUT  /api/v1/lake/lifecycle/policies/{id}
POST /api/v1/lake/lifecycle/policies/{id}/disable
POST /api/v1/lake/lifecycle/validate
POST /api/v1/lake/lifecycle/apply
POST /api/v1/lake/lifecycle/tables/{mappingId}/retention/preview
PUT  /api/v1/lake/lifecycle/tables/{mappingId}/retention
```

### 14.4 Logical

```http
GET    /api/v1/lake/logical/datasources/{sourceDataSourceId}/capability
POST   /api/v1/lake/logical/catalogs/page
POST   /api/v1/lake/logical/catalogs
GET    /api/v1/lake/logical/catalogs/{id}
PUT    /api/v1/lake/logical/catalogs/{id}
DELETE /api/v1/lake/logical/catalogs/{id}
POST   /api/v1/lake/logical/catalogs/{id}/refresh
POST   /api/v1/lake/logical/catalogs/{id}/validate
POST   /api/v1/lake/logical/catalogs/{id}/reconcile
POST   /api/v1/lake/logical/query/single-table
POST   /api/v1/lake/logical/query/join/preview
POST   /api/v1/lake/logical/query/join
```

---

## 15. 权限与安全

接入现有权限体系，不新造 RBAC。至少区分：

- `lake:view`；
- `lake:physical:manage`；
- `lake:lifecycle:manage`；
- `lake:logical:manage`；
- `lake:logical:query`。

安全不可变规则：

- Controller/Service 不直接拼 UI 标识符；
- DorisIdentifier 只处理标识符，DorisSqlLiteral 只处理字面量，职责分离；
- Catalog property key 使用 Adapter 白名单，不能由 UI 自定义；
- 日志、Operation、异常、DTO 不含 password/token/connection JSON；
- Script 禁止使用 Lake Doris DataSource；
- 管理账号与只读查询账号分离；
- Driver URL/Checksum 由服务端配置，P0 不支持上传 Driver。

---

## 16. 测试与门禁

### 16.1 单元测试

- Identifier/Literal/Property Escaper；
- TargetContractValidator：Key 禁 STRING、字段映射、物理顺序、生命周期源/目标 nullable；
- DDL Builder：Duplicate/Unique/Auto Range/Auto Bucket/各标量类型；
- ContractReader：真实 4.1.2 SHOW CREATE fixture，含 `TEXT↔STRING`；
- Canonical Hash 稳定性；
- Recommendation 全分支；
- Catalog Adapter/Scope/secret redaction；
- Job Relation aggregate status。

### 16.2 集成测试

1. V1.0.21 在已存在 V1.0.15–V1.0.20 的 MySQL 上升级成功；
2. create DB/table 的 DDL 后进程崩溃，可显式 Retry 恢复；
3. operation token 防止旧请求覆盖新结果；
4. Key STRING 被 Validator 阻止；
5. MANAGED 预填 mappings 和 `ERROR_WHEN_SCHEMA_NOT_EXIST`；
6. source rename/type/nullable Drift；
7. DBA ALTER/DROP Target Drift/Missing；
8. OM timeout → UNKNOWN，OM 404 → MISSING；
9. exact single AUTO_CREATED；
10. MULTI/WHOLE 只创建 Namespace Relation，表进入 discovered UNMANAGED；
11. RECREATE save/online/execute 三层阻止；
12. Script 使用 Lake DataSource 被阻止；
13. 删除 AUTO_CREATED 前要求任务下线/解绑；
14. retention create/update/decrease confirmation；
15. MySQL Catalog create/refresh/validate/join/delete；
16. credential revision change → CONFIG_DRIFT；
17. query timeout/max rows/max bytes；
18. DataSource 删除保护。

### 16.3 前端门禁

- `yarn install --frozen-lockfile`；
- `yarn run tsc`；
- `yarn run build`；
- 关键状态工具、向导 reducer、API normalization 单元测试；
- 320/768/1440 宽度页面检查；
- Loading/Empty/Error/Partial/Permission Denied 状态都有明确呈现。

### 16.4 Doris 真实环境门禁

- 使用 `/mnt/lc/doris` 当前实际版本；
- 不在 existing `ods`/业务 DB 做测试；使用唯一临时 DB/Catalog 并在测试后清理；
- Auto Range + Auto Bucket 用代表性历史全量回灌验证分区/桶规模；
- 检查 `max_auto_partition_num`；
- PostgreSQL/Oracle Driver 未部署前，不宣称这两个 Adapter 环境验收通过。

---

## 17. 合同验收

### 17.1 F6-01

- 从 OM Table 创建 MANAGED ODS；
- Key 字段不是 STRING，普通字段默认 STRING；
- 从 ODS 跳转现有单表任务并带出字段 mappings；
- 批量/CDC 写入成功；
- AUTO_CREATED 单表旁路可 Reconcile；
- MULTI/WHOLE 以 Namespace/UNMANAGED 方式如实展示。

### 17.2 F6-02

- MySQL Catalog 真实创建、刷新、验证；
- 第二个 Catalog 环境就绪后执行结构化跨 Catalog JOIN；
- 页面明确说明不搬移数据和查询适用范围；
- 不开放任意写 SQL。

### 17.3 F15-01

- MANAGED 表创建时选择 DATETIME NOT NULL Auto Range；
- 应用 retention_count；
- 页面展示“保留 N 个历史分区”，同时展示实际历史分区数；
- retention 修改可验证，减小前有影响预览；
- 不声称精确自然日 TTL 或行级过滤。

---

## 18. 实施顺序

1. Phase 0：环境 Spike、V1.0.21 migration、基础枚举/安全工具/operation journal。
2. Phase 1：DorisLakeClient、Contract/DDL/Reader。
3. Phase 2：ODS Database 和 MANAGED 单表向导。
4. Phase 3：Job Bridge、AUTO_CREATED、Namespace Relation。
5. Phase 4：Reconcile/Drift/UNMANAGED。
6. Phase 5：Lifecycle。
7. Phase 6：Logical Catalog、只读验证。
8. Phase 7：Recommendation、全前端、合同验收。

每个 Phase 必须包含代码、migration、单元测试、对应集成测试、配置说明、API/DTO 变更、对现有引接的影响和未完成项。不得以“后续补测试”作为 P0 完成。

---

## 19. 开发 Agent 不可自行改变的决策

1. 不新建第二套 ETL/调度/运行系统。
2. OM UUID 是 Source Asset 稳定身份。
3. Doris 是实际资源事实源，本地保存 desired/control state。
4. 非 Key Value 字段默认 STRING；Key 禁止 STRING。
5. MANAGED 字段改名必须通过现有 mappings 闭环。
6. P0 MANAGED 仅单表向导。
7. MULTI/WHOLE 不伪装成已建立表级源映射。
8. Script 禁止使用 Lake DataSource。
9. 所有结构化 ODS 任务禁止 RECREATE_SCHEMA，保存/上线/执行重复校验。
10. 生命周期只使用 Doris Auto Range + retention_count，不做 Web Scheduler。
11. Lifecycle Binding 是 retention desired source。
12. Drift 只发现和提示，不自动修复。
13. Reconcile 是显式命令，GET 不写库。
14. External Catalog scope 不是权限边界。
15. P0 不开放任意 SQL 编辑器。
16. 不修改 V1.0.15–V1.0.20。

