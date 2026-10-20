-- Re-upsert existing OpenMetadata pipelines so they receive the new agent
-- schedules (metadata every two hours, sample/profile once a day).
UPDATE `t_seatunnel_web_metadata_binding`
SET `config_version` = `config_version` + 1,
    `sync_status` = 'PENDING',
    `update_time` = NOW()
WHERE `desired_state` = 'ACTIVE'
  AND `sync_status` <> 'DELETING';
