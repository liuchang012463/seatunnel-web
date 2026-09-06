ALTER TABLE `t_seatunnel_web_datasource`
    ADD COLUMN `system_managed` boolean NOT NULL DEFAULT FALSE;
ALTER TABLE `t_seatunnel_web_datasource`
    ADD COLUMN `system_key` varchar(128);
CREATE UNIQUE INDEX `uk_datasource_system_key` ON `t_seatunnel_web_datasource` (`system_key`);

CREATE TABLE `t_seatunnel_web_lake_warehouse_config` (
    `id` bigint NOT NULL, `config_key` varchar(64) NOT NULL, `name` varchar(128) NOT NULL,
    `jdbc_url` varchar(1024) NOT NULL, `username` varchar(128) NOT NULL,
    `password` varchar(2048) NOT NULL, `driver_class` varchar(256) NOT NULL,
    `driver_location` varchar(512) NOT NULL, `driver_sha256` varchar(64),
    `system_data_source_id` bigint, `config_version` bigint NOT NULL DEFAULT 1,
    `conn_status` varchar(24) NOT NULL DEFAULT 'CONNECTED_NONE', `last_error` varchar(4096),
    `create_user_id` int, `update_user_id` int,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE (`config_key`)
);

CREATE TABLE `t_seatunnel_web_lake_jdbc_driver` (
    `id` bigint NOT NULL, `adapter` varchar(32) NOT NULL, `file_name` varchar(255) NOT NULL,
    `driver_location` varchar(512) NOT NULL, `driver_class` varchar(256) NOT NULL,
    `sha256` varchar(64) NOT NULL, `doris_md5` varchar(32), `enabled` boolean NOT NULL DEFAULT TRUE,
    `verified` boolean NOT NULL DEFAULT FALSE, `status` varchar(32) NOT NULL DEFAULT 'UNVERIFIED',
    `last_error` varchar(4096), `create_user_id` int, `update_user_id` int,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE (`adapter`), UNIQUE (`sha256`)
);

CREATE TABLE `t_seatunnel_web_lake_datasource_alias` (
    `id` bigint NOT NULL, `legacy_data_source_id` bigint NOT NULL,
    `canonical_data_source_id` bigint NOT NULL, `reason` varchar(255),
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`), UNIQUE (`legacy_data_source_id`)
);
