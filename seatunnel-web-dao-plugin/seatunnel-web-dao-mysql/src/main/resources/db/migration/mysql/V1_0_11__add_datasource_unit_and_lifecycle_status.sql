ALTER TABLE `t_seatunnel_web_datasource`
    ADD COLUMN `data_source_unit` varchar(128) DEFAULT NULL COMMENT '数据源所属单位' AFTER `name`,
    ADD COLUMN `status` varchar(24) NOT NULL DEFAULT 'ENABLED' COMMENT '生命周期状态：ENABLED/DISABLED/REVOKED' AFTER `conn_status`,
    ADD KEY `idx_datasource_unit` (`data_source_unit`),
    ADD KEY `idx_datasource_status` (`status`);
