-- V1_0_42 created the snapshot columns with varchar(512), but the definition table stores
-- table names in varchar(1024) and whole-database sync joins many names into one value. Widen
-- the snapshot columns to match so instance creation cannot fail on a long table list.
ALTER TABLE `t_seatunnel_web_job_instance`
    MODIFY COLUMN `source_table` varchar(1024) DEFAULT NULL COMMENT '提交时的源表',
    MODIFY COLUMN `sink_table` varchar(1024) DEFAULT NULL COMMENT '提交时的目标表';
