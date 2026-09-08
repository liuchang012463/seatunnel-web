-- Drop obsolete Kingbase SSH tunnel columns from OpenMetadata server config.
ALTER TABLE `t_seatunnel_web_openmetadata_config`
    DROP COLUMN `kingbase_tunnel_host`,
    DROP COLUMN `kingbase_tunnel_port`;

-- Configured OM rows are always-on; normalize legacy disabled rows.
UPDATE `t_seatunnel_web_openmetadata_config`
SET `enabled` = 1
WHERE `base_url` IS NOT NULL AND `base_url` <> '' AND `token` IS NOT NULL AND `token` <> '';
