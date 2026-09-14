# 引接分系统双模入湖 MVP — Codex 实现设计

> 版本：v1.3  
> 目标代码库：`https://github.com/liuchang012463/seatunnel-web/tree/develop`  
> Doris：4.1.2  
> SeaTunnel：沿用当前项目既有版本与现有数据引接实现（当前代码基线推荐 2.3.13）  
> 元数据来源：OpenMetadata  
> 文档用途：**作为 Codex/其它开发 Agent 的直接实现基线。除“明确标注 P1/P2”的内容外，不应自行扩大 P0 范围。**

---

## 0. Codex 执行须知

本设计是在多轮需求讨论和一次外部 Review 后收敛的开发基线。实现前请先检查当前 `develop` 分支的真实代码结构，并遵守以下规则：

1. **优先扩展现有模块，不为匹配本文类名而复制一套平行体系。** 本文类名是目标职责名；如果代码中已有相同职责的 Service、Repository、Controller、DTO 或任务扩展点，应直接扩展现有实现。
2. **不得重新实现数据引接任务系统。** 批量、单表、CDC、调度、运行、日志、重试继续由现有数据引接/SeaTunnel 模块负责。
3. **不得复制 OpenMetadata 的完整 Database/Schema/Table/Column 元数据到本地库。** 本地仅保存 OM Table 引用和创建投影时的结构快照。
4. **不得复制 Doris 全量元数据到本地库。** Doris 是实际湖资源事实源；本地数据库保存归属、映射、期望契约和业务策略。
5. **P0 不实现自动源类型→Doris 类型映射。** ODS 字段默认 STRING，用户只在需要时手工优化关键字段。
6. **P0 不实现后台定时 Reconcile、自动 Schema Evolution、自动 ALTER、自动 Drift 修复。** 对账按页面刷新/显式操作触发，只发现和提示。
7. **P0 不解析用户自定义 SeaTunnel HOCON/Script。** 湖桥接只接入现有结构化任务模型/DTO；Script 最终创建的 Doris 表按未纳管资源处理。
8. **所有受管 ODS Database 禁止 `RECREATE_SCHEMA`。** MANAGED 表目标不存在时必须报错，不能被 SeaTunnel 偷偷重建。
9. **External Catalog P0 为 MANAGED 模式。** Web 负责创建/更新/刷新/验证/删除 Catalog；但挂载范围不是权限边界，真正权限由 Doris 权限体系负责。
10. 所有涉及 Doris 的 SQL、Catalog Properties、标识符拼接必须使用统一安全工具类生成，禁止直接拼接未经验证的 UI 输入。

---

# 1. 合同指标与 MVP 落地

## 1.1 F6-01 物理入湖

合同核心：

- 物理/逻辑双模；
- 根据数据特性智能选择；
- 批量或实时 ETL；
- 全量/增量迁移；
- 集中持久化到数据湖。

P0 落地：

```text
OpenMetadata 源表
       ↓
ODS 建表向导（可选）
       ↓
Doris Internal ODS Table
       ↓
现有数据引接模块 / SeaTunnel
       ↓
全量 / CDC / 其它现有引接能力
```

另支持旁路：

```text
数据源已建立 ODS DB
       ↓
用户直接从数据引接新建任务
       ↓
SeaTunnel CREATE_SCHEMA_WHEN_NOT_EXIST
       ↓
AUTO_CREATED ODS Table
       ↓
自动汇聚到物理入湖资源管理
```

### “智能选择”P0

不是 AI，也不是自动执行。实现为：

- 系统先判断 PHYSICAL/LOGICAL 技术能力；
- 用户点击“智能推荐”；
- 回答 4 个简单问题；
- 确定性决策树给出推荐和原因；
- 用户最终确认。

问题：

1. 是否允许把数据复制到数据湖？
2. 是否需要独立生命周期/长期保存？
3. 是否需要 CDC、持续更新或高频访问？
4. 是否主要用于低频、临时、跨源联合查询？

决策：

```text
if !allowPhysicalCopy:
    logicalSupported ? LOGICAL : UNSUPPORTED
else if needLifecycle:
    PHYSICAL
else if needContinuousSync:
    PHYSICAL
else if temporaryFederatedQuery && logicalSupported:
    LOGICAL
else:
    PHYSICAL
```

只有技术上不支持的模式被禁用，推荐结果不强制执行。

---

## 1.2 F6-02 逻辑入湖

P0 定义：

> 基于现有业务 DataSource，由 Web 在 Doris 4.1.2 创建并管理 External Catalog，通过 Catalog 暴露远端 DB/Table 元数据，不搬移数据，并提供最小只读查询验证与跨 Catalog 查询能力。

```text
Source DataSource
      ↓
DorisCatalogAdapter
      ↓
CREATE CATALOG
      ↓
External Catalog
      ↓
catalog.database.table
      ↓
Doris 联邦查询
```

无 SeaTunnel/ETL 任务。

---

## 1.3 F15-01 数据生命周期管理

P0 不做逐行“过期数据过滤”。

“时效性校验”定义为配置级校验：

- 生命周期字段是否存在；
- Doris 目标类型是否 DATE/DATETIME；
- 是否 NOT NULL；
- 是否为合法 AUTO RANGE 分区字段；
- Unique Key 与 Partition Key 是否满足约束；
- `retention_count` 是否合法。

“暂存周期管理”定义为：

```text
AUTO PARTITION BY RANGE(date_trunc(...))
+
partition.retention_count
```

**注意：`partition.retention_count=N` 的准确含义是保留分区值最大的 N 个历史分区，不等于精确 N 个自然日。** 产品 UI 必须表达为“保留 N 个日/月/年历史分区”，可辅助显示“约 N 天/月/年”。

P0 不实现：

- SeaTunnel 行级 TTL Filter；
- Web 定时 DELETE；
- Web 定时 DROP PARTITION；
- SSD/HDD 冷热；
- S3/HDFS 归档。

---

# 2. 领域模型与事实边界

## 2.1 四类核心对象

### Source DataSource

现有系统的数据源连接对象。例如：MySQL CRM、Oracle ERP、PG GIS。

### Source Asset

OpenMetadata Table。统一以 `OM Entity ID + 当前 FQN` 标识。

### Lake DataSource

平台指定的 Doris 4.1.2 DataSource。P0 只有一个湖实例，配置：

```yaml
lake:
  datasource-id: <existing Doris datasource id>
```

### Lake Projection

同一个 OM 源表可以有：

```text
OM Table
 ├── Physical Projection -> Doris Internal ODS Table
 └── Logical Projection  -> External Catalog 中的远端表
```

两种模式不互斥。

---

## 2.2 一源一库/一源一 Catalog

P0 规则：

- 一个 Source DataSource 最多绑定一个 ODS Database；
- 一个 Source DataSource 最多绑定一个 External Catalog。

这是 **MVP 简化规则**，不是永久领域真理。P1 可扩展为一源多 Namespace/Catalog。

---

## 2.3 权威数据矩阵

| 数据 | 权威来源 |
|---|---|
| 源表是否存在 | OpenMetadata |
| 源表当前 Schema | OpenMetadata |
| 源对象稳定身份 | OM Entity ID |
| 当前源 FQN | OpenMetadata |
| Doris Database/Table/Catalog 是否存在 | Doris |
| Doris 实际字段/Key/Partition/Properties | Doris |
| Doris 统计/分区实际状态 | Doris |
| ODS 应属于哪个源 DataSource | 本地业务库 |
| MANAGED ODS 期望结构 | 本地业务库 |
| 生命周期策略及实际绑定意图 | 本地业务库 |
| 物理/逻辑映射 | 本地业务库 |
| SeaTunnel 任务定义 | 现有数据引接模块 |
| SeaTunnel 运行状态 | 现有数据引接/SeaTunnel |

### 关键原则

MANAGED 表：

- 本地 `target_contract` = 期望；
- Doris = 实际；
- 二者不一致 -> `TARGET_DRIFT`；
- **不允许 Doris 实际结构静默覆盖本地契约，也不允许 Web 自动改回去。**

---

# 3. ODS 定位与字段契约

## 3.1 ODS 定位

本系统 ODS 定义为：

> **兼容优先的 Raw ODS（Compatibility-first Raw ODS）**。优先保证异构数据能够稳定落湖和保留原值，不承担完整清洗、标准化、质量修正职责。

因此 P0：

- 源字段自动带出；
- 目标字段名默认规范化为小写；
- 普通字段目标 Doris 类型默认 `STRING`；
- 用户可以优化关键字段类型；
- 系统仅对影响 Doris 能力的关键字段做强校验。

---

## 3.2 默认 STRING 的产品提示

建表向导固定提示：

> 字段默认使用 STRING 以提高异构数据入湖兼容性，降低因时间格式、精度及异常历史值造成的数据写入失败风险。建议根据实际业务需要优化时间、数值等关键字段类型。

不要对每个 STRING 字段显示警告，只针对明显关键字段给非阻塞建议。

---

## 3.3 P0 目标类型白名单

P0 ODS 向导只开放常用标量类型：

```text
STRING
VARCHAR(n)
CHAR(n)
BOOLEAN
TINYINT
SMALLINT
INT
BIGINT
LARGEINT
FLOAT
DOUBLE
DECIMAL(p,s)
DATE
DATETIME
```

复杂/二进制源字段默认仍可选择 STRING，但提示：

> 目标 DDL 可创建并不保证当前 SeaTunnel Source/Sink 能将复杂值可靠序列化为 STRING，实际写入失败按现有引接任务失败处理。

P0 不开放 ARRAY/MAP/STRUCT/VARIANT 等复杂 Doris 类型。

---

## 3.4 用户修改目标类型的契约边界

用户选择：

```text
源 VARCHAR/DATE/... -> 目标 DATETIME/DECIMAL/...
```

仅表示：

> Doris 目标表使用该类型。

**不代表系统提供完整的 SeaTunnel Transform 或数据清洗。** 若实际数据不能转换，任务运行失败由现有数据引接模块处理。

---

## 3.5 生命周期字段强约束

参与 AUTO RANGE/生命周期的字段必须：

- 目标类型 `DATE` 或 `DATETIME`；
- `NOT NULL`；
- 存在于目标字段列表；
- 若表为 Unique Key，必须已经属于 Unique Key。

系统禁止为了生命周期自动把时间字段偷偷加入 Unique Key，因为这可能改变源端唯一性语义。

---

# 4. 管理等级

ODS Table 统一分三类。

## 4.1 MANAGED

来源：物理入湖建表向导。

Web 保存：

- OM 源结构快照；
- Doris 目标契约；
- 表模型/Key/Partition/Lifecycle；
- Drift 基准。

P0 生命周期只允许 MANAGED 表。

## 4.2 AUTO_CREATED

来源：数据引接任务启用 SeaTunnel `CREATE_SCHEMA_WHEN_NOT_EXIST`，目标表不存在。

Web 保存：

- 源对象；
- 目标对象；
- 任务关系；
- 实际存在状态。

不保存完整 Target Contract，不承诺符合 ODS 向导的分区/生命周期规范。

P0 不允许配置生命周期。

## 4.3 UNMANAGED

Doris 中实际存在，但 Web 未创建结构契约，例如 DBA/脚本手工创建。

P0：

- 可发现；
- 可查看；
- 用户可显式关联到某个 OM Source Table；
- 即使关联后仍为 UNMANAGED；
- 不允许生命周期；
- Web 不提供 DROP。

---

# 5. 数据库表设计

## 5.1 命名与 ORM 约定

当前仓库现有业务表采用 `t_seatunnel_web_` 前缀，DAO 实体通常继承 `BaseEntity`，`BaseEntity` 使用 `Long id` 且通过代码生成 ID，而不是数据库 AUTO_INCREMENT。

因此新表建议：

```text
t_seatunnel_web_lake_source_object_ref
t_seatunnel_web_lake_ods_database_binding
t_seatunnel_web_lake_ods_table_mapping
t_seatunnel_web_lake_table_job_rel
t_seatunnel_web_lake_lifecycle_policy
t_seatunnel_web_lake_table_lifecycle_binding
t_seatunnel_web_lake_external_catalog_binding
```

P0 JSON/Schema/Contract 字段使用 `LONGTEXT` + Jackson，不依赖 MySQL JSON 类型，降低环境兼容风险。

不加数据库 FK，引用一致性由 Service 保证，保持与现有工程风格一致。

---

## 5.2 SQL Migration 草案

> Codex 必须先检查项目当前 SQL migration/初始化脚本组织方式，并按现有方式添加。以下 DDL 是字段与约束基线，不要求机械复制文件路径。

```sql
CREATE TABLE `t_seatunnel_web_lake_source_object_ref` (
  `id` BIGINT NOT NULL,
  `source_data_source_id` BIGINT NOT NULL,
  `om_entity_id` VARCHAR(64) NOT NULL,
  `om_fqn` VARCHAR(1000) NOT NULL,
  `om_version` VARCHAR(32) DEFAULT NULL,
  `schema_hash` CHAR(64) DEFAULT NULL,
  `metadata_status` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  `last_seen_at` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lake_src_om_id` (`om_entity_id`),
  KEY `idx_lake_src_ds` (`source_data_source_id`),
  KEY `idx_lake_src_status` (`metadata_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Lake OM source object reference';

CREATE TABLE `t_seatunnel_web_lake_ods_database_binding` (
  `id` BIGINT NOT NULL,
  `source_data_source_id` BIGINT NOT NULL,
  `lake_data_source_id` BIGINT NOT NULL,
  `doris_catalog_name` VARCHAR(128) NOT NULL DEFAULT 'internal',
  `database_name` VARCHAR(256) NOT NULL,
  `custom_name` VARCHAR(128) NOT NULL,
  `resource_status` VARCHAR(24) NOT NULL,
  `error_code` VARCHAR(64) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `last_reconcile_at` DATETIME DEFAULT NULL,
  `create_user_id` INT DEFAULT NULL,
  `update_user_id` INT DEFAULT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lake_ods_src_ds` (`source_data_source_id`),
  UNIQUE KEY `uk_lake_ods_db` (`lake_data_source_id`, `database_name`),
  KEY `idx_lake_ods_status` (`resource_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Source datasource to Doris ODS database binding';

CREATE TABLE `t_seatunnel_web_lake_ods_table_mapping` (
  `id` BIGINT NOT NULL,
  `ods_database_binding_id` BIGINT NOT NULL,
  `source_data_source_id` BIGINT NOT NULL,
  `source_object_ref_id` BIGINT DEFAULT NULL,
  `source_fqn_snapshot` VARCHAR(1000) DEFAULT NULL,
  `target_table_name` VARCHAR(256) NOT NULL,
  `management_mode` VARCHAR(24) NOT NULL,
  `resource_status` VARCHAR(24) NOT NULL,
  `source_om_version` VARCHAR(32) DEFAULT NULL,
  `source_schema_hash` CHAR(64) DEFAULT NULL,
  `source_schema_snapshot` LONGTEXT DEFAULT NULL,
  `target_contract` LONGTEXT DEFAULT NULL,
  `target_contract_hash` CHAR(64) DEFAULT NULL,
  `source_consistency` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  `target_consistency` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  `task_consistency` VARCHAR(16) NOT NULL DEFAULT 'UNBOUND',
  `last_reconcile_at` DATETIME DEFAULT NULL,
  `error_code` VARCHAR(64) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `create_user_id` INT DEFAULT NULL,
  `update_user_id` INT DEFAULT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lake_ods_target` (`ods_database_binding_id`, `target_table_name`),
  UNIQUE KEY `uk_lake_ods_source` (`ods_database_binding_id`, `source_object_ref_id`),
  KEY `idx_lake_ods_map_src_ds` (`source_data_source_id`),
  KEY `idx_lake_ods_map_mode` (`management_mode`),
  KEY `idx_lake_ods_map_status` (`resource_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Source table to ODS table mapping';

CREATE TABLE `t_seatunnel_web_lake_table_job_rel` (
  `id` BIGINT NOT NULL,
  `table_mapping_id` BIGINT NOT NULL,
  `job_id` BIGINT NOT NULL,
  `relation_status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `source_endpoint_snapshot` LONGTEXT DEFAULT NULL,
  `sink_endpoint_snapshot` LONGTEXT DEFAULT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lake_table_job` (`table_mapping_id`, `job_id`),
  KEY `idx_lake_job_id` (`job_id`),
  KEY `idx_lake_job_rel_status` (`relation_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ODS table to existing ingestion job relation';

CREATE TABLE `t_seatunnel_web_lake_lifecycle_policy` (
  `id` BIGINT NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(500) DEFAULT NULL,
  `partition_granularity` VARCHAR(16) NOT NULL,
  `retention_count` INT NOT NULL,
  `scope_unit_id` BIGINT DEFAULT NULL,
  `scope_system_id` BIGINT DEFAULT NULL,
  `scope_data_source_id` BIGINT DEFAULT NULL,
  `scope_department_code` VARCHAR(128) DEFAULT NULL,
  `scope_data_type_code` VARCHAR(128) DEFAULT NULL,
  `policy_status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `create_user_id` INT DEFAULT NULL,
  `update_user_id` INT DEFAULT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_lake_lifecycle_name` (`name`),
  KEY `idx_lake_lifecycle_status` (`policy_status`),
  KEY `idx_lake_lifecycle_ds` (`scope_data_source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Reusable ODS lifecycle policy';

CREATE TABLE `t_seatunnel_web_lake_table_lifecycle_binding` (
  `id` BIGINT NOT NULL,
  `table_mapping_id` BIGINT NOT NULL,
  `policy_id` BIGINT DEFAULT NULL,
  `partition_column` VARCHAR(256) NOT NULL,
  `partition_granularity` VARCHAR(16) NOT NULL,
  `retention_count` INT NOT NULL,
  `lifecycle_status` VARCHAR(24) NOT NULL,
  `validation_result` LONGTEXT DEFAULT NULL,
  `applied_at` DATETIME DEFAULT NULL,
  `error_code` VARCHAR(64) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `create_user_id` INT DEFAULT NULL,
  `update_user_id` INT DEFAULT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lake_table_lifecycle` (`table_mapping_id`),
  KEY `idx_lake_lifecycle_policy` (`policy_id`),
  KEY `idx_lake_lifecycle_bind_status` (`lifecycle_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Applied lifecycle settings for a managed ODS table';

CREATE TABLE `t_seatunnel_web_lake_external_catalog_binding` (
  `id` BIGINT NOT NULL,
  `source_data_source_id` BIGINT NOT NULL,
  `lake_data_source_id` BIGINT NOT NULL,
  `catalog_name` VARCHAR(256) NOT NULL,
  `catalog_type` VARCHAR(64) NOT NULL,
  `adapter_type` VARCHAR(64) NOT NULL,
  `scope_mode` VARCHAR(16) NOT NULL,
  `scope_config` LONGTEXT DEFAULT NULL,
  `source_config_fingerprint` CHAR(64) DEFAULT NULL,
  `resource_status` VARCHAR(24) NOT NULL,
  `config_consistency` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  `validation_status` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  `last_validate_at` DATETIME DEFAULT NULL,
  `last_reconcile_at` DATETIME DEFAULT NULL,
  `error_code` VARCHAR(64) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `create_user_id` INT DEFAULT NULL,
  `update_user_id` INT DEFAULT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lake_catalog_src_ds` (`source_data_source_id`),
  UNIQUE KEY `uk_lake_catalog_name` (`lake_data_source_id`, `catalog_name`),
  KEY `idx_lake_catalog_status` (`resource_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Managed Doris external catalog binding';
```

### 删除/重建历史

P0 不新增 History 表。对已经 `DELETED` 的同源/同目标关系再次创建时，优先复用原记录并重置状态，而不是插入第二条冲突记录。操作审计复用现有系统审计/操作日志。

---

# 6. 枚举定义

Codex 应建立统一 enum，数据库保存稳定字符串，禁止散落 magic string。

```text
LakeResourceStatus:
  CREATING
  PENDING_CREATE
  READY
  ERROR
  CREATE_FAILED
  MISSING
  DELETING
  DELETED

LakeManagementMode:
  MANAGED
  AUTO_CREATED
  UNMANAGED

LakeConsistencyStatus:
  CONSISTENT
  DRIFT
  MISSING
  UNKNOWN

LakeTaskConsistencyStatus:
  CONSISTENT
  DRIFT
  UNBOUND
  UNKNOWN

LakeMetadataStatus:
  ACTIVE
  MISSING
  UNKNOWN

LakeJobRelationStatus:
  ACTIVE
  STALE

LifecycleGranularity:
  DAY
  MONTH
  YEAR

LifecycleStatus:
  PENDING_APPLY
  ACTIVE
  INVALID
  APPLY_FAILED
  DISABLED   # 预留；P0 不提供 ACTIVE -> 永久保留的禁用操作

LifecyclePolicyStatus:
  ACTIVE
  DISABLED

CatalogScopeMode:
  ALL
  DATABASE
  TABLE

CatalogValidationStatus:
  UNKNOWN
  PASSED
  FAILED

RecommendationMode:
  PHYSICAL
  LOGICAL
  UNSUPPORTED
```

---

# 7. SourceObjectRef 与 OpenMetadata

## 7.1 懒加载原则

不要批量复制 OM 全库资产。

SourceObjectRef 在以下场景创建/刷新：

- 用户从探查结果选择某源表创建 ODS；
- 用户为 External Catalog 选择具体 Table scope；
- Reconcile 已有投影；
- 用户手工将 UNMANAGED Doris 表关联 OM 源表。

---

## 7.2 Schema Snapshot

创建 MANAGED 表时，从服务端重新读取 OM 当前 Table，而不是相信前端提交的源结构。

建议快照字段：

```json
{
  "omEntityId": "uuid",
  "fqn": "service.database.schema.table",
  "version": "1.3",
  "columns": [
    {
      "name": "ID",
      "ordinal": 1,
      "dataType": "NUMBER",
      "dataTypeDisplay": "NUMBER(20)",
      "length": null,
      "precision": 20,
      "scale": 0,
      "nullable": false,
      "constraint": "PRIMARY_KEY"
    }
  ]
}
```

Snapshot 只用于审计/Drift 对比，不成为当前源结构权威。

---

## 7.3 Schema Hash

Canonical Hash 仅包含影响结构的数据：

- 字段名；
- 序号；
- OM data type / display type；
- length/precision/scale；
- nullable；
- PK/Unique 等结构约束。

排除：

- Owner；
- tag；
- description/comment（P0 不把备注变化视为源 Schema Drift）；
- profile/statistics。

OM version 可作为快速变化信号，但最终 Source Drift 以 `schema_hash` 比较为准，因为 OM 版本可能因非结构元数据变化而变化。

---

# 8. ODS Database 设计

## 8.1 命名

```text
ods_{unit_code}_{system_code}_{custom_name}
```

要求：

- unit/system 使用稳定 code，不使用显示名称；
- 全部规范化小写；
- 仅允许 ASCII 字母、数字、下划线；
- custom name 必填；
- 固定前缀及 unit/system 部分前端只读。

建议保守标识符规则：

```regex
^[a-z_][a-z0-9_]{0,63}$
```

若 Doris 支持更长标识符，本项目 P0 仍可坚持 64 字符上限，降低转义与多系统兼容风险。

---

## 8.2 创建状态机

```text
(no binding)
   ↓ create
CREATING
 ├─ Doris 创建并验证成功 -> READY
 └─ 失败 -> ERROR

READY
 └─ reconcile 实际不存在 -> MISSING

MISSING/ERROR
 └─ retry/reconcile
      ├─ 实际已存在 -> READY
      └─ 不存在 -> 重试 CREATE

READY
 └─ delete（必须无活动表） -> DELETING -> DELETED
```

---

## 8.3 删除约束

P0 ODS Database 只有在：

```text
不存在 resource_status ∈ {PENDING_CREATE, READY, CREATE_FAILED, MISSING, DELETING}
的有效 ODS Table Mapping
```

才允许删除。

不实现 DROP DATABASE CASCADE。

---

# 9. ODS 建表向导

P0 固定 4 步，**单表**创建。

```text
Step 1 选择源表
Step 2 ODS 表结构
Step 3 分区与生命周期
Step 4 校验并创建
```

多表向导 P1。

---

## 9.1 Step 1 — 源表

从 OpenMetadata 探查结果选择。

显示：

- 单位；
- 业务系统；
- 数据源；
- DB/Schema；
- Table；
- 字段数；
- 主键探查情况。

已经存在有效 Mapping 的源表不可再次创建第二个标准 ODS 投影。

---

## 9.2 Step 2 — 表结构

系统默认：

```text
source table ORDER -> target table order
source column ORDER_NO -> target column order_no
all ordinary target type -> STRING
```

允许用户：

- 修改目标表名；
- 修改目标字段名；
- 修改目标 Doris 类型；
- nullable；
- CHAR/VARCHAR length；
- DECIMAL precision/scale；
- 选择 Duplicate/Unique；
- 选择 Key 列。

### 表模型

P0：

- `DUPLICATE`；
- `UNIQUE`。

OM 有 PK 时：

- Unique 模型预选源 PK 对应目标字段；
- Duplicate 模型把源 PK 作为排序 Key 候选。

OM 无 PK：

- Duplicate 默认排序 Key = 第一个目标字段，可改；
- Unique 必须用户选择稳定业务唯一键；若为空则 Error。

### Key 约束

- Key 列必须存在；
- Key 列不允许 FLOAT/DOUBLE/复杂类型；
- 生成 DDL 时 Key 字段必须排在 Value 字段之前；
- `sourceOrdinal` 仍保留源顺序，DDL Builder 自己做输出重排。

Duplicate Key 仅代表排序 Key，UI 不得称“主键”。

---

## 9.3 Step 3 — 分区与生命周期

默认：

```text
Partition disabled
Lifecycle permanent
```

若启用时间分区：

- 用户选择目标字段；
- 只列 DATE/DATETIME + NOT NULL；
- 粒度 DAY/MONTH/YEAR。

生成：

```sql
AUTO PARTITION BY RANGE(date_trunc(`create_time`, 'day')) ()
```

### 生命周期

若永久保留：

- 可有 Auto Range Partition；
- 不写 `partition.retention_count`。

若保留历史分区：

```text
partitionGranularity = DAY
retentionCount = 180
```

UI：

> 保留 180 个日历史分区（约 180 天，实际自然时间跨度取决于分区是否连续）。

### Unique + Partition

若：

```text
partitionColumn not in uniqueKeyColumns
```

直接 Error，不自动修改 Unique Key。

---

## 9.4 Step 4 — Preview

后端必须返回：

- `valid`；
- `warnings`；
- `errors`；
- canonical target contract；
- generated DDL。

Warning 不阻塞：

```text
SOURCE_TIME_TARGET_STRING
SOURCE_NUMBER_TARGET_STRING
COMPLEX_SOURCE_TO_STRING_RISK
STRING_KEY_OPTIMIZATION_HINT
```

Error 阻塞：

```text
INVALID_IDENTIFIER
DUPLICATE_TARGET_COLUMN
INVALID_DORIS_TYPE
INVALID_KEY
INVALID_PARTITION_COLUMN
PARTITION_COLUMN_NULLABLE
UNIQUE_PARTITION_KEY_MISMATCH
INVALID_RETENTION_COUNT
TARGET_TABLE_ALREADY_EXISTS
SOURCE_OBJECT_MISSING
SOURCE_ALREADY_MAPPED
```

---

# 10. Target Contract v1

P0 Contract 使用 JSON 字符串，必须有 `version`。

```json
{
  "version": 1,
  "tableModel": "DUPLICATE",
  "columns": [
    {
      "sourceName": "ID",
      "sourceOrdinal": 1,
      "sourceTypeDisplay": "NUMBER(20)",
      "targetName": "id",
      "targetType": {
        "base": "STRING"
      },
      "nullable": false,
      "comment": "业务ID"
    },
    {
      "sourceName": "CREATE_TIME",
      "sourceOrdinal": 2,
      "sourceTypeDisplay": "DATE",
      "targetName": "create_time",
      "targetType": {
        "base": "DATETIME"
      },
      "nullable": false
    }
  ],
  "keyColumns": ["id"],
  "partition": {
    "enabled": true,
    "column": "create_time",
    "granularity": "DAY"
  },
  "distribution": {
    "type": "RANDOM",
    "columns": [],
    "buckets": "AUTO"
  },
  "lifecycle": {
    "enabled": true,
    "retentionCount": 180
  }
}
```

### Target type 必须结构化

不要保存成难解析的单字符串：

```json
{"base":"STRING"}
{"base":"VARCHAR","length":255}
{"base":"DECIMAL","precision":18,"scale":2}
{"base":"DATETIME"}
```

这样 Validator/DDL Builder 不需要重新解析类型字符串。

---

# 11. Doris DDL Builder

## 11.1 基本原则

建议职责：

```java
DorisDdlBuilder.buildCreateTable(databaseName, tableName, TargetContract)
```

只接受已经 Validator 通过的 Contract。

不得包含任何源 DataSource 类型判断。

---

## 11.2 Duplicate / 无生命周期

```sql
CREATE TABLE `ods_yjs01_erp_prod`.`order` (
  `id` STRING NOT NULL,
  `order_no` STRING NULL
)
DUPLICATE KEY(`id`)
DISTRIBUTED BY RANDOM BUCKETS AUTO;
```

---

## 11.3 Duplicate / Auto Range + Retention

```sql
CREATE TABLE `ods_yjs01_erp_prod`.`log` (
  `log_time` DATETIME NOT NULL,
  `id` STRING NOT NULL,
  `message` STRING NULL
)
DUPLICATE KEY(`log_time`, `id`)
AUTO PARTITION BY RANGE(date_trunc(`log_time`, 'day')) ()
DISTRIBUTED BY RANDOM BUCKETS AUTO
PROPERTIES (
  "partition.retention_count" = "180"
);
```

---

## 11.4 Unique

```sql
CREATE TABLE `ods_yjs01_erp_prod`.`order` (
  `id` STRING NOT NULL,
  `order_no` STRING NULL
)
UNIQUE KEY(`id`)
DISTRIBUTED BY HASH(`id`) BUCKETS AUTO;
```

Doris 4.1.2 Unique Key 默认 Merge-on-Write，无需额外强加 Web 不管理的属性。

---

## 11.5 Unique + 生命周期

仅当分区字段本来就在 Unique Key：

```sql
CREATE TABLE `ods_yjs01_erp_prod`.`event` (
  `id` STRING NOT NULL,
  `event_time` DATETIME NOT NULL,
  `payload` STRING NULL
)
UNIQUE KEY(`id`, `event_time`)
AUTO PARTITION BY RANGE(date_trunc(`event_time`, 'day')) ()
DISTRIBUTED BY HASH(`id`, `event_time`) BUCKETS AUTO
PROPERTIES (
  "partition.retention_count" = "180"
);
```

---

## 11.6 Bucket 策略

P0：

- Duplicate：`DISTRIBUTED BY RANDOM BUCKETS AUTO`；
- Unique：`DISTRIBUTED BY HASH(key...) BUCKETS AUTO`；
- 不在 UI 暴露 bucket count；
- 不写 `replication_num`，继承 Doris/集群默认。

已知风险：AUTO RANGE + Auto Bucket 在一次历史全量写入大量分区时，桶推断未必最优。P0 仍采用 AUTO 以降低运维门槛；这是性能优化项，不作为合同 P0 功能。必要时可通过平台配置预留：

```yaml
lake:
  doris:
    bucket-mode: AUTO
```

P1 可增加 profile-based/fixed bucket 策略。

---

# 12. Doris SQL 安全

## 12.1 标识符

SQL 参数不能绑定 database/table/column 标识符，因此必须：

1. 先通过严格 Regex/长度验证；
2. 使用统一 `DorisIdentifier.quote()` 包裹反引号；
3. 禁止任何 Controller/Service 直接拼 raw UI 字符串。

P0 系统生成目标标识符只接受：

```regex
^[a-z_][a-z0-9_]{0,63}$
```

---

## 12.2 SQL Literal / Property

建立 `DorisSqlLiteral`/`DorisPropertyEscaper`：

- Catalog Properties 的字符串值统一安全转义；
- 密码只在服务端运行时进入 SQL，不进入日志；
- 日志打印 Catalog DDL 时必须对 password/secret 做 `******` 脱敏。

---

# 13. DorisLakeClient

## 13.1 连接方式

后端通过平台配置的 Doris DataSource/JDBC（MySQL 协议）访问 Doris。

**不要调用 8030 Playground 页面接口，也不要模拟浏览器 Web Console。**

从 `lake.datasource-id` 找到现有 DataSource，使用现有连接服务/连接参数管理能力创建 Doris 管理连接。

如果仓库已经存在通用 JDBC execute/query 能力，必须复用。

---

## 13.2 建议接口

```java
interface DorisLakeClient {
    boolean databaseExists(String database);
    void createDatabase(String database);
    void dropDatabase(String database);

    boolean tableExists(String database, String table);
    List<DorisTableSummary> listTables(String database);
    void executeDdl(String sql);
    String showCreateTable(String database, String table);
    DorisTableActualContract readTableContract(String database, String table);
    List<DorisPartitionInfo> listPartitions(String database, String table);
    Map<String, String> readTableProperties(String database, String table);

    boolean catalogExists(String catalog);
    void createCatalog(CatalogCreateSpec spec);
    void alterCatalog(CatalogUpdateSpec spec);
    void dropCatalog(String catalog);
    void refreshCatalog(String catalog);
    CatalogMetadata listCatalogMetadata(String catalog);
    QueryResult executeReadOnly(String sql, int maxRows);
}
```

---

## 13.3 实际元数据读取

优先：

- `information_schema.schemata`；
- `information_schema.tables`；
- `information_schema.columns`；
- `information_schema.partitions`；
- `information_schema.table_properties`；
- `SHOW CREATE TABLE`。

不要维护一套长期 Doris 元数据缓存表。

---

# 14. DorisContractReader 与 Target Drift

## 14.1 不比较原始 SHOW CREATE 字符串

禁止：

```text
local generated SQL == SHOW CREATE TABLE raw SQL
```

因为 Doris 会调整格式、属性顺序、默认值，容易误报。

---

## 14.2 读取并规范化

推荐：

- Column：`information_schema.columns`；
- Properties：`information_schema.table_properties`；
- Partition 实际信息：`information_schema.partitions`；
- Table Model / Key / Distribution / Auto Partition：对 `SHOW CREATE TABLE` 做**有限模式解析**。

P0 不引入完整 SQL Parser。

有限解析至少识别：

```regex
(DUPLICATE|UNIQUE)\s+KEY\s*\((.*?)\)
AUTO\s+PARTITION\s+BY\s+RANGE\s*\(.*date_trunc\s*\((.*?),\s*'(day|month|year)'\).*
DISTRIBUTED\s+BY\s+RANDOM
DISTRIBUTED\s+BY\s+HASH\s*\((.*?)\)
BUCKETS\s+AUTO
```

实现时需要针对 Doris 4.1.2 实际 `SHOW CREATE TABLE` 输出写单元测试，不要只测试自己生成的字符串。

---

## 14.3 Drift 比较范围

MANAGED 只比较 Web 管理部分：

- target columns name/type/nullability；
- Table Model；
- Key；
- Auto Range partition column/granularity；
- distribution mode/columns；
- `partition.retention_count`（如果 Contract enabled）。

忽略：

- comment 差异（P0 可不作为强 Drift）；
- Doris 运行期/内部属性；
- stats；
- replication 默认值；
- 未由 Web 管理的表属性。

---

# 15. MANAGED 表创建与幂等

流程：

```text
1. 服务端重新获取 OM source table
2. 校验用户 Draft
3. 生成 canonical Source Snapshot + Hash
4. 生成 TargetContract + Hash + DDL
5. 检查本地冲突
6. 检查 Doris target 不存在
7. INSERT mapping(PENDING_CREATE, MANAGED)
8. Doris CREATE TABLE
9. readTableContract 验证
10. 一致 -> READY / target=CONSISTENT
11. 若有 lifecycle -> 创建 lifecycle binding ACTIVE
```

失败：

```text
resource_status = CREATE_FAILED
error_code/error_message
```

### 重试

若目标不存在：重新执行 CREATE。

若目标已存在：

- 实际 Contract 等于 desired -> READY；
- 不等 -> READY + `target_consistency=DRIFT`，不得 DROP/覆盖。

---

# 16. 数据引接模块桥接

## 16.1 边界

物理入湖不新建 `PhysicalIngestTask`。

已有数据引接任务仍是唯一 ETL 任务体系。

湖模块只提供：

```text
ODS 资源准备
+
Source -> ODS Mapping
+
现有任务关联/校验
```

---

## 16.2 Hook 点

实现 `LakeIngestBridgeService`（名称可适配现有代码）：

```java
void validateBeforeJobSave(JobDraft draft);
void afterJobSaved(JobDefinition job);
void afterJobDeleted(Long jobId);
void reconcileJob(Long jobId);
```

Codex 必须在当前结构化任务 DTO/模型层找到适当 Hook，**不要解析最终 HOCON**。

---

## 16.3 什么任务属于物理入湖

同时满足：

```text
sinkDataSourceId == configured lakeDataSourceId
AND
sinkDatabase is registered lake_ods_database_binding
```

其它写 Doris 的普通任务不纳入 ODS 管理。

---

## 16.4 源一致性

命中 ODS DB 后：

```text
job.sourceDataSourceId
==
odsBinding.sourceDataSourceId
```

否则阻止保存：

```text
LAKE_SOURCE_MISMATCH
```

避免不同业务源混写同一 ODS Database。

---

## 16.5 Schema Save Mode

### MANAGED target

强制：

```text
ERROR_WHEN_SCHEMA_NOT_EXIST
```

MANAGED 表若被删除，任务应该失败而不是自动重建。

### 快速自动建表

允许：

```text
CREATE_SCHEMA_WHEN_NOT_EXIST
```

目标不存在时：

```text
insert mapping:
management_mode=AUTO_CREATED
resource_status=PENDING_CREATE
```

### 全部 ODS Database

禁止：

```text
RECREATE_SCHEMA
```

错误：

```text
LAKE_RECREATE_SCHEMA_FORBIDDEN
```

---

## 16.6 AUTO_CREATED 注册

### 任务保存时目标不存在

若自动建表开启：

```text
Source OM Table -> Target table
AUTO_CREATED/PENDING_CREATE
```

建立 `lake_table_job_rel`。

任务运行后，下一次刷新/Reconcile：

```text
Doris table exists -> READY
```

### 目标已经存在但无 Mapping

不能声称 SeaTunnel 创建了它。

创建/展示为：

```text
UNMANAGED + READY
```

再建立 Job Relation。

---

## 16.7 Script 模式

P0：

- 不解析自定义 Script；
- 不自动创建 Mapping；
- 如果脚本最终在 ODS DB 产生新表，物理入湖详情会把它显示在 discovered unmanaged 列表。

---

# 17. lake_table_job_rel

一个 ODS Table 可以关联多个历史/现有任务。

关系状态只表示任务定义是否仍指向该映射：

```text
ACTIVE
STALE
```

保存关系时记录结构化 endpoint snapshot：

```json
{
  "datasourceId": 101,
  "database": "ERP",
  "schema": "ERP",
  "table": "ORDER"
}
```

```json
{
  "datasourceId": 10086,
  "database": "ods_yjs01_erp_prod",
  "table": "order"
}
```

若任务后续改 target：

```text
relation -> STALE
table.task_consistency -> DRIFT
```

任务实时运行状态仍查询现有任务模块，不复制到 lake 表。

---

# 18. Reconcile / Drift

P0 没有后台 Scheduler。

触发：

- 打开物理入湖详情；
- 用户点击“刷新”；
- 建表完成；
- 数据引接任务运行/保存后的显式 Hook；
- Catalog 创建/更新/刷新/验证后。

---

## 18.1 MANAGED Table Reconcile

```text
1. OM 当前 Table
2. 计算 current schema hash
3. 比较 source_schema_hash
4. Doris table exists?
5. 读取 actual contract
6. 比较 target_contract
7. 读取 ACTIVE job relations + current job endpoints
8. 更新 resource/source/target/task consistency
```

### Source

```text
CONSISTENT / DRIFT / MISSING / UNKNOWN
```

OM UUID 不存在 -> MISSING。

OM FQN 改名但 Schema Hash 不变：

- SourceObjectRef 更新当前 FQN；
- 不因为单纯 FQN 变化标 SOURCE_DRIFT；
- 任务若仍引用旧源定位，由 Task Drift 体现。

### Target

```text
CONSISTENT / DRIFT / MISSING / UNKNOWN
```

Doris 实际表不存在：

```text
resource_status=MISSING
target_consistency=MISSING
```

### Task

无任何 ACTIVE relation：

```text
UNBOUND
```

relation endpoint 与任务当前定义不一致：

```text
DRIFT
```

---

## 18.2 AUTO_CREATED

不比较 Target Contract：

- source：可检查；
- target：只检查存在性；
- task：检查 relation；
- UI Target Drift 显示 `—`/`UNKNOWN`。

---

## 18.3 UNMANAGED

只检查 Doris 实际存在性。

用户可显式“关联源表”，必须选择明确 OM Table，禁止按名字自动猜测。

---

## 18.4 额外 Doris 表

进入 ODS Database 页面：

```text
Doris listTables(db)
-
local active mappings
```

得到 `discoveredUnmanagedTables`。

P0 默认不立刻插数据库，避免 DBA 临时表污染本地控制面。

只有用户显式“关联/纳管引用”时才创建 UNMANAGED Mapping。

---

# 19. 生命周期

## 19.1 Policy 与 Binding

`lake_lifecycle_policy` 是可复用业务模板。

Scope：

- unit；
- business system；
- datasource；
- department；
- data type。

P0 Scope 只用于：

- 筛选；
- 推荐；
- 展示适用范围。

**不做继承、不自动 apply。** 用户明确选择表后执行应用。

Binding 保存实际应用快照，因此 Policy 以后改动不会偷偷影响所有表。

---

## 19.2 P0 可配置对象

```text
MANAGED + READY
```

AUTO_CREATED / UNMANAGED：不允许。

---

## 19.3 创建时应用

建表向导已经选择生命周期时，DDL 直接带：

```text
AUTO PARTITION
+
partition.retention_count
```

CREATE/验证成功后写 Binding：

```text
ACTIVE
```

---

## 19.4 后续修改 retention_count

P0 允许：

```text
180 -> 365
```

流程：

```text
validate
↓
ALTER TABLE `db`.`table`
SET ("partition.retention_count" = "365")
↓
read properties / SHOW CREATE verify
↓
update binding snapshot
```

---

## 19.5 P0 不支持从 ACTIVE 改“永久”

Doris 文档对 `partition.retention_count` 定义为正整数历史分区数量，不应假设未文档化的 -1/0 关闭语义。

因此 P0：

- 建表时可选永久；
- 一旦应用正数 retention，允许改为其它正数；
- 不提供“关闭生命周期并永久保留”按钮；
- 如确有需求列入 P1，在验证 Doris 支持的属性移除/重建方案后实现。

`DISABLED` enum 仅预留。

---

## 19.6 F15 validation_result

```json
{
  "passed": true,
  "checks": [
    {"code":"MANAGED_TABLE_REQUIRED","passed":true},
    {"code":"TARGET_READY","passed":true},
    {"code":"AUTO_RANGE_ENABLED","passed":true},
    {"code":"PARTITION_COLUMN_EXISTS","passed":true},
    {"code":"PARTITION_COLUMN_TYPE","passed":true,"actual":"DATETIME"},
    {"code":"PARTITION_COLUMN_NOT_NULL","passed":true},
    {"code":"UNIQUE_KEY_PARTITION_COMPATIBLE","passed":true},
    {"code":"RETENTION_COUNT_VALID","passed":true,"actual":180}
  ]
}
```

这就是 F15-01 “时效性校验”的 P0 可展示验收证据。

---

# 20. Logical Lake / External Catalog

## 20.1 MANAGED Catalog

Web 负责：

- capability；
- create；
- scope update；
- refresh；
- validate；
- delete。

凭证复用现有 DataSource，但 lake binding 不存密码。

---

## 20.2 Adapter Registry

```java
interface DorisCatalogAdapter {
    boolean supports(DbType type);
    CatalogCapability capability(DataSource source);
    CatalogCreateSpec buildCreateSpec(DataSource source, CatalogMountScope scope);
    CatalogUpdateSpec buildUpdateSpec(DataSource source, CatalogMountScope scope);
    CatalogValidationTarget chooseValidationTarget(...);
    String buildValidationSql(...);
    String adapterType();
}
```

Registry 负责按现有 `DbType` 选择 Adapter。

禁止 LogicalLakeService 写大段 `if MYSQL ... else ORACLE ...`。

---

## 20.3 P0 Adapter 支持矩阵

建议 P0 必须至少：

| DataSource | 逻辑入湖 P0 |
|---|---|
| MySQL | 支持 JDBC Catalog |
| PostgreSQL | 支持 JDBC Catalog |
| Oracle | 支持 JDBC Catalog |
| Elasticsearch | 可在现有连接配置能够明确映射 Doris ES Catalog 时实现；否则降 P1 |
| Kingbase | 默认不支持，除非 Doris 4.1.2 环境实测完成独立 Adapter |
| Dameng | 默认不支持，除非实测完成 Adapter |
| HTTP | 不支持通用 External Catalog |

这与物理入湖能力无关。不支持逻辑挂载的数据源仍可物理入湖。

---

## 20.4 JDBC Driver Registry

Doris JDBC Catalog 需要 `driver_url` / `driver_class`。P0 不允许用户从页面上传 JDBC Driver。

由服务端配置：

```yaml
lake:
  datasource-id: 10086
  doris-catalog-drivers:
    MYSQL:
      driver-url: mysql-connector-j-8.x.jar
      driver-class: com.mysql.cj.jdbc.Driver
    POSTGRES_SQL:
      driver-url: postgresql-42.x.jar
      driver-class: org.postgresql.Driver
    ORACLE:
      driver-url: ojdbc8.jar
      driver-class: oracle.jdbc.OracleDriver
```

具体 `driver_url` 必须符合 Doris 部署环境要求，不能默认认为 Web JVM 中的 Connector Jar 就能被 Doris FE/BE 使用。

若 driver config 缺失：

```text
logicalSupported=false
reason=CATALOG_DRIVER_NOT_CONFIGURED
```

---

## 20.5 密码与指纹

Catalog 创建时：

```text
DataSource Service 读取服务端连接参数
↓
Adapter 构建 Catalog properties
↓
Doris CREATE/ALTER CATALOG
```

`lake_external_catalog_binding` 不保存 password/secret。

计算 `source_config_fingerprint` 时只包含：

- DbType；
- host/port；
- database/service；
- username；
- 非敏感连接选项。

**不包含密码。**

密码变更本身可通过用户点击“更新挂载/重新验证”处理，不依赖 fingerprint 自动检测。

---

## 20.6 Scope

```text
ALL
DATABASE
TABLE
```

`scope_config` 示例：

```json
{
  "databases": ["ERP"],
  "tables": [
    {
      "omEntityId": "uuid",
      "fqn": "service.db.schema.ORDER",
      "database": "ERP",
      "table": "ORDER"
    }
  ]
}
```

Doris 4.1.2 支持 `include_database_list`、`exclude_database_list`、`include_table_list` 等 Catalog 公共属性；`include_table_list` 自 4.1.0 支持。

TABLE scope 生成配置时必须同时约束涉及的 Database 与 Table，不能只设置 table list 后错误假设其它库也被限制。

### 权限声明

`include_*` 是元数据/挂载范围控制，不是安全授权。

Doris 用户真正能否 `SELECT catalog.db.table` 仍由 Doris 权限体系决定。

---

## 20.7 不统一假设源层级

MySQL、PostgreSQL、Oracle、ES 的 `database/schema/table` 映射语义不同。

Adapter 必须自行实现 OM Mount Tree -> Doris Catalog 可见名称的映射。

如果某 Adapter 无法可靠把 OM Table Scope 映射到 Doris 4.1.2 的具体 include property：

- P0 限制该 Adapter 只支持 ALL 或 DATABASE；
- 不允许用通用字符串拼接猜测。

---

## 20.8 Catalog 创建状态机

```text
(no binding)
  ↓
CREATING
 ├─ create成功 -> READY
 └─ fail -> ERROR

READY
 ├─ validate pass -> validation=PASSED
 ├─ validate fail -> validation=FAILED（resource仍READY）
 └─ actual catalog missing -> MISSING

delete -> DELETING -> DELETED
```

`resource_status` 与 `validation_status` 必须分开。

---

## 20.9 创建与验证

创建流程：

```text
1. capability
2. server-side source config
3. validate Catalog name/scope
4. precheck Doris catalog name conflict
5. INSERT CREATING
6. CREATE CATALOG
7. catalog exists
8. list database/table metadata
9. generated SELECT * FROM catalog.db.table LIMIT 1
10. READY + PASSED
```

CREATE 成功但查询失败：

```text
resource_status=READY
validation_status=FAILED
```

错误信息保留用于页面展示。

---

## 20.10 Refresh / Update

Refresh：

```sql
REFRESH CATALOG `catalog_name`;
```

Scope/connection 更新优先使用：

```text
ALTER CATALOG ... SET PROPERTIES(...)
```

Adapter 对属性是否可在线更新负责判断。

如果某类变更需要重建 Catalog：P0 可要求用户显式删除后重新创建，不要默默 drop/recreate。

---

## 20.11 Catalog 删除

执行 `DROP CATALOG`。

确认提示固定包含：

> 该操作仅删除 Doris 中的逻辑挂载关系，不会删除源数据库中的实际数据。

---

# 21. 只读查询验证

逻辑入湖必须能证明 F6-02 “跨源联合查询”。

P0 最小页面：

- 生成单表 `LIMIT 1` 验证；
- 可提供只读 SQL 验证区；
- 支持 SELECT / EXPLAIN / SHOW / DESC；
- 禁止 CREATE/ALTER/DROP/INSERT/DELETE/UPDATE/ADMIN；
- 最好使用 Doris 专用只读用户执行，而不是 Catalog 管理账号。

如果没有可靠 SQL parser，P0 可先不开放任意编辑器，只提供由 UI 选表/字段生成的 SELECT 和一个专门的跨 Catalog JOIN 验收页面。**不要用简单 startsWith("select") 作为安全边界。**

---

# 22. 智能推荐 API

Recommendation 不落库。

Request：

```json
{
  "sourceDataSourceId": 123,
  "allowPhysicalCopy": true,
  "needLifecycle": true,
  "needContinuousSync": true,
  "temporaryFederatedQuery": false
}
```

Response：

```json
{
  "recommendedMode": "PHYSICAL",
  "physicalSupported": true,
  "logicalSupported": true,
  "disabledModes": [],
  "reasons": [
    "需要配置数据生命周期",
    "需要持续数据同步"
  ],
  "logicalUnsupportedReason": null
}
```

物理能力 P0 基本要求：

- 湖 Doris 配置可用；
- 源存在可用 OM Table 探查结果；
- 现有数据引接支持该 Source -> Doris Sink 路径。

逻辑能力由 Catalog Adapter + Driver/连接条件决定。

---

# 23. Service 分层

建议 Core 新增目标职责：

```text
lake/
├── LakeSourceObjectService
├── PhysicalLakeService
├── LifecycleService
├── LogicalLakeService
├── LakeRecommendationService
├── LakeReconcileService
├── LakeIngestBridgeService
└── doris/
    ├── DorisLakeClient
    ├── DorisDdlBuilder
    ├── DorisContractReader
    ├── DorisIdentifier
    ├── DorisSqlLiteral
    └── catalog/
        ├── DorisCatalogAdapter
        ├── DorisCatalogAdapterRegistry
        └── adapters/...
```

实际包路径必须遵循当前工程规范。

---

## 23.1 PhysicalLakeService

目标方法：

```java
OdsDatabaseVO createDatabase(CreateOdsDatabaseCommand command);
OdsDatabaseVO retryDatabase(Long bindingId);
PhysicalLakeDetailVO getDataSourceDetail(Long sourceDataSourceId, boolean reconcile);

ManagedTablePreviewVO previewManagedTable(ManagedTableDraftCommand command);
OdsTableVO createManagedTable(CreateManagedTableCommand command);
OdsTableVO retryManagedTable(Long mappingId);
void deleteTable(Long mappingId);

UnmanagedTableVO bindUnmanagedTable(BindUnmanagedTableCommand command);
```

---

## 23.2 LifecycleService

```java
LifecycleValidationVO validate(LifecycleDraft draft);
LifecycleBindingVO apply(ApplyLifecycleCommand command);
LifecycleBindingVO updateRetention(UpdateLifecycleRetentionCommand command);

List<LifecyclePolicyVO> listPolicies(...);
LifecyclePolicyVO createPolicy(...);
LifecyclePolicyVO updatePolicy(...);
void disablePolicy(Long policyId);
```

P0 不提供 active binding -> permanent disable。

---

## 23.3 LogicalLakeService

```java
LogicalCapabilityVO capability(Long sourceDataSourceId);
CatalogBindingVO create(CreateCatalogCommand command);
CatalogBindingVO update(UpdateCatalogCommand command);
CatalogValidationVO validate(Long bindingId);
CatalogBindingVO refresh(Long bindingId);
void delete(Long bindingId);
CatalogDetailVO detail(Long bindingId, boolean reconcile);
```

---

## 23.4 LakeReconcileService

```java
DatabaseReconcileResult reconcileDatabase(Long bindingId);
TableReconcileResult reconcileTable(Long mappingId);
CatalogReconcileResult reconcileCatalog(Long bindingId);
```

P0 所有方法同步、按需执行，不做自动后台任务。

---

# 24. API 设计

实际 URL 前缀、统一 Result 包装、权限注解必须遵循现有 Controller 规范。下列仅作为资源语义目标。

## 24.1 Recommendation

```http
POST /api/v1/lake/recommend
```

## 24.2 Physical

```http
GET  /api/v1/lake/physical/datasources
GET  /api/v1/lake/physical/datasources/{sourceDataSourceId}

POST /api/v1/lake/physical/datasources/{sourceDataSourceId}/database
POST /api/v1/lake/physical/databases/{bindingId}/retry
DELETE /api/v1/lake/physical/databases/{bindingId}

POST /api/v1/lake/physical/tables/preview
POST /api/v1/lake/physical/tables
POST /api/v1/lake/physical/tables/{mappingId}/retry
POST /api/v1/lake/physical/tables/{mappingId}/reconcile
DELETE /api/v1/lake/physical/tables/{mappingId}

POST /api/v1/lake/physical/unmanaged/bind
```

## 24.3 Lifecycle

```http
GET  /api/v1/lake/lifecycle/policies
POST /api/v1/lake/lifecycle/policies
PUT  /api/v1/lake/lifecycle/policies/{id}
POST /api/v1/lake/lifecycle/policies/{id}/disable

POST /api/v1/lake/lifecycle/validate
POST /api/v1/lake/lifecycle/apply
PUT  /api/v1/lake/lifecycle/tables/{mappingId}/retention
```

## 24.4 Logical

```http
GET    /api/v1/lake/logical/datasources/{sourceDataSourceId}/capability
GET    /api/v1/lake/logical/catalogs
POST   /api/v1/lake/logical/catalogs
GET    /api/v1/lake/logical/catalogs/{id}
PUT    /api/v1/lake/logical/catalogs/{id}
DELETE /api/v1/lake/logical/catalogs/{id}
POST   /api/v1/lake/logical/catalogs/{id}/refresh
POST   /api/v1/lake/logical/catalogs/{id}/validate
POST   /api/v1/lake/logical/catalogs/{id}/reconcile
```

## 24.5 引接桥接

优先做内部 Service Hook，不开放前端 API。

---

# 25. DTO 基线

## 25.1 ManagedTableDraftCommand

```json
{
  "sourceDataSourceId": 101,
  "omEntityId": "uuid",
  "targetTableName": "order",
  "tableModel": "DUPLICATE",
  "columns": [
    {
      "sourceName": "ID",
      "targetName": "id",
      "targetType": {"base":"STRING"},
      "nullable": false
    },
    {
      "sourceName": "CREATE_TIME",
      "targetName": "create_time",
      "targetType": {"base":"DATETIME"},
      "nullable": false
    }
  ],
  "keyColumns": ["id"],
  "partition": {
    "enabled": true,
    "column": "create_time",
    "granularity": "DAY"
  },
  "lifecycle": {
    "enabled": true,
    "policyId": 12,
    "retentionCount": 180
  }
}
```

服务端必须根据 `omEntityId` 重新获取源结构，不相信 `sourceName/sourceType` 的事实性。

---

## 25.2 Preview Response

```json
{
  "valid": true,
  "warnings": [
    {
      "code": "SOURCE_TIME_TARGET_STRING",
      "column": "update_time",
      "message": "源字段为时间类型，当前按 STRING 保存；如需按时间查询可调整类型"
    }
  ],
  "errors": [],
  "ddl": "CREATE TABLE ...",
  "targetContract": {},
  "sourceSchemaHash": "..."
}
```

---

## 25.3 PhysicalLakeDetailVO

建议结构：

```json
{
  "sourceDataSource": {},
  "databaseBinding": {},
  "managedTables": [],
  "autoCreatedTables": [],
  "linkedUnmanagedTables": [],
  "discoveredUnmanagedTables": [],
  "summary": {
    "managedCount": 12,
    "autoCreatedCount": 3,
    "driftCount": 2,
    "missingCount": 0
  }
}
```

---

# 26. 页面设计

## 26.1 一级菜单

```text
数据入湖
├── 物理入湖
├── 逻辑入湖
└── 生命周期管理
```

现有“数据源”卡片/列表操作增加：

```text
探查
智能推荐
物理入湖
逻辑入湖（能力不支持时 disabled + reason）
```

---

## 26.2 物理入湖列表

业务主视角，不做 Doris Tree 首页。

| 单位 | 业务系统 | 数据源 | ODS DB | 托管表 | 自动表 | 异常 | 状态 |
|---|---|---|---|---:|---:|---:|---|

操作：

- 进入详情；
- 首次创建 ODS DB。

---

## 26.3 物理入湖详情

顶部：

```text
DataSource / 单位 / 系统
ODS Database
DB resource status
最后对账时间
[刷新]
[创建 ODS 表]
```

Tab：

```text
ODS资源
关联引接
```

ODS 资源表：

| 源表 | ODS表 | 管理模式 | Resource | 生命周期 | Source | Target | Task | 操作 |
|---|---|---|---|---|---|---|---|---|

例：

```text
ORDER -> order | MANAGED | READY | 180日分区 | 正常 | 正常 | 正常
PAYMENT -> payment | AUTO_CREATED | READY | 未配置 | 正常 | — | 正常
CUSTOMER -> customer | MANAGED | READY | 永久 | 源变化 | 正常 | 未绑定
```

Discovered Unmanaged 单独区域：

```text
temp_test | Doris实际存在 | 未纳管 | [查看] [关联源表]
```

---

## 26.4 ODS Table 详情

Tab：

```text
基本信息
表结构
分区/生命周期
数据引接
一致性
```

MANAGED 一致性页展示：

- 创建时 Source Snapshot；
- OM 当前结构变化摘要；
- Target Desired vs Actual 差异；
- Job endpoint drift。

P0 没有“自动修复”按钮。

---

## 26.5 Logical Lake

列表：

| 单位 | 系统 | 数据源 | Catalog | Scope | Resource | Validation | 操作 |
|---|---|---|---|---|---|---|---|

详情：

```text
基本信息
挂载资源
查询验证
```

创建向导：

```text
Step 1 数据源/Capability
Step 2 Catalog Name
Step 3 ALL/DB/Table Scope
Step 4 创建并验证
```

---

## 26.6 Lifecycle

策略列表：

| 策略 | 粒度 | 历史分区保留数 | 约等时间 | Scope | 应用表数 | 状态 |
|---|---|---:|---|---|---:|---|

表详情/建表向导可选择已有策略或直接填写自定义 retention，最终 Binding 保存实际快照。

---

# 27. 删除与危险操作

## 27.1 Table

MANAGED/AUTO_CREATED 可删除，但必须：

- 检查是否有运行中的关联任务；
- 如果有，阻止并要求先停任务；
- 二次确认“删除 Doris 物理表及数据”。

流程：

```text
READY -> DELETING -> DROP TABLE -> verify missing -> DELETED
```

历史 Mapping 不物理删除。

UNMANAGED：P0 不提供 DROP，只能解除本地关联。

---

## 27.2 DataSource 删除保护

删除 Source DataSource 前，如果存在：

- 未 DELETED 的 ODS Database Binding；
- 未 DELETED 的 External Catalog Binding；

阻止删除：

```text
LAKE_DATASOURCE_IN_USE
```

先清理对应湖投影。

平台配置的 Lake Doris DataSource 若仍有任何湖资源，也不允许删除。

---

# 28. 错误码

至少定义：

```text
LAKE_NOT_CONFIGURED
LAKE_DORIS_UNAVAILABLE

LAKE_DB_ALREADY_BOUND
LAKE_DB_NAME_CONFLICT
LAKE_DB_CREATE_FAILED
LAKE_DB_NOT_EMPTY

LAKE_SOURCE_OBJECT_NOT_FOUND
LAKE_SOURCE_ALREADY_MAPPED
LAKE_SOURCE_MISMATCH

LAKE_TABLE_NAME_CONFLICT
LAKE_TABLE_CREATE_FAILED
LAKE_TABLE_NOT_FOUND
LAKE_TABLE_TARGET_DRIFT

LAKE_INVALID_IDENTIFIER
LAKE_INVALID_DORIS_TYPE
LAKE_INVALID_TABLE_MODEL
LAKE_INVALID_KEY
LAKE_INVALID_PARTITION
LAKE_INVALID_LIFECYCLE

LAKE_RECREATE_SCHEMA_FORBIDDEN
LAKE_RUNNING_JOB_EXISTS

LAKE_CATALOG_UNSUPPORTED
LAKE_CATALOG_DRIVER_NOT_CONFIGURED
LAKE_CATALOG_NAME_CONFLICT
LAKE_CATALOG_CREATE_FAILED
LAKE_CATALOG_VALIDATION_FAILED
LAKE_CATALOG_CONFIG_DRIFT

LAKE_DATASOURCE_IN_USE
```

错误响应必须保留可读 message 和可稳定判断的 code。

---

# 29. 事务、幂等与并发

## 29.1 本地事务边界

本地表插入/更新使用正常数据库事务。

外部 Doris 操作不纳入数据库事务，采用 PENDING/ERROR 状态机。

---

## 29.2 并发创建

唯一索引是最终保护：

```text
source datasource -> one ODS DB
ODS db + source object -> one source projection
ODS db + table name -> unique target
source datasource -> one Catalog
lake datasource + catalog name -> unique
```

Service 在执行外部 DDL 前先做显式查询，数据库 unique constraint 处理竞态。

---

## 29.3 Retry

所有 retry 必须先查询 Doris 实际状态，再决定是否重复执行外部操作。

禁止“看到本地 ERROR 就直接 DROP+重建”。

---

# 30. 权限与安全

P0 至少需要区分：

- Lake 管理：Create/Drop DB/Table/Catalog、ALTER property；
- Lake 查看：查看资源/元数据；
- 逻辑查询验证：只读 SELECT；
- 生命周期管理：创建策略、应用 retention。

具体接入现有权限体系，不新造 RBAC。

Doris 管理连接和只读验证连接最好为不同账号：

```text
lake.admin Doris account
lake.readonly Doris account
```

若项目 P0 暂时只有一个账号，也必须让 SQL 验证功能拒绝写 SQL，并把“专用只读账号”列为部署建议。

Catalog source password、Doris password 禁止出现在普通日志。

---

# 31. 配置项

建议：

```yaml
lake:
  enabled: true
  datasource-id: 10086
  doris:
    bucket-mode: AUTO
    readonly-datasource-id: null # 可选，如没有则使用管理连接但禁写验证
  catalog-drivers:
    MYSQL:
      driver-url: mysql-connector-j-8.x.jar
      driver-class: com.mysql.cj.jdbc.Driver
    POSTGRES_SQL:
      driver-url: postgresql-42.x.jar
      driver-class: org.postgresql.Driver
    ORACLE:
      driver-url: ojdbc8.jar
      driver-class: oracle.jdbc.OracleDriver
```

实际配置机制/命名遵循当前 Spring Boot 项目规范。

---

# 32. 测试设计

## 32.1 单元测试

### TargetContractValidator

覆盖：

- STRING 默认；
- Duplicate Key；
- Unique Key；
- Key 列不存在；
- FLOAT/DOUBLE key；
- DATE/DATETIME lifecycle；
- nullable lifecycle；
- Unique + Partition mismatch；
- invalid retention；
- duplicate target names。

### DorisDdlBuilder

Golden tests：

- Duplicate basic；
- Duplicate Auto Range；
- Unique basic；
- Unique Auto Range；
- VARCHAR/CHAR/DECIMAL；
- key columns 自动前置；
- identifier quoting。

### DorisContractReader

用 Doris 4.1.2 真实/代表性 SHOW CREATE fixture：

- Duplicate；
- Unique；
- Auto Partition；
- RANDOM/HASH；
- retention property。

### LakeRecommendationService

决策树全分支。

### Catalog Adapter

每个 Adapter：

- source config -> safe properties；
- scope；
- validation target；
- secret 不进入 persisted spec/log。

---

## 32.2 集成测试

优先配置真实 Doris 4.1.2 测试环境。

测试：

1. create ODS DB -> exists -> READY；
2. 网络异常 -> ERROR -> retry 幂等；
3. create MANAGED table -> actual contract equals desired；
4. DBA ALTER column/property -> TARGET_DRIFT；
5. DBA DROP table -> MISSING；
6. OM schema hash change -> SOURCE_DRIFT；
7. ingestion auto-create -> PENDING_CREATE -> READY；
8. RECREATE_SCHEMA -> save blocked；
9. different source writing bound ODS DB -> blocked；
10. Catalog create -> list -> query validation；
11. source DataSource config change -> Catalog config drift；
12. retention update -> Doris property updated and Binding snapshot updated。

---

# 33. 合同验收场景

## 33.1 F6-01 物理入湖

### Demo A：推荐

```text
Oracle ERP
↓
智能推荐
↓
需要生命周期 + CDC
↓
推荐物理入湖（给出原因）
```

### Demo B：规范 ODS

```text
OM ORDER
↓
创建 ods_xxx DB
↓
ODS向导：默认STRING
↓
create_time 改 DATETIME
↓
CREATE MANAGED TABLE
↓
创建/跳转现有数据引接任务
↓
SeaTunnel写入
↓
物理入湖页面看到 Doris 数据/任务状态
```

### Demo C：快速自动建表

```text
已有 ODS DB
↓
直接从数据引接创建任务
↓
CREATE_SCHEMA_WHEN_NOT_EXIST
↓
AUTO_CREATED/PENDING_CREATE
↓
任务运行
↓
Reconcile -> READY
```

证明物理入湖不是唯一创建入口，但资源统一管理。

---

## 33.2 F6-02 逻辑入湖

```text
MySQL CRM -> ext_crm
Oracle ERP -> ext_erp
↓
Catalog metadata validate
↓
SELECT / JOIN：
ext_crm.xxx.customer
JOIN ext_erp.xxx.orders
↓
返回结果
```

证明：

- 数据未搬移；
- Doris 统一元数据访问；
- 跨源联合查询。

---

## 33.3 F15-01 生命周期

```text
MANAGED LOG表
log_time DATETIME NOT NULL
Auto Range DAY
retention_count=30
↓
时效性校验全部通过
↓
页面展示：保留30个日历史分区（约30天）
↓
Doris实际Properties/Partition展示
```

证明：

- 时效性规则在建模/引接前完成配置校验；
- 暂存周期由 Doris 原生分区生命周期执行。

不要在验收材料中宣称“精确自然日 TTL”或“逐行入湖前过滤”。

---

# 34. P0 / P1 / P2 冻结

## 34.1 P0 必须完成

- 单 Doris 4.1.2 湖实例；
- OM Table UUID/FQN 统一源对象身份；
- 一 Source DataSource 一 ODS DB（MVP规则）；
- ODS 单表向导；
- 默认 STRING；
- Duplicate/Unique；
- Auto Bucket；
- Auto Range；
- `partition.retention_count`；
- 生命周期配置级时效性校验；
- MANAGED/AUTO_CREATED/UNMANAGED；
- 现有 SeaTunnel 任务桥接；
- 自动建表旁路注册；
- 禁止 RECREATE_SCHEMA；
- 按需 Reconcile；
- Source/Target/Task Drift 发现；
- MANAGED External Catalog；
- MySQL/PG/Oracle Catalog Adapter；
- Catalog refresh/validate/delete；
- 最小跨源只读查询验证；
- 智能推荐。

## 34.2 P1

- 多表批量 ODS 建表向导；
- 一源多 ODS Namespace；
- 一源多 Catalog；
- Elasticsearch Catalog（若未进入P0）；
- Kingbase/Dameng Catalog 经实测后 Adapter；
- 自动类型推荐；
- MANAGED 表受控 ALTER/Schema Evolution；
- AUTO_CREATED/UNMANAGED 转 MANAGED 重建流程；
- 更灵活 Bucket 策略；
- SSD/HDD 本地冷热；
- 生命周期 ACTIVE -> 永久 的可靠处理（需先验证 Doris 支持）。

## 34.3 P2 / 非当前合同 MVP

- 通用自动源类型映射规则系统；
- 完整数据质量/Transform；
- 行级 TTL；
- Web 生命周期 Scheduler；
- S3/HDFS 远程归档；
- 多湖实例；
- Drift 自动修复；
- Script HOCON 自动解析；
- 完整 Doris DBA Playground；
- 自动接管所有 Doris 外部表。

---

# 35. Codex 开发任务拆分

建议严格按依赖实施。

## Phase 0 — 基线检查

Codex 先输出一次实现前检查结果，不改业务行为：

- 当前 migration/SQL 初始化目录；
- DAO Entity/Mapper/Repository 规范；
- Controller API prefix/Result/权限规范；
- DataSource 获取连接的现有 Service；
- OpenMetadata Table 获取入口；
- Job Draft/Definition 的结构化 save/update Hook；
- SeaTunnel `schema_save_mode` 当前在哪个 DTO/Connector 参数中；
- Doris Connector 当前配置结构。

如果本文类名与现有代码冲突，以职责合并，不新增平行类。

---

## Phase 1 — 基础控制面

- 7 张表 migration；
- Entity/Enum/Mapper/Repository；
- LakeProperties；
- LakeSourceObjectService；
- DorisIdentifier / SqlLiteral；
- DorisLakeClient 基础 Database/Table 元数据访问。

验收：Repository + Doris connectivity tests。

---

## Phase 2 — ODS Database

- PhysicalLakeService DB binding；
- 命名器；
- create/retry/reconcile/delete；
- 前端物理入湖列表/首次建库。

验收：ERROR/retry 幂等。

---

## Phase 3 — MANAGED ODS Table

- TargetContract model；
- Validator；
- DDL Builder；
- ContractReader；
- preview/create/retry；
- 4 步前端向导；
- Table detail basic/schema。

验收：Duplicate/Unique/STRING/create/drift。

---

## Phase 4 — Lifecycle

- policy/binding；
- Auto Range validation；
- retention DDL；
- update retention；
- validation result UI；
- lifecycle policy page。

验收：F15-01 demo。

---

## Phase 5 — Ingestion Bridge

- locate structured job save/update Hook；
- lake target detection；
- source ODS DB ownership validation；
- MANAGED `ERROR_WHEN_SCHEMA_NOT_EXIST`；
- `RECREATE_SCHEMA` blocked；
- AUTO_CREATED/PENDING registration；
- job relation ACTIVE/STALE；
- jump from ODS table to existing ingestion task creation/detail。

验收：auto-create旁路闭环。

---

## Phase 6 — Reconcile / Drift / Unmanaged

- source hash compare；
- target desired vs actual compare；
- task endpoint compare；
- ODS DB list difference -> discovered unmanaged；
- explicit source association；
- UI consistency states。

验收：DBA ALTER/DROP、OM source change、job target edit。

---

## Phase 7 — Logical Lake

- Catalog Adapter Registry；
- JDBC MySQL/PG/Oracle adapters；
- driver registry；
- External Catalog CRUD；
- scope；
- refresh；
- validate；
- resource/config consistency；
- UI create/detail。

验收：F6-02 two-catalog cross-source query。

---

## Phase 8 — Recommendation / Contract Acceptance

- capability resolver；
- decision tree；
- “智能推荐” UI；
- 三个合同验收路径联调；
- 文案精确性检查（尤其 retention 语义）。

---

# 36. Codex 完成标准

每个 Phase 提交必须包含：

1. 代码；
2. DB migration（如有）；
3. 单元测试；
4. 关键集成测试；
5. 新配置说明；
6. API/DTO 变更说明；
7. 对现有数据引接行为是否有影响；
8. 未完成/P1 项清单。

不得以“后续再补测试”作为 P0 完成。

---

# 37. 关键不可变设计决策摘要

以下内容已经经过需求讨论和 Review，不应由开发 Agent 自行重构：

1. **OpenMetadata Table UUID 是物理/逻辑双模共享的源对象身份。**
2. **DataSource 是连接，OM Table 是源资产，指定 Doris DataSource 是湖实例。**
3. **一源一 ODS DB、一源一 Catalog 是 P0 约束，不是永久架构。**
4. **ODS 是兼容优先 Raw ODS；普通字段默认 STRING。**
5. **P0 不做自动类型映射，不引入 SeaTunnelDataType 转换链。**
6. **现有数据引接模块是唯一 ETL 任务系统。**
7. **物理入湖向导不是 ODS 表唯一入口；SeaTunnel 自动建表是正式合法旁路。**
8. **ODS 表分 MANAGED / AUTO_CREATED / UNMANAGED 三个等级。**
9. **只有 MANAGED 表在 P0 配生命周期。**
10. **所有 ODS DB 禁止 RECREATE_SCHEMA；MANAGED 目标不存在应任务失败。**
11. **生命周期 P0 = 配置级时效性校验 + Auto Range + retention_count。**
12. **retention_count 是历史分区数量，不是精确自然日 TTL。**
13. **Unique Key 分区字段必须已经属于 Unique Key，系统不得为生命周期偷偷改变唯一键。**
14. **Doris 是实际资源事实源，本地是归属/期望/策略源；二者差异只标 Drift，不自动修。**
15. **Source/Target/Task Drift 三个维度独立。**
16. **P0 Reconcile 按需执行，不做后台周期扫描。**
17. **External Catalog 为 MANAGED；挂载范围不是 Doris 权限。**
18. **逻辑入湖是否可用由 Adapter/Driver/网络等能力决定；不支持时 UI 明确 disabled。**
19. **智能推荐是可解释规则推荐，用户最终决定。**
20. **P0 必须同时闭环 F6-01、F6-02、F15-01，不能把逻辑入湖移到 P1。**

---

# 38. 官方技术参考

实现时以 Doris 4.1.2 和当前项目 SeaTunnel 版本的官方文档为准，避免依赖博客/旧版本行为。

- Doris Data Model / Unique Key  
  https://doris.apache.org/docs/4.x/table-design/data-model/unique/

- Doris Auto Partitioning / retention_count  
  https://doris.apache.org/docs/4.x/table-design/data-partitioning/auto-partitioning/

- Doris CREATE TABLE  
  https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/CREATE-TABLE/

- Doris ALTER TABLE PROPERTY  
  https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/ALTER-TABLE-PROPERTY/

- Doris CREATE CATALOG  
  https://doris.apache.org/docs/4.x/sql-manual/sql-statements/catalog/CREATE-CATALOG/

- Doris Catalog Overview / include_database_list / include_table_list  
  https://doris.apache.org/docs/4.x/lakehouse/catalog-overview/

- Doris JDBC Catalog Overview  
  https://doris.apache.org/docs/4.x/lakehouse/catalogs/jdbc-catalog-overview/

- SeaTunnel Doris Sink 2.3.13 / schema_save_mode  
  https://seatunnel.apache.org/docs/2.3.13/connectors/sink/Doris/

---

# 39. 最终目标

完成本设计 P0 后，系统应形成以下完整闭环：

```text
                              OpenMetadata
                                  │
                             统一源对象
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
                物理入湖                    逻辑入湖
                    │                           │
              ODS Database                 External Catalog
                    │                           │
          MANAGED / AUTO / UNMANAGED            │
                    │                           │
              生命周期（托管表）               联邦查询
                    │                           │
              Doris Internal              Doris External
                    │                           │
                    └────────── Doris 4.1.2 ────┘
                           │
                   现有 SeaTunnel 引接
                           │
                    批量 / CDC / 运行监控
```

由此以最小开发范围分别闭环：

- **F6-01：智能推荐 + ODS 物理持久化 + 现有 ETL 引接；**
- **F6-02：External Catalog + 不搬数据 + 跨源联合查询；**
- **F15-01：时效性配置校验 + Doris 历史时间分区保留。**

