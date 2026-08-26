-- Lake metadata audit fields.  Connection secrets remain in the datasource table.
ALTER TABLE `t_seatunnel_web_lake_physical_resource`
    ADD COLUMN `version` int NOT NULL DEFAULT 1 COMMENT '资源版本' AFTER `id`,
    ADD COLUMN `lock_version` int NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER `version`,
    ADD COLUMN `create_user_id` int DEFAULT NULL COMMENT '创建人' AFTER `update_time`,
    ADD COLUMN `update_user_id` int DEFAULT NULL COMMENT '更新人' AFTER `create_user_id`,
    ADD COLUMN `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除' AFTER `update_user_id`;

ALTER TABLE `t_seatunnel_web_lake_logical_mapping`
    ADD COLUMN `version` int NOT NULL DEFAULT 1 COMMENT '映射版本' AFTER `id`,
    ADD COLUMN `lock_version` int NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER `version`,
    ADD COLUMN `create_user_id` int DEFAULT NULL COMMENT '创建人' AFTER `update_time`,
    ADD COLUMN `update_user_id` int DEFAULT NULL COMMENT '更新人' AFTER `create_user_id`,
    ADD COLUMN `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除' AFTER `update_user_id`;

ALTER TABLE `t_seatunnel_web_lake_lifecycle_rule`
    ADD COLUMN `lock_version` int NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER `version`,
    ADD COLUMN `create_user_id` int DEFAULT NULL COMMENT '创建人' AFTER `update_time`,
    ADD COLUMN `update_user_id` int DEFAULT NULL COMMENT '更新人' AFTER `create_user_id`,
    ADD COLUMN `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除' AFTER `update_user_id`;

CREATE INDEX `idx_lake_physical_deleted` ON `t_seatunnel_web_lake_physical_resource` (`deleted`);
CREATE INDEX `idx_lake_logical_deleted` ON `t_seatunnel_web_lake_logical_mapping` (`deleted`);
CREATE INDEX `idx_lake_lifecycle_deleted` ON `t_seatunnel_web_lake_lifecycle_rule` (`deleted`);

ALTER TABLE `t_seatunnel_web_lake_logical_mapping`
    DROP INDEX `uk_lake_logical_name`,
    ADD UNIQUE KEY `uk_lake_logical_name_version` (`logical_database`, `logical_name`, `version`);
