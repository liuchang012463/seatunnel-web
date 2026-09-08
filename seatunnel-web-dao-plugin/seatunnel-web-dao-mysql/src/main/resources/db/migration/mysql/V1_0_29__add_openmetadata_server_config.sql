-- Singleton OpenMetadata control-plane connection. UI under 运行运维 → 探查引擎管理
-- is the only source of truth; an empty table means OM is not configured.
CREATE TABLE `t_seatunnel_web_openmetadata_config`
(
    `id`                         bigint        NOT NULL COMMENT '主键',
    `config_key`                 varchar(64)   NOT NULL COMMENT '单例配置键：OPENMETADATA',
    `enabled`                    tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否启用 OpenMetadata 集成',
    `base_url`                   varchar(1024) NOT NULL COMMENT 'OpenMetadata /api 基址',
    `token`                      varchar(4096) NOT NULL COMMENT 'Bot JWT（明文，与湖仓密码策略一致）',
    `connect_timeout_ms`         int           NOT NULL DEFAULT 2000 COMMENT '连接超时毫秒',
    `read_timeout_ms`            int           NOT NULL DEFAULT 10000 COMMENT '读超时毫秒',
    `expected_server_version`    varchar(32)   NOT NULL DEFAULT '1.12.10' COMMENT '固定 Server 版本契约',
    `expected_ingestion_patch`   varchar(32)   NOT NULL DEFAULT '1.12.10.0' COMMENT '固定 Ingestion 版本契约',
    `kingbase_tunnel_host`       varchar(255)  DEFAULT NULL COMMENT 'Kingbase SSH 隧道主机（可选）',
    `kingbase_tunnel_port`       int           NOT NULL DEFAULT 0 COMMENT 'Kingbase SSH 隧道端口（可选）',
    `config_version`             bigint        NOT NULL DEFAULT 1 COMMENT '配置版本，变更时重建 SDK client',
    `conn_status`                varchar(24)   NOT NULL DEFAULT 'CONNECTED_NONE',
    `last_error`                 varchar(4096) DEFAULT NULL,
    `create_user_id`             int           DEFAULT NULL,
    `update_user_id`             int           DEFAULT NULL,
    `create_time`                datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`                datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_openmetadata_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OpenMetadata 探查引擎连接配置';
