-- Lineage is written asynchronously after submission. Persist the source/target identity used by
-- this run so editing the definition in the meantime cannot redirect the lineage edge.
ALTER TABLE t_seatunnel_web_job_instance
    ADD COLUMN source_datasource_id BIGINT NULL;

ALTER TABLE t_seatunnel_web_job_instance
    ADD COLUMN sink_datasource_id BIGINT NULL;

ALTER TABLE t_seatunnel_web_job_instance
    ADD COLUMN source_table VARCHAR(512) NULL;

ALTER TABLE t_seatunnel_web_job_instance
    ADD COLUMN sink_table VARCHAR(512) NULL;
