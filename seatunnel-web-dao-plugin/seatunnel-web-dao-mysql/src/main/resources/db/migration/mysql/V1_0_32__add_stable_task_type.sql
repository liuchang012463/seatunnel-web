-- Add a business-level task type without changing job_type.
-- job_type remains the SeaTunnel Engine runtime mode: BATCH / STREAMING.
--
-- The column is nullable so a legacy GUIDE_SINGLE row whose WEB_UPLOAD
-- source is only discoverable from definition_content can be resolved by the
-- service layer instead of being silently classified as a normal batch job.

ALTER TABLE `t_seatunnel_web_job_definition`
    ADD COLUMN `task_type` varchar(32) NULL DEFAULT 'BATCH'
        COMMENT '稳定业务任务类型：BATCH / FILE_INGEST / FILE_TRANSFER'
        AFTER `job_type`,
    ADD KEY `idx_task_type` (`task_type`);

ALTER TABLE `t_seatunnel_web_streaming_job_definition`
    ADD COLUMN `task_type` varchar(32) NULL DEFAULT 'STREAM'
        COMMENT '稳定业务任务类型：STREAM'
        AFTER `job_type`,
    ADD KEY `idx_streaming_task_type` (`task_type`);

-- FILE_SYNC has an unambiguous historical meaning in the batch definition
-- table: it is the file-transfer task family.
UPDATE `t_seatunnel_web_job_definition`
SET `task_type` = 'FILE_TRANSFER'
WHERE UPPER(COALESCE(`mode`, '')) = 'FILE_SYNC';

-- Keep ordinary batch definitions explicit even when the database default
-- has already populated them.  The upload predicate intentionally remains
-- excluded and is handled by the compatibility fallback below.
UPDATE `t_seatunnel_web_job_definition`
SET `task_type` = 'BATCH'
WHERE `task_type` IS NULL
  AND UPPER(COALESCE(`mode`, '')) <> 'FILE_SYNC'
  AND NOT (UPPER(COALESCE(`mode`, '')) = 'GUIDE_SINGLE'
           AND UPPER(COALESCE(`source_type`, '')) = 'WEB_UPLOAD');

UPDATE `t_seatunnel_web_streaming_job_definition`
SET `task_type` = 'STREAM'
WHERE `task_type` IS NULL;

-- Do not parse definition_content in this migration.  It is LONGTEXT and
-- legacy rows are not guaranteed to contain valid JSON.  The current
-- analyzer persists WEB_UPLOAD in source_type, so those rows are left NULL
-- for a service-level content fallback; this avoids misclassifying them as
-- ordinary BATCH tasks.  Other GUIDE_SINGLE rows retain the BATCH default.
UPDATE `t_seatunnel_web_job_definition`
SET `task_type` = NULL
WHERE UPPER(COALESCE(`mode`, '')) = 'GUIDE_SINGLE'
  AND UPPER(COALESCE(`source_type`, '')) = 'WEB_UPLOAD';
