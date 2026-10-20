-- Sample collection becomes part of the default data-source setup.
--
-- The flag is turned on for every data source, so the sample agent (auto-classification
-- pipeline) or the metadata pipeline's own sampler collects payloads without an extra
-- operator step. Only connectors whose adapter reports a sample capability act on the
-- flag; for every other type it stays inert.
--
-- The sample agent gets its own status columns, mirroring the metadata scan and the
-- profiler: the UI reports the agent state per data source instead of guessing it
-- from the switch. Connectors that sample inside their metadata pipeline (Elasticsearch,
-- FTP, SFTP) have no such agent and keep the columns empty.
--
-- The backfill re-queues the bindings so the reconciler re-upserts the pipelines with
-- the new sampling decision; the sample pipelines of the object-storage sources are
-- created by that reconciliation.
ALTER TABLE `t_seatunnel_web_metadata_binding`
    ADD COLUMN `sample_status` varchar(16) NOT NULL DEFAULT 'NEVER'
        COMMENT '样本采集 agent 状态：NEVER/QUEUED/RUNNING/SUCCESS/FAILED/UNKNOWN' AFTER `profile_last_error`,
    ADD COLUMN `sample_last_run_time` datetime DEFAULT NULL
        COMMENT '样本采集 agent 最近一次运行时间' AFTER `sample_status`,
    ADD COLUMN `sample_last_success_time` datetime DEFAULT NULL
        COMMENT '样本采集 agent 最近一次成功时间' AFTER `sample_last_run_time`,
    ADD COLUMN `sample_last_error` varchar(512) DEFAULT NULL
        COMMENT '样本采集 agent 最近一次错误' AFTER `sample_last_success_time`;

ALTER TABLE `t_seatunnel_web_metadata_binding`
    MODIFY COLUMN `sample_data_enabled` tinyint(1) NOT NULL DEFAULT 1
        COMMENT 'OpenMetadata 样本数据采集开关（Kafka 主题消息 / SFTP 文件行），默认开启';

UPDATE `t_seatunnel_web_metadata_binding`
SET `sample_data_enabled` = 1,
    `config_version` = `config_version` + 1,
    `sync_status` = 'PENDING',
    `update_time` = NOW()
WHERE `desired_state` = 'ACTIVE'
  AND `sync_status` <> 'DELETING'
  AND (`sample_data_enabled` = 0 OR `sample_data_enabled` IS NULL);
