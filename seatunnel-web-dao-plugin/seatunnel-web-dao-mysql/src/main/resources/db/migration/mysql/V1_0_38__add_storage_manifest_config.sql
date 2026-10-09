-- Object-storage data sources need a manifest to become structured containers with a
-- data model. OpenMetadata accepts it as the pipeline's defaultManifest, which is the
-- same JSON a bucket-level openmetadata.json would hold.
--
-- It lives on the binding rather than in the data source connection parameters: the
-- connection parameters build the SeaTunnel job, and this value is OpenMetadata-only.

ALTER TABLE `t_seatunnel_web_metadata_binding`
    ADD COLUMN `storage_manifest_config` text NULL
        COMMENT 'OpenMetadata 对象存储清单（defaultManifest JSON，仅结构化容器使用）'
        AFTER `sample_data_enabled`;
