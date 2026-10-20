-- New data sources run the three OpenMetadata agents in order once:
-- metadata scan -> sample collection -> profiler. Existing bindings stay DONE
-- so upgrading never replays an initial exploration.
ALTER TABLE `t_seatunnel_web_metadata_binding`
    ADD COLUMN `initial_run_stage` varchar(16) NOT NULL DEFAULT 'DONE'
        COMMENT '初始化探查阶段：METADATA/SAMPLE/PROFILE/DONE' AFTER `storage_sample_scan_run_id`,
    ADD COLUMN `initial_run_baseline_id` varchar(64) DEFAULT NULL
        COMMENT '当前初始化阶段触发前的 OpenMetadata run id 基线' AFTER `initial_run_stage`,
    ADD COLUMN `initial_run_started_at` datetime DEFAULT NULL
        COMMENT '当前初始化阶段开始时间' AFTER `initial_run_baseline_id`;
