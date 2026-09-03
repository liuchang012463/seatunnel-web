-- Stores the last read-only Doris partition observation; desired policy state
-- remains in retention_count and is intentionally not duplicated here.
ALTER TABLE `t_seatunnel_web_lake_table_lifecycle_binding`
    ADD COLUMN `actual_partition_summary_json` longtext DEFAULT NULL
        COMMENT '脱敏 Doris 实际分区汇总' AFTER `actual_retention_count`,
    ADD COLUMN `last_observed_at` datetime DEFAULT NULL
        COMMENT '最近一次实际分区观测时间' AFTER `actual_partition_summary_json`;
