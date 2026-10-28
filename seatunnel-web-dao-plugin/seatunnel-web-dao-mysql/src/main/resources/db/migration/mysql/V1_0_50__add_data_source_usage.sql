ALTER TABLE `t_seatunnel_web_datasource`
    ADD COLUMN `source_enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否可用作源端连接' AFTER `db_type`,
    ADD COLUMN `sink_enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否可用作目标连接' AFTER `source_enabled`;

UPDATE `t_seatunnel_web_datasource` datasource
SET datasource.source_enabled = CASE
        WHEN datasource.system_managed = 1 THEN 1
        WHEN EXISTS (SELECT 1 FROM `t_seatunnel_web_job_definition` job
                     WHERE job.source_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_streaming_job_definition` job
                     WHERE job.source_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_lake_ods_database_binding` binding
                     WHERE binding.source_data_source_id = datasource.id AND binding.deleted = 0)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_lake_external_catalog_binding` binding
                     WHERE binding.source_data_source_id = datasource.id AND binding.deleted = 0)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_lake_source_object_ref` ref
                     WHERE ref.source_data_source_id = datasource.id AND ref.deleted = 0)
        THEN 1
        WHEN EXISTS (SELECT 1 FROM `t_seatunnel_web_job_definition` job
                     WHERE job.sink_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_streaming_job_definition` job
                     WHERE job.sink_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_offline_import_task` task
                     WHERE task.target_datasource_id = datasource.id)
        THEN 0
        ELSE 1
    END,
    datasource.sink_enabled = CASE
        WHEN datasource.system_managed = 1 THEN 1
        WHEN EXISTS (SELECT 1 FROM `t_seatunnel_web_job_definition` job
                     WHERE job.sink_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_streaming_job_definition` job
                     WHERE job.sink_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_offline_import_task` task
                     WHERE task.target_datasource_id = datasource.id)
        THEN 1
        WHEN EXISTS (SELECT 1 FROM `t_seatunnel_web_job_definition` job
                     WHERE job.source_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_streaming_job_definition` job
                     WHERE job.source_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_lake_ods_database_binding` binding
                     WHERE binding.source_data_source_id = datasource.id AND binding.deleted = 0)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_lake_external_catalog_binding` binding
                     WHERE binding.source_data_source_id = datasource.id AND binding.deleted = 0)
          OR EXISTS (SELECT 1 FROM `t_seatunnel_web_lake_source_object_ref` ref
                     WHERE ref.source_data_source_id = datasource.id AND ref.deleted = 0)
        THEN 0
        ELSE 1
    END;
