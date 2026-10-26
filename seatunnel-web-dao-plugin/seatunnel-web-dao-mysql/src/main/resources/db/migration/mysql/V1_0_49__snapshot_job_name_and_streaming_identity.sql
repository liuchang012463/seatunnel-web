-- Lineage and the run detail read the job identity from the instance row. Persist the job name
-- captured at submission so renaming the definition while a run is in flight cannot split one
-- run across two OpenLineage jobs, and add the same source/target snapshot to the streaming
-- instance table (the batch table got it in V1.0.42/43). Existing rows are backfilled from the
-- definition, which is the best available identity for them.
--
-- The definition tables store table summaries as longtext since V1.0.10 (multi-table jobs keep a
-- JSON table list there), so the instance snapshot columns are longtext too: a varchar snapshot
-- would reject a long GUIDE_MULTI table list during the backfill or the insert.
ALTER TABLE `t_seatunnel_web_job_instance`
    ADD COLUMN `job_name` varchar(255) DEFAULT NULL COMMENT '提交时的任务名称' AFTER `log_path`,
    MODIFY COLUMN `source_table` longtext COMMENT '提交时的源表',
    MODIFY COLUMN `sink_table` longtext COMMENT '提交时的目标表';

ALTER TABLE `t_seatunnel_web_streaming_job_instance`
    ADD COLUMN `job_name` varchar(255) DEFAULT NULL COMMENT '提交时的任务名称' AFTER `log_path`,
    ADD COLUMN `source_datasource_id` bigint DEFAULT NULL COMMENT '提交时的源数据源ID' AFTER `job_name`,
    ADD COLUMN `sink_datasource_id` bigint DEFAULT NULL COMMENT '提交时的目标数据源ID' AFTER `source_datasource_id`,
    ADD COLUMN `source_table` longtext COMMENT '提交时的源表' AFTER `sink_datasource_id`,
    ADD COLUMN `sink_table` longtext COMMENT '提交时的目标表' AFTER `source_table`;

UPDATE `t_seatunnel_web_job_instance` i
    JOIN `t_seatunnel_web_job_definition` d ON i.job_definition_id = d.id
SET i.job_name = d.job_name
WHERE i.job_name IS NULL;

UPDATE `t_seatunnel_web_streaming_job_instance` i
    JOIN `t_seatunnel_web_streaming_job_definition` d ON i.job_definition_id = d.id
SET i.job_name            = d.job_name,
    i.source_datasource_id = d.source_datasource_id,
    i.sink_datasource_id   = d.sink_datasource_id,
    i.source_table         = d.source_table,
    i.sink_table           = d.sink_table
WHERE i.job_name IS NULL;
