-- Lineage is written asynchronously after submission. Persist the source/target identity used by
-- this run so editing the definition in the meantime cannot redirect the lineage edge.
ALTER TABLE `t_seatunnel_web_job_instance`
    ADD COLUMN `source_datasource_id` bigint DEFAULT NULL COMMENT '提交时的源数据源ID' AFTER `log_path`,
    ADD COLUMN `sink_datasource_id` bigint DEFAULT NULL COMMENT '提交时的目标数据源ID' AFTER `source_datasource_id`,
    ADD COLUMN `source_table` varchar(512) DEFAULT NULL COMMENT '提交时的源表' AFTER `sink_datasource_id`,
    ADD COLUMN `sink_table` varchar(512) DEFAULT NULL COMMENT '提交时的目标表' AFTER `source_table`;
