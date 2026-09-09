-- Raise OpenMetadata client timeout defaults so exploration pipeline upserts
-- are less likely to fail with Network error: timeout under load.
ALTER TABLE `t_seatunnel_web_openmetadata_config` ALTER COLUMN `connect_timeout_ms` SET DEFAULT 10000;
ALTER TABLE `t_seatunnel_web_openmetadata_config` ALTER COLUMN `read_timeout_ms` SET DEFAULT 60000;

UPDATE `t_seatunnel_web_openmetadata_config`
SET `connect_timeout_ms` = 10000,
    `config_version` = `config_version` + 1
WHERE `connect_timeout_ms` = 2000;

UPDATE `t_seatunnel_web_openmetadata_config`
SET `read_timeout_ms` = 60000,
    `config_version` = `config_version` + 1
WHERE `read_timeout_ms` = 10000;
