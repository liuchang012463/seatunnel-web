-- Object-storage sample collection is triggered once per completed metadata scan.
--
-- The marker is the scan run id rather than its success time: the binding stores
-- timestamps at second precision while OpenMetadata reports milliseconds, and the
-- recomputed success time was observed to drift by a second between refreshes. A run
-- id is stable, so it identifies "the scan we last sampled for" exactly.

ALTER TABLE `t_seatunnel_web_metadata_binding`
    ADD COLUMN `storage_sample_scan_run_id` varchar(64) DEFAULT NULL
        COMMENT '已触发对象存储样本采集的元数据扫描 run id'
        AFTER `storage_manifest_config`;
