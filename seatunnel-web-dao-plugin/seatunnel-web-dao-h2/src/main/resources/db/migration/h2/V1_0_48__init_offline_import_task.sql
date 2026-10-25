-- H2 equivalent of MySQL V1.0.47 for local/test profiles.
CREATE TABLE `t_seatunnel_web_offline_import_task`
(
    `id`                   bigint        NOT NULL,
    `name`                 varchar(128)  NOT NULL,
    `description`          varchar(512),
    `import_type`          varchar(32)   NOT NULL,
    `file_resource_id`     bigint        NOT NULL,
    `file_name`            varchar(512)  NOT NULL,
    `target_datasource_id` bigint        NOT NULL,
    `target_schema`        varchar(128),
    `options_json`         varchar(2048),
    `status`               varchar(24)   NOT NULL DEFAULT 'PENDING',
    `total_statements`     int           NOT NULL DEFAULT 0,
    `executed_statements`  int           NOT NULL DEFAULT 0,
    `failed_statements`    int           NOT NULL DEFAULT 0,
    `error_message`        varchar(2000),
    `execution_log`        text,
    `start_time`           timestamp,
    `end_time`             timestamp,
    `create_user_id`       int           NOT NULL,
    `create_time`          timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`          timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE INDEX `idx_offline_import_task_owner`
    ON `t_seatunnel_web_offline_import_task` (`create_user_id`, `create_time`);
CREATE INDEX `idx_offline_import_task_type_status`
    ON `t_seatunnel_web_offline_import_task` (`import_type`, `status`);
