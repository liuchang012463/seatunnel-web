-- H2 equivalent of the MySQL V1.0.49 snapshot columns and backfill. MERGE ... USING is the
-- H2 2.x form of UPDATE ... JOIN: one pass over the definition table per instance table.
-- The definition tables store table summaries as longtext since V1.0.10, so the instance
-- snapshot columns are CLOB too and a long multi-table list cannot fail the backfill.
ALTER TABLE t_seatunnel_web_job_instance
    ADD COLUMN job_name VARCHAR(255) NULL;

ALTER TABLE t_seatunnel_web_job_instance
    ALTER COLUMN source_table SET DATA TYPE CLOB;

ALTER TABLE t_seatunnel_web_job_instance
    ALTER COLUMN sink_table SET DATA TYPE CLOB;

ALTER TABLE t_seatunnel_web_streaming_job_instance
    ADD COLUMN job_name VARCHAR(255) NULL;

ALTER TABLE t_seatunnel_web_streaming_job_instance
    ADD COLUMN source_datasource_id BIGINT NULL;

ALTER TABLE t_seatunnel_web_streaming_job_instance
    ADD COLUMN sink_datasource_id BIGINT NULL;

ALTER TABLE t_seatunnel_web_streaming_job_instance
    ADD COLUMN source_table CLOB NULL;

ALTER TABLE t_seatunnel_web_streaming_job_instance
    ADD COLUMN sink_table CLOB NULL;

MERGE INTO t_seatunnel_web_job_instance i
USING t_seatunnel_web_job_definition d
ON i.job_definition_id = d.id
WHEN MATCHED AND i.job_name IS NULL THEN
    UPDATE SET i.job_name = d.job_name;

MERGE INTO t_seatunnel_web_streaming_job_instance i
USING t_seatunnel_web_streaming_job_definition d
ON i.job_definition_id = d.id
WHEN MATCHED AND i.job_name IS NULL THEN
    UPDATE SET i.job_name            = d.job_name,
               i.source_datasource_id = d.source_datasource_id,
               i.sink_datasource_id   = d.sink_datasource_id,
               i.source_table         = d.source_table,
               i.sink_table           = d.sink_table;
