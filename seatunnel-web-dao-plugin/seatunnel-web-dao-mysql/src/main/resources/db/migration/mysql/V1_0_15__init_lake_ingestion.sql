-- 入湖管理元数据。Doris 连接参数只保存在 t_seatunnel_web_datasource，以下表只保存数据源 ID。
CREATE TABLE `t_seatunnel_web_lake_physical_resource`
(
    `id`                         bigint       NOT NULL COMMENT '主键',
    `lake_data_source_id`        bigint       NOT NULL COMMENT 'Doris 湖数据源 ID',
    `source_data_source_id`      bigint                DEFAULT NULL COMMENT '来源数据源 ID',
    `resource_name`              varchar(128) NOT NULL COMMENT '入湖资源名称',
    `source_locator`             varchar(512)          DEFAULT NULL COMMENT '来源对象定位',
    `data_type`                  varchar(64)           DEFAULT NULL COMMENT '业务数据类型',
    `department_id`              varchar(64)           DEFAULT NULL COMMENT '业务部门 ID',
    `doris_catalog`              varchar(128) NOT NULL DEFAULT 'internal' COMMENT 'Doris Catalog',
    `doris_database`             varchar(128) NOT NULL COMMENT 'Doris 目标库',
    `doris_table`                varchar(128) NOT NULL COMMENT 'Doris 目标表',
    `job_definition_id`          bigint                DEFAULT NULL COMMENT 'SeaTunnel 任务 ID',
    `job_mode`                   varchar(32)           DEFAULT NULL COMMENT '任务模式',
    `sync_mode`                  varchar(32)           DEFAULT NULL COMMENT 'FULL/INCREMENTAL/REALTIME',
    `partition_field`            varchar(128)          DEFAULT NULL COMMENT '分区字段',
    `partition_granularity`     varchar(16)           DEFAULT NULL COMMENT 'DAY/MONTH',
    `row_count`                  bigint                DEFAULT NULL COMMENT '最近行数快照',
    `storage_bytes`              bigint                DEFAULT NULL COMMENT '最近存储量快照',
    `last_sync_time`             datetime              DEFAULT NULL COMMENT '最近同步时间',
    `last_metadata_refresh_time` datetime              DEFAULT NULL COMMENT '最近元数据刷新时间',
    `retention_value`            int                   DEFAULT NULL COMMENT '有效期数值',
    `retention_unit`             varchar(16)           DEFAULT NULL COMMENT 'DAY/MONTH/YEAR',
    `status`                     varchar(32)  NOT NULL DEFAULT 'READY' COMMENT 'READY/RUNNING/ERROR/DISABLED',
    `status_reason`              varchar(1000)         DEFAULT NULL COMMENT '状态原因',
    `create_time`                datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`                datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lake_physical_target` (`lake_data_source_id`, `doris_catalog`, `doris_database`, `doris_table`),
    KEY `idx_lake_physical_source` (`lake_data_source_id`, `source_locator`),
    KEY `idx_lake_physical_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='物理入湖资源';

CREATE TABLE `t_seatunnel_web_lake_logical_mapping`
(
    `id`                    bigint       NOT NULL COMMENT '主键',
    `mapping_name`          varchar(128) NOT NULL COMMENT '逻辑映射名称',
    `lake_data_source_id`   bigint       NOT NULL COMMENT 'Doris 湖数据源 ID',
    `source_data_source_id` bigint       NOT NULL COMMENT '外部源数据源 ID',
    `source_locator`        varchar(512)          DEFAULT NULL COMMENT '外部数据库/表',
    `source_type`           varchar(32)           DEFAULT NULL COMMENT 'JDBC/HMS/ICEBERG/HUDI',
    `catalog_name`          varchar(128)          DEFAULT NULL COMMENT 'Doris External Catalog 名称',
    `catalog_type`          varchar(32)           DEFAULT NULL COMMENT 'JDBC/HMS/ICEBERG/HUDI',
    `remote_database`       varchar(128)          DEFAULT NULL COMMENT '远端数据库',
    `remote_table`          varchar(128)          DEFAULT NULL COMMENT '远端表',
    `data_type`             varchar(64)           DEFAULT NULL COMMENT '业务数据类型',
    `department_id`         varchar(64)           DEFAULT NULL COMMENT '业务部门 ID',
    `logical_database`      varchar(128) NOT NULL DEFAULT 'logical_ods',
    `logical_name`          varchar(128) NOT NULL COMMENT '统一元数据名称',
    `field_mapping_json`    text                  COMMENT '字段映射 JSON',
    `implementation_mode`   varchar(32)  NOT NULL DEFAULT 'WEB_ALIAS' COMMENT 'DORIS_VIEW/WEB_ALIAS',
    `status`                varchar(32)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/READY/ERROR/DISABLED',
    `last_check_time`       datetime              DEFAULT NULL,
    `last_check_message`    varchar(1000)         DEFAULT NULL,
    `query_latency_ms`      bigint                DEFAULT NULL,
    `create_time`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lake_logical_name` (`logical_database`, `logical_name`),
    KEY `idx_lake_logical_datasource` (`lake_data_source_id`, `source_data_source_id`),
    KEY `idx_lake_logical_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='逻辑入湖映射';

CREATE TABLE `t_seatunnel_web_lake_lifecycle_rule`
(
    `id`                       bigint       NOT NULL COMMENT '主键',
    `rule_name`                varchar(128) NOT NULL COMMENT '规则名称',
    `version`                  int          NOT NULL DEFAULT 1,
    `priority`                 int          NOT NULL DEFAULT 1,
    `status`                   varchar(32)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ENABLED/DISABLED',
    `source_data_source_id`    bigint                DEFAULT NULL COMMENT '外部/来源数据源 ID',
    `data_type`                varchar(64)           DEFAULT NULL,
    `department_id`            varchar(64)           DEFAULT NULL,
    `physical_resource_id`     bigint                DEFAULT NULL,
    `validity_value`           int          NOT NULL,
    `validity_unit`            varchar(16)  NOT NULL DEFAULT 'DAY',
    `event_time_field`         varchar(128) NOT NULL DEFAULT 'ingest_time',
    `parse_failure_action`     varchar(32)  NOT NULL DEFAULT 'QUARANTINE',
    `future_tolerance_seconds` int          NOT NULL DEFAULT 300,
    `expired_action`           varchar(32)  NOT NULL DEFAULT 'MARK_ONLY',
    `archive_target_id`        bigint                DEFAULT NULL,
    `cron_expression`          varchar(128) NOT NULL DEFAULT '0 0 3 * * ?',
    `time_zone`                varchar(64)  NOT NULL DEFAULT 'Asia/Shanghai',
    `published_at`             datetime              DEFAULT NULL,
    `published_by`             int                   DEFAULT NULL,
    `create_time`              datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`              datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_lake_lifecycle_match` (`status`, `priority`, `source_data_source_id`, `data_type`, `department_id`),
    KEY `idx_lake_lifecycle_resource` (`physical_resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据生命周期规则';

CREATE TABLE `t_seatunnel_web_lake_lifecycle_execution`
(
    `id`                    bigint      NOT NULL COMMENT '主键',
    `rule_id`               bigint      NOT NULL,
    `rule_version`          int         NOT NULL,
    `trigger_type`          varchar(32) NOT NULL DEFAULT 'SCHEDULED',
    `status`                varchar(32) NOT NULL DEFAULT 'PLANNED' COMMENT 'PLANNED/RUNNING/SUCCESS/PARTIAL/FAILED',
    `dry_run`               tinyint(1)  NOT NULL DEFAULT 1,
    `started_at`            datetime             DEFAULT NULL,
    `finished_at`           datetime             DEFAULT NULL,
    `resource_count`        int                  DEFAULT 0,
    `expired_partitions`    int                  DEFAULT 0,
    `released_storage_bytes` bigint               DEFAULT 0,
    `error_message`         varchar(2000)        DEFAULT NULL,
    `operator_id`           int                  DEFAULT NULL,
    `create_time`           datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_lake_execution_rule` (`rule_id`, `rule_version`),
    KEY `idx_lake_execution_status` (`status`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生命周期执行审计';

CREATE TABLE `t_seatunnel_web_lake_lifecycle_rule_binding`
(
    `id`             bigint      NOT NULL COMMENT '主键',
    `rule_id`        bigint      NOT NULL,
    `rule_version`   int         NOT NULL,
    `resource_id`    bigint      NOT NULL,
    `matched_at`     datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_time`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lake_rule_binding` (`rule_id`, `rule_version`, `resource_id`),
    KEY `idx_lake_rule_binding_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生命周期规则资源绑定';

CREATE TABLE `t_seatunnel_web_lake_ingestion_validation_record`
(
    `id`                    bigint       NOT NULL COMMENT '主键',
    `job_definition_id`     bigint                DEFAULT NULL,
    `job_instance_id`       varchar(128)          DEFAULT NULL,
    `resource_id`           bigint                DEFAULT NULL,
    `rule_id`               bigint                DEFAULT NULL,
    `rule_version`          int                   DEFAULT NULL,
    `total_count`           bigint       NOT NULL DEFAULT 0,
    `valid_count`           bigint       NOT NULL DEFAULT 0,
    `expired_count`         bigint       NOT NULL DEFAULT 0,
    `parse_failure_count`   bigint       NOT NULL DEFAULT 0,
    `quarantine_target`     varchar(512)          DEFAULT NULL,
    `min_event_time`        datetime              DEFAULT NULL,
    `max_event_time`        datetime              DEFAULT NULL,
    `status`                varchar(32)  NOT NULL DEFAULT 'SUCCESS',
    `error_message`         varchar(2000)         DEFAULT NULL,
    `create_time`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_lake_validation_job` (`job_definition_id`, `job_instance_id`),
    KEY `idx_lake_validation_resource` (`resource_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='引接阶段时效校验记录';

CREATE TABLE `t_seatunnel_web_lake_lifecycle_execution_detail`
(
    `id`                      bigint       NOT NULL COMMENT '主键',
    `execution_id`            bigint       NOT NULL,
    `resource_id`             bigint                DEFAULT NULL,
    `partition_name`          varchar(256)          DEFAULT NULL,
    `partition_range`         varchar(512)          DEFAULT NULL,
    `action`                  varchar(32)  NOT NULL DEFAULT 'MARK_ONLY',
    `status`                  varchar(32)  NOT NULL DEFAULT 'PLANNED',
    `estimated_row_count`     bigint                DEFAULT NULL,
    `estimated_storage_bytes` bigint                DEFAULT NULL,
    `actual_row_count`        bigint                DEFAULT NULL,
    `actual_storage_bytes`    bigint                DEFAULT NULL,
    `execution_sql`           text,
    `archive_verified`        tinyint(1)             DEFAULT NULL,
    `error_message`           varchar(2000)         DEFAULT NULL,
    `create_time`             datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`             datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_lake_execution_detail_execution` (`execution_id`, `status`),
    KEY `idx_lake_execution_detail_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生命周期执行明细';
