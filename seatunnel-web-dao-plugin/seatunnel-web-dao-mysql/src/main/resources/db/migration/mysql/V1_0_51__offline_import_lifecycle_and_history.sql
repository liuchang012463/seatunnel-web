ALTER TABLE `t_seatunnel_web_offline_import_task`
    ADD COLUMN `release_state` varchar(16) NOT NULL DEFAULT 'ONLINE' COMMENT '任务上线状态：ONLINE/OFFLINE';

CREATE TABLE `t_seatunnel_web_offline_import_task_run`
(
    `id`                  bigint       NOT NULL COMMENT '运行记录ID',
    `task_id`             bigint       NOT NULL COMMENT '离线导入任务ID',
    `task_name`           varchar(128) NOT NULL COMMENT '运行时任务名称快照',
    `status`              varchar(24)  NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
    `total_statements`    int          NOT NULL DEFAULT 0 COMMENT 'SQL导入：语句总数',
    `executed_statements` int          NOT NULL DEFAULT 0 COMMENT 'SQL导入：已执行语句数',
    `failed_statements`   int          NOT NULL DEFAULT 0 COMMENT 'SQL导入：失败语句数',
    `error_message`       varchar(2000)         DEFAULT NULL COMMENT '失败原因摘要',
    `execution_log`       mediumtext COMMENT '本次运行日志',
    `start_time`          datetime     NOT NULL COMMENT '本次运行开始时间',
    `end_time`            datetime              DEFAULT NULL COMMENT '本次运行结束时间',
    `create_user_id`      int          NOT NULL COMMENT '任务创建用户ID',
    `create_time`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_offline_import_run_task_time` (`task_id`, `start_time`),
    KEY `idx_offline_import_run_owner_task` (`create_user_id`, `task_id`),
    CONSTRAINT `fk_offline_import_run_task`
        FOREIGN KEY (`task_id`) REFERENCES `t_seatunnel_web_offline_import_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='离线DMP/SQL任务运行历史';

INSERT INTO `t_seatunnel_web_offline_import_task_run`
    (`id`, `task_id`, `task_name`, `status`, `total_statements`, `executed_statements`,
     `failed_statements`, `error_message`, `execution_log`, `start_time`, `end_time`,
     `create_user_id`, `create_time`, `update_time`)
SELECT `id`, `id`, `name`, `status`, `total_statements`, `executed_statements`,
       `failed_statements`, `error_message`, `execution_log`, `start_time`, `end_time`,
       `create_user_id`, `create_time`, `update_time`
FROM `t_seatunnel_web_offline_import_task`
WHERE `start_time` IS NOT NULL;
