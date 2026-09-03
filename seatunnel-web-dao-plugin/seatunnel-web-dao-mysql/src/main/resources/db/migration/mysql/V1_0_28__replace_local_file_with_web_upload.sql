-- Replace the abandoned LOCAL_FILE datasource implementation with browser
-- uploads backed by the platform MinIO bucket.
--
-- V1_0_27 is an applied migration and must remain immutable.  This migration
-- removes only LOCAL_FILE rows and the batch jobs which referenced them; FTP,
-- SFTP, S3 and MINIO rows are intentionally preserved.

-- The first local development run may have stopped after creating one of these
-- tables.  They are new tables owned by this migration, so make a failed retry
-- idempotent before applying the cleanup below.
DROP TABLE IF EXISTS `t_seatunnel_web_file_upload_asset`;
DROP TABLE IF EXISTS `t_seatunnel_web_file_upload_session`;

CREATE TEMPORARY TABLE `tmp_seatunnel_local_file_datasources`
(
    `id` bigint NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `tmp_seatunnel_local_file_datasources` (`id`)
SELECT `id`
FROM `t_seatunnel_web_datasource`
WHERE UPPER(`db_type`) = 'LOCAL_FILE';

CREATE TEMPORARY TABLE `tmp_seatunnel_local_file_jobs`
(
    `id` bigint NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `tmp_seatunnel_local_file_jobs` (`id`)
SELECT DISTINCT `definition`.`id`
FROM `t_seatunnel_web_job_definition` AS `definition`
LEFT JOIN `t_seatunnel_web_datasource` AS `source_ds`
    ON `source_ds`.`id` = `definition`.`source_datasource_id`
LEFT JOIN `t_seatunnel_web_datasource` AS `sink_ds`
    ON `sink_ds`.`id` = `definition`.`sink_datasource_id`
WHERE UPPER(COALESCE(`source_ds`.`db_type`, '')) = 'LOCAL_FILE'
   OR UPPER(COALESCE(`sink_ds`.`db_type`, '')) = 'LOCAL_FILE'
   OR (UPPER(COALESCE(`definition`.`mode`, '')) = 'FILE_SYNC'
       AND UPPER(COALESCE(`definition`.`source_type`, '')) = 'LOCAL_FILE');

DELETE `allocation`
FROM `t_seatunnel_web_cdc_server_id_allocation` AS `allocation`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `allocation`.`job_definition_id`;

DELETE `record`
FROM `t_seatunnel_web_incremental_batch_record` AS `record`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `record`.`job_definition_id`;

DELETE `control`
FROM `t_seatunnel_web_incremental_batch_control` AS `control`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `control`.`job_definition_id`;

DELETE `metric`
FROM `t_seatunnel_web_job_metrics` AS `metric`
JOIN `t_seatunnel_web_job_instance` AS `instance`
    ON `instance`.`id` = `metric`.`job_instance_id`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `instance`.`job_definition_id`;

DELETE `metric`
FROM `t_seatunnel_web_job_table_metrics` AS `metric`
JOIN `t_seatunnel_web_job_instance` AS `instance`
    ON `instance`.`id` = `metric`.`job_instance_id`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `instance`.`job_definition_id`;

DELETE `alarm`
FROM `t_seatunnel_web_alarm_record` AS `alarm`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `alarm`.`job_definition_id`;

DELETE `validation`
FROM `t_seatunnel_web_lake_ingestion_validation_record` AS `validation`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `validation`.`job_definition_id`;

DELETE `instance`
FROM `t_seatunnel_web_job_instance` AS `instance`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `instance`.`job_definition_id`;

DELETE `schedule`
FROM `t_seatunnel_web_job_schedule` AS `schedule`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `schedule`.`job_definition_id`;

DELETE `content`
FROM `t_seatunnel_web_job_definition_content` AS `content`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `content`.`job_definition_id`;

DELETE `definition`
FROM `t_seatunnel_web_job_definition` AS `definition`
JOIN `tmp_seatunnel_local_file_jobs` AS `job`
    ON `job`.`id` = `definition`.`id`;

DELETE `binding`
FROM `t_seatunnel_web_metadata_binding` AS `binding`
JOIN `tmp_seatunnel_local_file_datasources` AS `datasource`
    ON `datasource`.`id` = `binding`.`datasource_id`;

DELETE `pool`
FROM `t_seatunnel_web_cdc_server_id_pool` AS `pool`
JOIN `tmp_seatunnel_local_file_datasources` AS `datasource`
    ON `datasource`.`id` = `pool`.`datasource_id`;

DELETE `datasource`
FROM `t_seatunnel_web_datasource` AS `datasource`
JOIN `tmp_seatunnel_local_file_datasources` AS `legacy`
    ON `legacy`.`id` = `datasource`.`id`;

DELETE FROM `t_seatunnel_web_connector_param_meta`
WHERE UPPER(`type`) = 'LOCAL_FILE'
  AND UPPER(`connector_name`) = 'LOCALFILE';

ALTER TABLE `t_seatunnel_web_job_definition`
    MODIFY COLUMN `source_datasource_id` bigint DEFAULT NULL COMMENT '源端数据源ID；Web 上传任务为空';

CREATE TABLE `t_seatunnel_web_file_upload_session`
(
    `id`                 varchar(64)  NOT NULL COMMENT '不透明上传会话ID',
    `job_definition_id`  bigint       NOT NULL COMMENT '批任务定义ID',
    `owner_id`           int          NOT NULL COMMENT '创建用户ID',
    `object_prefix`     varchar(512) NOT NULL COMMENT 'MinIO 对象前缀',
    `status`             varchar(24)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ATTACHED/DELETED',
    `expires_at`         datetime     NOT NULL COMMENT '草稿过期时间',
    `create_time`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_upload_session_job` (`job_definition_id`),
    KEY `idx_file_upload_session_expire` (`status`, `expires_at`),
    KEY `idx_file_upload_session_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Web 文件上传会话';

CREATE TABLE `t_seatunnel_web_file_upload_asset`
(
    `id`             bigint        NOT NULL COMMENT '主键ID',
    `session_id`     varchar(64)   NOT NULL COMMENT '上传会话ID',
    `relative_path`  varchar(1024) NOT NULL COMMENT '浏览器相对路径',
    `object_key`     varchar(1024) NOT NULL COMMENT 'MinIO 对象Key',
    `original_name`  varchar(512)  NOT NULL COMMENT '原始文件名',
    `size`           bigint        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `etag`           varchar(128)  DEFAULT NULL COMMENT '对象ETag',
    `content_type`   varchar(255)  DEFAULT NULL COMMENT '文件 MIME 类型',
    `status`         varchar(24)   NOT NULL DEFAULT 'READY' COMMENT 'READY/DELETED',
    `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_upload_asset_path` (`session_id`, `relative_path`(700)),
    KEY `idx_file_upload_asset_session` (`session_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Web 文件上传资产';
