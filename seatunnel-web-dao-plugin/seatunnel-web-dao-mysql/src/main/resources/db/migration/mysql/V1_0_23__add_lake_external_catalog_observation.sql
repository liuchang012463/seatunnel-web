-- Adds only the missing local read-model fields for external catalog bindings.
-- V1_0_21 is already applied and remains immutable; no H2 migration is used.
ALTER TABLE `t_seatunnel_web_lake_external_catalog_binding`
    ADD COLUMN `driver_checksum` char(64) DEFAULT NULL
        COMMENT '服务端已验证的 JDBC driver SHA-256' AFTER `credential_revision`,
    ADD COLUMN `actual_snapshot_json` longtext DEFAULT NULL
        COMMENT '脱敏 Doris 实际 Catalog 快照' AFTER `validation_status`,
    ADD COLUMN `last_observed_at` datetime DEFAULT NULL
        COMMENT '最近一次实际 Catalog 观测时间' AFTER `actual_snapshot_json`;
