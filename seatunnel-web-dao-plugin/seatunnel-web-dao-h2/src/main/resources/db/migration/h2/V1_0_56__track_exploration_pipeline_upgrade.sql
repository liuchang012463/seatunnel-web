-- The Web reconciler upgrades the OM pipelines that belong to this environment.
ALTER TABLE t_seatunnel_web_metadata_binding ADD COLUMN exploration_pipeline_version INT DEFAULT 0 NOT NULL;
ALTER TABLE t_seatunnel_web_metadata_binding ADD COLUMN exploration_pipeline_retry_at TIMESTAMP(3);
