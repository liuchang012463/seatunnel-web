UPDATE `t_seatunnel_web_openmetadata_config`
SET `expected_server_version` = '2.0.4',
    `expected_ingestion_patch` = '2.0.4.0'
WHERE `expected_server_version` = '1.12.10'
  AND `expected_ingestion_patch` = '1.12.10.0';

ALTER TABLE `t_seatunnel_web_openmetadata_config`
    ALTER COLUMN `expected_server_version` SET DEFAULT '2.0.4';

ALTER TABLE `t_seatunnel_web_openmetadata_config`
    ALTER COLUMN `expected_ingestion_patch` SET DEFAULT '2.0.4.0';
