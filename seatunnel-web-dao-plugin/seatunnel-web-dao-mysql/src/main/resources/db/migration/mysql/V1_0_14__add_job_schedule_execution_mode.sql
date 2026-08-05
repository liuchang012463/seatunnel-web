ALTER TABLE `t_seatunnel_web_job_schedule`
    MODIFY COLUMN `cron_expression` varchar(64) NULL COMMENT 'Cron表达式；手动执行任务为空';

ALTER TABLE `t_seatunnel_web_job_schedule`
    ADD COLUMN `execution_mode` varchar(16) NULL
        COMMENT '任务执行方式：MANUAL / AUTO' AFTER `job_definition_id`;

UPDATE `t_seatunnel_web_job_schedule`
SET `execution_mode` = CASE
    WHEN `cron_expression` IS NULL OR TRIM(`cron_expression`) = '' THEN 'MANUAL'
    ELSE 'AUTO'
END;

ALTER TABLE `t_seatunnel_web_job_schedule`
    MODIFY COLUMN `execution_mode` varchar(16) NOT NULL DEFAULT 'MANUAL'
        COMMENT '任务执行方式：MANUAL / AUTO';
