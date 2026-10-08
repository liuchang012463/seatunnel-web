ALTER TABLE t_seatunnel_web_metadata_binding
    ALTER COLUMN status_refresh_error CLOB;

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN profile_run_reservation_token VARCHAR(36) NULL;

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN profile_profiler_run_id_baseline VARCHAR(255) NULL;

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN profile_sample_run_id_baseline VARCHAR(255) NULL;

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN profile_run_baseline_captured BOOLEAN NULL;

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN profile_run_baseline_captured_at TIMESTAMP NULL;
