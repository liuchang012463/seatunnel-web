CREATE TABLE `t_seatunnel_web_unified_auth_config`
(
    `id`                         bigint       NOT NULL AUTO_INCREMENT,
    `config_key`                 varchar(32)  NOT NULL,
    `issuer`                     varchar(512)          DEFAULT NULL,
    `client_id`                  varchar(255)          DEFAULT NULL,
    `redirect_uri`               varchar(1024) NOT NULL,
    `client_secret_enc`          text                  DEFAULT NULL,
    `enabled`                    tinyint(1)   NOT NULL DEFAULT 0,
    `auto_provision_general_user` tinyint(1)  NOT NULL DEFAULT 0,
    `ticket_enabled`              tinyint(1)  NOT NULL DEFAULT 0,
    `ticket_validation_endpoint`  varchar(1024)         DEFAULT NULL,
    `ticket_validation_method`    varchar(16)           DEFAULT NULL,
    `ticket_login_entry_url`      varchar(1024)         DEFAULT NULL,
    `ticket_issuer`               varchar(512)          DEFAULT NULL,
    `ticket_parameter_name`       varchar(64)  NOT NULL DEFAULT 'ticket',
    `ticket_success_field`        varchar(128)          DEFAULT NULL,
    `ticket_success_value`        varchar(255)          DEFAULT NULL,
    `ticket_username_field`       varchar(128)          DEFAULT NULL,
    `ticket_result_issuer_pointer` varchar(256)         DEFAULT NULL,
    `ticket_result_subject_pointer` varchar(256)        DEFAULT NULL,
    `ticket_result_email_pointer` varchar(256)          DEFAULT NULL,
    `ticket_result_display_name_pointer` varchar(256)   DEFAULT NULL,
    `config_version`             bigint       NOT NULL DEFAULT 1,
    `create_time`                datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`                datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_unified_auth_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统一用户 OIDC 配置';

CREATE TABLE `t_seatunnel_web_unified_auth_identity`
(
    `id`           bigint       NOT NULL AUTO_INCREMENT,
    `identity_key` char(64)     NOT NULL,
    `identity_source` varchar(16) NOT NULL DEFAULT 'OIDC',
    `issuer`       varchar(512) NOT NULL,
    `subject`      varchar(255) NOT NULL,
    `email`        varchar(255)          DEFAULT NULL,
    `display_name` varchar(255)          DEFAULT NULL,
    `user_id`      int                   DEFAULT NULL,
    `create_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_unified_auth_identity_key` (`identity_key`),
    KEY `idx_unified_auth_identity_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统一用户外部身份映射';
