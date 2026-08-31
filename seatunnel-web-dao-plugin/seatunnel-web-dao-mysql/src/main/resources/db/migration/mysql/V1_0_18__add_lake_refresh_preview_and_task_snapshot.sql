CREATE TABLE `t_seatunnel_web_lake_metadata_refresh_execution`
(
    `id`            bigint       NOT NULL COMMENT '执行编号',
    `status`        varchar(32)  NOT NULL DEFAULT 'QUEUED',
    `total`         int          NOT NULL DEFAULT 0,
    `succeeded`     int          NOT NULL DEFAULT 0,
    `failed`        int          NOT NULL DEFAULT 0,
    `error_message` varchar(2000) DEFAULT NULL,
    `started_at`    datetime      DEFAULT NULL,
    `finished_at`   datetime      DEFAULT NULL,
    `operator_id`   int           DEFAULT NULL,
    `create_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_lake_refresh_status` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ODS 元数据刷新执行';

CREATE TABLE `t_seatunnel_web_lake_logical_preview_audit`
(
    `id`            bigint        NOT NULL COMMENT '主键',
    `mapping_id`    bigint        NOT NULL,
    `operator_id`   int           DEFAULT NULL,
    `request_json`  text,
    `query_sql`     text,
    `status`        varchar(32)   NOT NULL DEFAULT 'SUCCESS',
    `latency_ms`    bigint        DEFAULT NULL,
    `error_message` varchar(2000) DEFAULT NULL,
    `create_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_lake_preview_mapping` (`mapping_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='逻辑入湖只读预览审计';

ALTER TABLE `t_seatunnel_web_job_definition_content`
    ADD COLUMN `lake_ingestion_plan_json` text COMMENT '引接阶段规则版本快照';
ALTER TABLE `t_seatunnel_web_streaming_job_definition_content`
    ADD COLUMN `lake_ingestion_plan_json` text COMMENT '引接阶段规则版本快照';
