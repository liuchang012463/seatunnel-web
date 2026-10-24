-- H2 equivalent of the MySQL V1.0.46 metadata sample agent status columns.
ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN sample_status VARCHAR(16) NOT NULL DEFAULT 'NEVER';

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN sample_last_run_time TIMESTAMP NULL;

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN sample_last_success_time TIMESTAMP NULL;

ALTER TABLE t_seatunnel_web_metadata_binding
    ADD COLUMN sample_last_error VARCHAR(512) NULL;

UPDATE t_seatunnel_web_metadata_binding
SET config_version = config_version + 1,
    sync_status = 'PENDING',
    update_time = CURRENT_TIMESTAMP
WHERE desired_state = 'ACTIVE'
  AND sync_status <> 'DELETING';
