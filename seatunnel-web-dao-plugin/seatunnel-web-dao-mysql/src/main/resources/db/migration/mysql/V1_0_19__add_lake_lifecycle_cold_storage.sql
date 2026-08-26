ALTER TABLE `t_seatunnel_web_lake_lifecycle_rule`
    ADD COLUMN `cold_storage_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用冷热分层' AFTER `expired_action`,
    ADD COLUMN `cold_after_value` int DEFAULT NULL COMMENT '转冷时间数值' AFTER `cold_storage_enabled`,
    ADD COLUMN `cold_after_unit` varchar(16) DEFAULT NULL COMMENT '转冷时间单位' AFTER `cold_after_value`;
