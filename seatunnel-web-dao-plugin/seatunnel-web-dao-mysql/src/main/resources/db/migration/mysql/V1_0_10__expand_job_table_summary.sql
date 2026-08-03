-- Expand the denormalized table summaries so multi-table jobs are not limited
-- by the previous VARCHAR length. Individual table metrics keep their indexed
-- VARCHAR columns because those columns contain one table name per row.

ALTER TABLE `t_seatunnel_web_job_definition`
    MODIFY COLUMN `source_table` longtext COMMENT '源表摘要',
    MODIFY COLUMN `sink_table` longtext COMMENT '目标表摘要';

ALTER TABLE `t_seatunnel_web_streaming_job_definition`
    MODIFY COLUMN `source_table` longtext COMMENT '源表',
    MODIFY COLUMN `sink_table` longtext COMMENT '目标表';
