ALTER TABLE `t_seatunnel_web_openmetadata_config` DROP COLUMN `kingbase_tunnel_host`;
ALTER TABLE `t_seatunnel_web_openmetadata_config` DROP COLUMN `kingbase_tunnel_port`;

UPDATE `t_seatunnel_web_openmetadata_config`
SET `enabled` = TRUE
WHERE `base_url` IS NOT NULL AND `base_url` <> '' AND `token` IS NOT NULL AND `token` <> '';
