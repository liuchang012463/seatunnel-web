ALTER TABLE `t_seatunnel_web_metadata_binding`
    MODIFY COLUMN `status_refresh_error` TEXT NULL;

ALTER TABLE `t_seatunnel_web_metadata_binding`
    ADD COLUMN `profile_run_reservation_token` VARCHAR(36) NULL,
    ADD COLUMN `profile_profiler_run_id_baseline` VARCHAR(255) NULL,
    ADD COLUMN `profile_sample_run_id_baseline` VARCHAR(255) NULL,
    ADD COLUMN `profile_run_baseline_captured` BOOLEAN NULL,
    ADD COLUMN `profile_run_baseline_captured_at` DATETIME NULL;
