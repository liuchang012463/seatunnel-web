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

-- Script jobs may contain multiple source or sink plugins while the legacy
-- definition columns retain only the first ID. Keep every connection found in
-- a current script definition dual-purpose until its side can be maintained
-- by the job editor without losing historical references.
UPDATE t_seatunnel_web_datasource datasource
SET source_enabled = TRUE,
    sink_enabled = TRUE
WHERE EXISTS (
    SELECT 1
    FROM t_seatunnel_web_job_definition_content content
    JOIN t_seatunnel_web_job_definition job
      ON job.id = content.job_definition_id
     AND job.job_version = content.version
     AND job.mode = 'SCRIPT'
     AND content.mode = 'SCRIPT'
    WHERE REGEXP_LIKE(LOWER(content.definition_content),
          CONCAT('.*(datasourceid|datasource_id).{0,32}[^0-9]',
                 datasource.id, '([^0-9].*|$)'))
)
OR EXISTS (
    SELECT 1
    FROM t_seatunnel_web_streaming_job_definition_content content
    JOIN t_seatunnel_web_streaming_job_definition job
      ON job.id = content.job_definition_id
     AND job.job_version = content.version
     AND job.mode = 'SCRIPT'
     AND content.mode = 'SCRIPT'
    WHERE REGEXP_LIKE(LOWER(content.definition_content),
          CONCAT('.*(datasourceid|datasource_id).{0,32}[^0-9]',
                 datasource.id, '([^0-9].*|$)'))
);
