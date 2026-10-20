-- H2 equivalent of the MySQL V1.0.44 initial metadata run stage columns.
ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN initial_run_stage VARCHAR(16) NOT NULL DEFAULT 'DONE';

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN initial_run_baseline_id VARCHAR(64) NULL;

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN initial_run_started_at TIMESTAMP NULL;
