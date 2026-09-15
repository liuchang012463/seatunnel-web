CREATE TABLE `t_seatunnel_web_file_resource`
(
    `id`                bigint        NOT NULL COMMENT '资源ID',
    `owner_id`          int           NOT NULL COMMENT '资源所属用户ID',
    `provider_type`     varchar(32)   NOT NULL COMMENT '对象存储Provider类型',
    `bucket`            varchar(255)  NOT NULL COMMENT '对象存储Bucket',
    `object_key`        varchar(1024) NOT NULL COMMENT '对象存储Key',
    `logical_path`      varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '资源库逻辑路径',
    `logical_path_hash` char(64)      NOT NULL COMMENT '逻辑路径SHA-256，用于精确去重',
    `name`              varchar(512)  NOT NULL COMMENT '文件或目录名称',
    `resource_type`     varchar(16)   NOT NULL COMMENT 'FILE/DIRECTORY',
    `size`              bigint        NOT NULL DEFAULT 0 COMMENT '文件字节数；目录为0',
    `content_type`      varchar(255)           DEFAULT NULL COMMENT '文件MIME类型',
    `etag`              varchar(128)           DEFAULT NULL COMMENT '对象ETag',
    `status`            varchar(24)   NOT NULL DEFAULT 'READY' COMMENT 'READY/DELETED',
    `create_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_resource_owner_path_hash` (`owner_id`, `logical_path_hash`),
    KEY `idx_file_resource_owner_path` (`owner_id`, `logical_path`(700)),
    KEY `idx_file_resource_owner_status` (`owner_id`, `status`),
    KEY `idx_file_resource_object_key` (`provider_type`, `bucket`, `object_key`(400))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可复用文件资源';

CREATE TABLE `t_seatunnel_web_file_upload_record`
(
    `id`             bigint        NOT NULL COMMENT '上传记录ID',
    `owner_id`       int           NOT NULL COMMENT '上传用户ID',
    `provider_type`  varchar(32)   NOT NULL COMMENT '对象存储Provider类型',
    `bucket`         varchar(255)  NOT NULL COMMENT '对象存储Bucket',
    `target_path`    varchar(1024) NOT NULL COMMENT '资源库目标目录',
    `total_files`    int           NOT NULL DEFAULT 0 COMMENT '本次上传文件数量',
    `total_size`     bigint        NOT NULL DEFAULT 0 COMMENT '本次上传总字节数',
    `status`         varchar(24)   NOT NULL DEFAULT 'UPLOADING' COMMENT 'UPLOADING/SUCCESS/FAILED',
    `error_message`  varchar(2000)          DEFAULT NULL COMMENT '失败信息',
    `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `finish_time`    datetime               DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (`id`),
    KEY `idx_file_upload_record_owner_time` (`owner_id`, `create_time`),
    KEY `idx_file_upload_record_owner_status` (`owner_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件资源上传记录';
