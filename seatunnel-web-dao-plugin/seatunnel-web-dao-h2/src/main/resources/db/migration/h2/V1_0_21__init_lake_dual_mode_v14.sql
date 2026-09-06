-- H2 test equivalent of MySQL V1.0.21.  It intentionally keeps the same names,
-- unique constraints and columns while omitting MySQL-only engine clauses.
CREATE TABLE `t_seatunnel_web_lake_source_object_ref` (
    `id` bigint NOT NULL, `source_data_source_id` bigint NOT NULL,
    `om_entity_id` varchar(128) NOT NULL, `om_fqn` varchar(512),
    `object_type` varchar(32) NOT NULL DEFAULT 'TABLE', `source_schema_hash` varchar(64),
    `source_snapshot_json` longvarchar, `resource_status` varchar(32) NOT NULL DEFAULT 'READY',
    `lock_version` int NOT NULL DEFAULT 1, `generation` int NOT NULL DEFAULT 1,
    `operation_token` varchar(128), `error_code` varchar(128), `error_message` varchar(4096),
    `last_reconcile_at` timestamp, `create_user_id` int, `update_user_id` int,
    `deleted` boolean NOT NULL DEFAULT FALSE, `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE (`om_entity_id`)
);
CREATE INDEX `idx_lake_source_object_datasource` ON `t_seatunnel_web_lake_source_object_ref` (`source_data_source_id`, `resource_status`);

CREATE TABLE `t_seatunnel_web_lake_ods_database_binding` (
    `id` bigint NOT NULL, `lake_data_source_id` bigint NOT NULL, `source_data_source_id` bigint NOT NULL,
    `unit_code` varchar(128) NOT NULL, `system_code` varchar(128) NOT NULL, `database_name` varchar(64) NOT NULL,
    `resource_status` varchar(32) NOT NULL DEFAULT 'PENDING_CREATE', `lock_version` int NOT NULL DEFAULT 1,
    `generation` int NOT NULL DEFAULT 1, `operation_token` varchar(128), `error_code` varchar(128),
    `error_message` varchar(4096), `last_reconcile_at` timestamp, `create_user_id` int, `update_user_id` int,
    `deleted` boolean NOT NULL DEFAULT FALSE, `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`),
    UNIQUE (`source_data_source_id`), UNIQUE (`lake_data_source_id`, `database_name`)
);

CREATE TABLE `t_seatunnel_web_lake_ods_table_mapping` (
    `id` bigint NOT NULL, `source_object_ref_id` bigint NOT NULL, `ods_database_binding_id` bigint NOT NULL,
    `lake_data_source_id` bigint NOT NULL, `database_name` varchar(64) NOT NULL, `target_table_name` varchar(128) NOT NULL,
    `management_level` varchar(32) NOT NULL, `table_model` varchar(32), `source_schema_hash` varchar(64),
    `target_contract_hash` varchar(64), `source_snapshot_json` longvarchar, `target_contract_json` longvarchar,
    `field_mappings_json` longvarchar, `source_consistency_status` varchar(32), `target_consistency_status` varchar(32),
    `task_consistency_status` varchar(32), `actual_table_exists` boolean, `resource_status` varchar(32) NOT NULL DEFAULT 'PENDING_CREATE',
    `lock_version` int NOT NULL DEFAULT 1, `generation` int NOT NULL DEFAULT 1, `operation_token` varchar(128),
    `error_code` varchar(128), `error_message` varchar(4096), `last_reconcile_at` timestamp,
    `create_user_id` int, `update_user_id` int, `deleted` boolean NOT NULL DEFAULT FALSE,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE (`ods_database_binding_id`, `target_table_name`),
    UNIQUE (`ods_database_binding_id`, `source_object_ref_id`)
);

CREATE TABLE `t_seatunnel_web_lake_job_relation` (
    `id` bigint NOT NULL, `ods_database_binding_id` bigint NOT NULL, `table_mapping_id` bigint,
    `relation_scope` varchar(32) NOT NULL, `job_runtime_type` varchar(32) NOT NULL, `job_id` bigint NOT NULL,
    `job_version` int NOT NULL, `relation_status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
    `source_endpoint_snapshot` longvarchar, `sink_endpoint_snapshot` longvarchar,
    `schema_save_mode_snapshot` varchar(64), `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (`id`),
    UNIQUE (`ods_database_binding_id`, `job_id`, `relation_scope`)
);

CREATE TABLE `t_seatunnel_web_lake_lifecycle_policy` (
    `id` bigint NOT NULL, `policy_name` varchar(128) NOT NULL, `version` int NOT NULL DEFAULT 1,
    `status` varchar(32) NOT NULL DEFAULT 'DRAFT', `granularity` varchar(16) NOT NULL,
    `retention_count` int NOT NULL, `description` varchar(1024), `create_user_id` int, `update_user_id` int,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE (`policy_name`)
);

CREATE TABLE `t_seatunnel_web_lake_table_lifecycle_binding` (
    `id` bigint NOT NULL, `table_mapping_id` bigint NOT NULL, `policy_id` bigint, `policy_version` int,
    `partition_column` varchar(128) NOT NULL, `granularity` varchar(16) NOT NULL, `retention_count` int,
    `actual_retention_count` int, `policy_snapshot_json` longvarchar, `status` varchar(32) NOT NULL DEFAULT 'PENDING',
    `operation_token` varchar(128), `generation` int NOT NULL DEFAULT 1, `lock_version` int NOT NULL DEFAULT 1,
    `error_code` varchar(128), `error_message` varchar(4096), `create_user_id` int, `update_user_id` int,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE (`table_mapping_id`)
);

CREATE TABLE `t_seatunnel_web_lake_external_catalog_binding` (
    `id` bigint NOT NULL, `lake_data_source_id` bigint NOT NULL, `source_data_source_id` bigint NOT NULL,
    `catalog_name` varchar(128) NOT NULL, `adapter` varchar(32) NOT NULL, `scope` varchar(32) NOT NULL,
    `desired_spec_json` longvarchar NOT NULL, `desired_spec_hash` varchar(64) NOT NULL, `credential_revision` varchar(128),
    `validation_status` varchar(32), `resource_status` varchar(32) NOT NULL DEFAULT 'PENDING_CREATE',
    `lock_version` int NOT NULL DEFAULT 1, `generation` int NOT NULL DEFAULT 1, `operation_token` varchar(128),
    `error_code` varchar(128), `error_message` varchar(4096), `last_reconcile_at` timestamp,
    `create_user_id` int, `update_user_id` int, `deleted` boolean NOT NULL DEFAULT FALSE,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE (`source_data_source_id`), UNIQUE (`lake_data_source_id`, `catalog_name`)
);

CREATE TABLE `t_seatunnel_web_lake_resource_operation` (
    `id` bigint NOT NULL, `resource_type` varchar(64) NOT NULL, `resource_id` bigint NOT NULL,
    `generation` int NOT NULL, `operation_type` varchar(64) NOT NULL, `operation_token` varchar(128) NOT NULL,
    `request_hash` varchar(64), `status` varchar(32) NOT NULL DEFAULT 'PENDING', `started_at` timestamp,
    `finished_at` timestamp, `error_code` varchar(128), `error_summary` varchar(2000), `operator_id` int,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE (`operation_token`)
);
