-- H2 equivalent of MySQL V1.0.22 lifecycle partition observation columns.
ALTER TABLE `t_seatunnel_web_lake_table_lifecycle_binding`
    ADD COLUMN `actual_partition_summary_json` CLOB;
ALTER TABLE `t_seatunnel_web_lake_table_lifecycle_binding`
    ADD COLUMN `last_observed_at` TIMESTAMP;
