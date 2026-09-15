-- Keep H2 test/local profiles aligned with the MySQL task type migration.
ALTER TABLE t_seatunnel_web_job_definition
    ADD COLUMN task_type varchar(32) DEFAULT 'BATCH';

ALTER TABLE t_seatunnel_web_streaming_job_definition
    ADD COLUMN task_type varchar(32) DEFAULT 'STREAM';

UPDATE t_seatunnel_web_job_definition
SET task_type = 'FILE_TRANSFER'
WHERE UPPER(COALESCE(mode, '')) = 'FILE_SYNC';

UPDATE t_seatunnel_web_job_definition
SET task_type = 'BATCH'
WHERE task_type IS NULL
  AND UPPER(COALESCE(mode, '')) <> 'FILE_SYNC'
  AND NOT (UPPER(COALESCE(mode, '')) = 'GUIDE_SINGLE'
           AND UPPER(COALESCE(source_type, '')) = 'WEB_UPLOAD');

UPDATE t_seatunnel_web_streaming_job_definition
SET task_type = 'STREAM'
WHERE task_type IS NULL;

UPDATE t_seatunnel_web_job_definition
SET task_type = NULL
WHERE UPPER(COALESCE(mode, '')) = 'GUIDE_SINGLE'
  AND UPPER(COALESCE(source_type, '')) = 'WEB_UPLOAD';
