ALTER TABLE t_seatunnel_web_datasource
    ADD COLUMN source_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE t_seatunnel_web_datasource
    ADD COLUMN sink_enabled BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE t_seatunnel_web_datasource datasource
SET source_enabled = CASE
        WHEN datasource.system_managed = TRUE THEN TRUE
        WHEN EXISTS (SELECT 1 FROM t_seatunnel_web_job_definition job
                     WHERE job.source_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_streaming_job_definition job
                     WHERE job.source_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_lake_ods_database_binding binding
                     WHERE binding.source_data_source_id = datasource.id AND binding.deleted = FALSE)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_lake_external_catalog_binding binding
                     WHERE binding.source_data_source_id = datasource.id AND binding.deleted = FALSE)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_lake_source_object_ref ref
                     WHERE ref.source_data_source_id = datasource.id AND ref.deleted = FALSE)
        THEN TRUE
        WHEN EXISTS (SELECT 1 FROM t_seatunnel_web_job_definition job
                     WHERE job.sink_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_streaming_job_definition job
                     WHERE job.sink_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_offline_import_task task
                     WHERE task.target_datasource_id = datasource.id)
        THEN FALSE
        ELSE TRUE
    END,
    sink_enabled = CASE
        WHEN datasource.system_managed = TRUE THEN TRUE
        WHEN EXISTS (SELECT 1 FROM t_seatunnel_web_job_definition job
                     WHERE job.sink_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_streaming_job_definition job
                     WHERE job.sink_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_offline_import_task task
                     WHERE task.target_datasource_id = datasource.id)
        THEN TRUE
        WHEN EXISTS (SELECT 1 FROM t_seatunnel_web_job_definition job
                     WHERE job.source_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_streaming_job_definition job
                     WHERE job.source_datasource_id = datasource.id)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_lake_ods_database_binding binding
                     WHERE binding.source_data_source_id = datasource.id AND binding.deleted = FALSE)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_lake_external_catalog_binding binding
                     WHERE binding.source_data_source_id = datasource.id AND binding.deleted = FALSE)
          OR EXISTS (SELECT 1 FROM t_seatunnel_web_lake_source_object_ref ref
                     WHERE ref.source_data_source_id = datasource.id AND ref.deleted = FALSE)
        THEN FALSE
        ELSE TRUE
    END;
