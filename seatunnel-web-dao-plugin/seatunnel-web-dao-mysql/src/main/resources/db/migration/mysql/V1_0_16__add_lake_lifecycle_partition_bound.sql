ALTER TABLE `t_seatunnel_web_lake_lifecycle_execution_detail`
    ADD COLUMN `partition_upper_bound` varchar(64) DEFAULT NULL COMMENT '分区范围上界';
