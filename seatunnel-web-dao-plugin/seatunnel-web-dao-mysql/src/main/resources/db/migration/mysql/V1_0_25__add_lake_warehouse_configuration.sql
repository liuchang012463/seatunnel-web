-- Lake warehouse configuration is the control-plane source of truth.  The
-- DataSource row remains only as a system-managed SeaTunnel task projection.
ALTER TABLE `t_seatunnel_web_datasource`
    ADD COLUMN `system_managed` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否由系统能力维护' AFTER `status`,
    ADD COLUMN `system_key` varchar(128) DEFAULT NULL COMMENT '系统维护标识，如 LAKE_ODS_DORIS' AFTER `system_managed`,
    ADD UNIQUE KEY `uk_datasource_system_key` (`system_key`);

CREATE TABLE `t_seatunnel_web_lake_warehouse_config`
(
    `id`                   bigint       NOT NULL COMMENT '主键',
    `config_key`           varchar(64)  NOT NULL COMMENT '单例配置键：ODS_DORIS',
    `name`                 varchar(128) NOT NULL COMMENT '数仓名称',
    `jdbc_url`             varchar(1024) NOT NULL COMMENT 'Doris JDBC URL',
    `username`             varchar(128) NOT NULL COMMENT 'Doris 用户名',
    `password`             varchar(2048) NOT NULL COMMENT 'AES-GCM 加密密码',
    `driver_class`         varchar(256) NOT NULL COMMENT 'JDBC 驱动类',
    `driver_location`      varchar(512) NOT NULL COMMENT '共享驱动目录中的文件名或相对路径',
    `driver_sha256`        char(64)     DEFAULT NULL COMMENT '驱动 SHA-256',
    `system_data_source_id` bigint      DEFAULT NULL COMMENT '系统内置数据源投影 ID',
    `config_version`       bigint       NOT NULL DEFAULT 1 COMMENT '配置版本',
    `conn_status`          varchar(24)  NOT NULL DEFAULT 'CONNECTED_NONE',
    `last_error`           varchar(4096) DEFAULT NULL,
    `create_user_id`       int          DEFAULT NULL,
    `update_user_id`       int          DEFAULT NULL,
    `create_time`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lake_warehouse_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='湖 ODS 数仓配置';

CREATE TABLE `t_seatunnel_web_lake_jdbc_driver`
(
    `id`              bigint       NOT NULL COMMENT '主键',
    `adapter`         varchar(32)  NOT NULL COMMENT 'MYSQL/POSTGRESQL/ORACLE',
    `file_name`       varchar(255) NOT NULL,
    `driver_location` varchar(512) NOT NULL COMMENT '共享驱动目录相对路径',
    `driver_class`    varchar(256) NOT NULL,
    `sha256`          char(64)     NOT NULL,
    `doris_md5`       char(32)     DEFAULT NULL COMMENT 'Doris catalog 可选 MD5',
    `enabled`         tinyint(1)   NOT NULL DEFAULT 1,
    `verified`        tinyint(1)   NOT NULL DEFAULT 0,
    `status`          varchar(32)  NOT NULL DEFAULT 'UNVERIFIED',
    `last_error`      varchar(4096) DEFAULT NULL,
    `create_user_id`  int          DEFAULT NULL,
    `update_user_id`  int          DEFAULT NULL,
    `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lake_jdbc_driver_adapter` (`adapter`),
    UNIQUE KEY `uk_lake_jdbc_driver_sha256` (`sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='湖逻辑 Catalog JDBC 驱动注册表';

CREATE TABLE `t_seatunnel_web_lake_datasource_alias`
(
    `id`                     bigint       NOT NULL COMMENT '主键',
    `legacy_data_source_id`  bigint       NOT NULL COMMENT '历史湖数据源 ID',
    `canonical_data_source_id` bigint     NOT NULL COMMENT '系统内置数据源 ID',
    `reason`                 varchar(255) DEFAULT NULL,
    `create_time`            datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`            datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_lake_datasource_alias_legacy` (`legacy_data_source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='湖数据源 ID 兼容映射';
