-- H2 equivalent of MySQL V1.0.33 for local/test profiles.
CREATE TABLE `t_seatunnel_web_file_resource`
(
    `id`                bigint        NOT NULL,
    `owner_id`          int           NOT NULL,
    `provider_type`     varchar(32)   NOT NULL,
    `bucket`            varchar(255)  NOT NULL,
    `object_key`        varchar(1024) NOT NULL,
    `logical_path`      varchar(1024) NOT NULL,
    `logical_path_hash` varchar(64)   NOT NULL,
    `name`              varchar(512)  NOT NULL,
    `resource_type`     varchar(16)   NOT NULL,
    `size`              bigint        NOT NULL DEFAULT 0,
    `content_type`      varchar(255),
    `etag`              varchar(128),
    `status`            varchar(24)   NOT NULL DEFAULT 'READY',
    `create_time`       timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE (`owner_id`, `logical_path_hash`)
);

CREATE INDEX `idx_file_resource_owner_path`
    ON `t_seatunnel_web_file_resource` (`owner_id`, `logical_path`);
CREATE INDEX `idx_file_resource_owner_status`
    ON `t_seatunnel_web_file_resource` (`owner_id`, `status`);
CREATE INDEX `idx_file_resource_object_key`
    ON `t_seatunnel_web_file_resource` (`provider_type`, `bucket`, `object_key`);

CREATE TABLE `t_seatunnel_web_file_upload_record`
(
    `id`             bigint        NOT NULL,
    `owner_id`       int           NOT NULL,
    `provider_type`  varchar(32)   NOT NULL,
    `bucket`         varchar(255)  NOT NULL,
    `target_path`    varchar(1024) NOT NULL,
    `total_files`    int           NOT NULL DEFAULT 0,
    `total_size`     bigint        NOT NULL DEFAULT 0,
    `status`         varchar(24)   NOT NULL DEFAULT 'UPLOADING',
    `error_message`  varchar(2000),
    `create_time`    timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finish_time`    timestamp,
    PRIMARY KEY (`id`)
);

CREATE INDEX `idx_file_upload_record_owner_time`
    ON `t_seatunnel_web_file_upload_record` (`owner_id`, `create_time`);
CREATE INDEX `idx_file_upload_record_owner_status`
    ON `t_seatunnel_web_file_upload_record` (`owner_id`, `status`);
