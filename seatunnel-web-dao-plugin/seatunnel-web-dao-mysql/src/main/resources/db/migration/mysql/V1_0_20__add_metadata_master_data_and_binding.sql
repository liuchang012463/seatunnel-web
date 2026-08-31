CREATE TABLE `t_seatunnel_web_data_source_unit`
(
    `id`             bigint       NOT NULL COMMENT '主键',
    `unit_code`      varchar(128) NOT NULL COMMENT '单位编码',
    `unit_name`      varchar(256) NOT NULL COMMENT '单位名称',
    `status`         tinyint      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `remark`         varchar(1024) DEFAULT NULL COMMENT '备注',
    `create_user_id` int          DEFAULT NULL COMMENT '创建人',
    `update_user_id` int          DEFAULT NULL COMMENT '更新人',
    `create_time`    datetime     NOT NULL COMMENT '创建时间',
    `update_time`    datetime     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ds_unit_code` (`unit_code`),
    UNIQUE KEY `uk_ds_unit_name` (`unit_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源单位主数据';

CREATE TABLE `t_seatunnel_web_business_system`
(
    `id`             bigint       NOT NULL COMMENT '主键',
    `unit_id`        bigint       NOT NULL COMMENT '所属单位',
    `system_code`    varchar(128) NOT NULL COMMENT '业务系统编码',
    `system_name`    varchar(256) NOT NULL COMMENT '业务系统名称',
    `status`         tinyint      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `remark`         varchar(1024) DEFAULT NULL COMMENT '备注',
    `create_user_id` int          DEFAULT NULL COMMENT '创建人',
    `update_user_id` int          DEFAULT NULL COMMENT '更新人',
    `create_time`    datetime     NOT NULL COMMENT '创建时间',
    `update_time`    datetime     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_business_system_code` (`unit_id`, `system_code`),
    UNIQUE KEY `uk_business_system_name` (`unit_id`, `system_name`),
    KEY `idx_business_system_unit` (`unit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务系统主数据';

ALTER TABLE `t_seatunnel_web_datasource`
    ADD COLUMN `business_system_id` bigint DEFAULT NULL COMMENT '归属业务系统；历史数据可为空' AFTER `data_source_unit`,
    ADD KEY `idx_datasource_business_system` (`business_system_id`);

/* Import canonical Units only. No synthetic BusinessSystem is created for legacy rows. */
INSERT INTO `t_seatunnel_web_data_source_unit`
    (`id`, `unit_code`, `unit_name`, `status`, `create_time`, `update_time`)
SELECT 900000000000000000 + ROW_NUMBER() OVER (ORDER BY `unit_name`),
       `unit_name`,
       `unit_name`,
       1,
       NOW(),
       NOW()
FROM (
    SELECT DISTINCT TRIM(`data_source_unit`) AS `unit_name`
    FROM `t_seatunnel_web_datasource`
    WHERE `data_source_unit` IS NOT NULL
      AND TRIM(`data_source_unit`) <> ''
) AS legacy_units;

CREATE TABLE `t_seatunnel_web_metadata_binding`
(
    `id`                          bigint        NOT NULL COMMENT '主键',
    `datasource_id`               bigint        NOT NULL COMMENT 'SeaTunnel DataSource ID',
    `desired_state`               varchar(32)   NOT NULL COMMENT 'ACTIVE/DELETED',
    `sync_status`                 varchar(32)   NOT NULL COMMENT 'PENDING/SYNCING/READY/WAITING/ERROR/DELETING',
    `config_version`              bigint        NOT NULL DEFAULT 1,
    `synced_config_version`      bigint        NOT NULL DEFAULT 0,
    `metadata_triggered_version` bigint        NOT NULL DEFAULT 0,
    `om_service_id`               varchar(64)   DEFAULT NULL,
    `om_service_fqn`              varchar(512)  DEFAULT NULL,
    `om_metadata_pipeline_id`     varchar(64)   DEFAULT NULL,
    `om_metadata_pipeline_fqn`    varchar(512)  DEFAULT NULL,
    `om_profiler_pipeline_id`     varchar(64)   DEFAULT NULL,
    `om_profiler_pipeline_fqn`    varchar(512)  DEFAULT NULL,
    `scan_status`                 varchar(32)   NOT NULL DEFAULT 'NEVER',
    `scan_last_run_time`          datetime      DEFAULT NULL,
    `scan_last_success_time`      datetime      DEFAULT NULL,
    `scan_last_error`             varchar(4096) DEFAULT NULL,
    `profile_status`              varchar(32)   NOT NULL DEFAULT 'NEVER',
    `profile_last_run_time`       datetime      DEFAULT NULL,
    `profile_last_success_time`   datetime      DEFAULT NULL,
    `profile_last_error`          varchar(4096) DEFAULT NULL,
    `retry_count`                 int           NOT NULL DEFAULT 0,
    `next_retry_time`             datetime      DEFAULT NULL,
    `last_sync_error_code`        varchar(64)   DEFAULT NULL,
    `last_sync_error`             varchar(4096) DEFAULT NULL,
    `last_status_refresh_time`    datetime      DEFAULT NULL,
    `status_refresh_error`        varchar(2048) DEFAULT NULL,
    `version`                     bigint        NOT NULL DEFAULT 0,
    `create_time`                 datetime      NOT NULL,
    `update_time`                 datetime      NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_metadata_binding_datasource` (`datasource_id`),
    KEY `idx_metadata_binding_reconcile` (`sync_status`, `next_retry_time`),
    KEY `idx_metadata_binding_desired` (`desired_state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OpenMetadata 本地绑定控制面';
