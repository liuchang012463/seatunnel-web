-- Raise OpenMetadata client timeout defaults for new rows only.
-- Do not rewrite existing operator-tuned connect/read timeouts.
ALTER TABLE `t_seatunnel_web_openmetadata_config` ALTER COLUMN `connect_timeout_ms` SET DEFAULT 10000;
ALTER TABLE `t_seatunnel_web_openmetadata_config` ALTER COLUMN `read_timeout_ms` SET DEFAULT 60000;
