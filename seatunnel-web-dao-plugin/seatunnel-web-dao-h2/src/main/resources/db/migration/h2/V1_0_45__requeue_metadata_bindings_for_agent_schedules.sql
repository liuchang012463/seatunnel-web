-- H2 equivalent of the MySQL V1.0.45 binding requeue.
UPDATE t_seatunnel_web_metadata_binding
SET config_version = config_version + 1,
    sync_status = 'PENDING',
    update_time = CURRENT_TIMESTAMP
WHERE desired_state = 'ACTIVE'
  AND sync_status <> 'DELETING';
