-- The Web reconciler upgrades the OM pipelines that belong to this environment.
ALTER TABLE `t_seatunnel_web_metadata_binding`
    ADD COLUMN `exploration_pipeline_version` INT NOT NULL DEFAULT 0,
    ADD COLUMN `exploration_pipeline_retry_at` DATETIME(3) NULL;
