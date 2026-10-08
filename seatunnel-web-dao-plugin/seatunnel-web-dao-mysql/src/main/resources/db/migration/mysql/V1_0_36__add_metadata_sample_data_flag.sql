-- OpenMetadata sample-data collection is an operator decision per data source.
--
-- The flag is deliberately off by default: enabling it makes the ingestion
-- pipeline read real payloads (Kafka topic messages, SFTP file rows) and store
-- them inside OpenMetadata, where they are visible to any principal holding
-- VIEW_SAMPLE_DATA. Only connectors whose adapter reports supportsSampleData()
-- act on the flag; for every other type it stays inert.

ALTER TABLE `t_seatunnel_web_metadata_binding`
    ADD COLUMN `sample_data_enabled` tinyint(1) NOT NULL DEFAULT 0
        COMMENT 'OpenMetadata 样本数据采集开关（Kafka 主题消息 / SFTP 文件行）'
        AFTER `om_profiler_pipeline_fqn`;
