CREATE TABLE IF NOT EXISTS `t_seatunnel_web_incremental_batch_control` (
    `id` bigint NOT NULL,
    `job_definition_id` bigint NOT NULL,
    `committed_watermark` datetime(6) NOT NULL,
    `last_success_batch_id` varchar(96) DEFAULT NULL,
    `task_status` varchar(24) NOT NULL DEFAULT 'READY',
    `version_no` int NOT NULL DEFAULT 0,
    `create_time` datetime(6) NOT NULL,
    `update_time` datetime(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_incremental_control_definition` (`job_definition_id`),
    KEY `idx_incremental_control_status` (`task_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单表增量微批水位控制';

CREATE TABLE IF NOT EXISTS `t_seatunnel_web_incremental_batch_record` (
    `batch_id` varchar(96) NOT NULL,
    `job_definition_id` bigint NOT NULL,
    `job_instance_id` bigint DEFAULT NULL,
    `window_start` datetime(6) NOT NULL,
    `window_end` datetime(6) NOT NULL,
    `query_start` datetime(6) NOT NULL,
    `batch_status` varchar(24) NOT NULL,
    `retry_count` int NOT NULL DEFAULT 0,
    `started_at` datetime(6) NOT NULL,
    `finished_at` datetime(6) DEFAULT NULL,
    `error_message` varchar(4000) DEFAULT NULL,
    `create_time` datetime(6) NOT NULL,
    `update_time` datetime(6) NOT NULL,
    PRIMARY KEY (`batch_id`),
    UNIQUE KEY `uk_incremental_record_instance` (`job_instance_id`),
    KEY `idx_incremental_record_definition_status` (`job_definition_id`, `batch_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单表增量微批窗口执行记录';
