-- Resource audit ownership is intentionally audit-only. Resource queries
-- remain global and are not filtered by either user column.

ALTER TABLE `t_seatunnel_web_user`
    ADD COLUMN `sso_subject` varchar(255) DEFAULT NULL COMMENT '外部 SSO subject' AFTER `phone`,
    ADD UNIQUE KEY `uk_user_sso_subject` (`sso_subject`);

ALTER TABLE `t_seatunnel_web_datasource`
    ADD COLUMN `create_user_id` int DEFAULT NULL COMMENT '创建人用户ID' AFTER `update_time`,
    ADD COLUMN `update_user_id` int DEFAULT NULL COMMENT '最近修改人用户ID' AFTER `create_user_id`,
    ADD KEY `idx_datasource_create_user_id` (`create_user_id`),
    ADD KEY `idx_datasource_update_user_id` (`update_user_id`);

UPDATE `t_seatunnel_web_datasource`
SET `create_user_id` = 1
WHERE `create_user_id` IS NULL;

UPDATE `t_seatunnel_web_datasource`
SET `update_user_id` = 1
WHERE `update_user_id` IS NULL;

ALTER TABLE `t_seatunnel_web_datasource`
    MODIFY COLUMN `update_time` datetime DEFAULT NULL COMMENT '更新时间',
    MODIFY COLUMN `create_user_id` int NOT NULL DEFAULT 1 COMMENT '创建人用户ID',
    MODIFY COLUMN `update_user_id` int NOT NULL DEFAULT 1 COMMENT '最近修改人用户ID';

ALTER TABLE `t_seatunnel_web_client`
    ADD COLUMN `create_user_id` int DEFAULT NULL COMMENT '创建人用户ID' AFTER `update_time`,
    ADD COLUMN `update_user_id` int DEFAULT NULL COMMENT '最近修改人用户ID' AFTER `create_user_id`,
    ADD KEY `idx_client_create_user_id` (`create_user_id`),
    ADD KEY `idx_client_update_user_id` (`update_user_id`);

UPDATE `t_seatunnel_web_client`
SET `create_user_id` = 1
WHERE `create_user_id` IS NULL;

UPDATE `t_seatunnel_web_client`
SET `update_user_id` = 1
WHERE `update_user_id` IS NULL;

ALTER TABLE `t_seatunnel_web_client`
    MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    MODIFY COLUMN `create_user_id` int NOT NULL DEFAULT 1 COMMENT '创建人用户ID',
    MODIFY COLUMN `update_user_id` int NOT NULL DEFAULT 1 COMMENT '最近修改人用户ID';

ALTER TABLE `t_seatunnel_web_job_definition`
    ADD COLUMN `create_user_id` int DEFAULT NULL COMMENT '创建人用户ID' AFTER `update_time`,
    ADD COLUMN `update_user_id` int DEFAULT NULL COMMENT '最近修改人用户ID' AFTER `create_user_id`,
    ADD KEY `idx_job_definition_create_user_id` (`create_user_id`),
    ADD KEY `idx_job_definition_update_user_id` (`update_user_id`);

UPDATE `t_seatunnel_web_job_definition`
SET `create_user_id` = 1
WHERE `create_user_id` IS NULL;

UPDATE `t_seatunnel_web_job_definition`
SET `update_user_id` = 1
WHERE `update_user_id` IS NULL;

ALTER TABLE `t_seatunnel_web_job_definition`
    MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    MODIFY COLUMN `create_user_id` int NOT NULL DEFAULT 1 COMMENT '创建人用户ID',
    MODIFY COLUMN `update_user_id` int NOT NULL DEFAULT 1 COMMENT '最近修改人用户ID';

ALTER TABLE `t_seatunnel_web_streaming_job_definition`
    ADD COLUMN `create_user_id` int DEFAULT NULL COMMENT '创建人用户ID' AFTER `update_time`,
    ADD COLUMN `update_user_id` int DEFAULT NULL COMMENT '最近修改人用户ID' AFTER `create_user_id`,
    ADD KEY `idx_streaming_definition_create_user_id` (`create_user_id`),
    ADD KEY `idx_streaming_definition_update_user_id` (`update_user_id`);

UPDATE `t_seatunnel_web_streaming_job_definition`
SET `create_user_id` = 1
WHERE `create_user_id` IS NULL;

UPDATE `t_seatunnel_web_streaming_job_definition`
SET `update_user_id` = 1
WHERE `update_user_id` IS NULL;

ALTER TABLE `t_seatunnel_web_streaming_job_definition`
    MODIFY COLUMN `update_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    MODIFY COLUMN `create_user_id` int NOT NULL DEFAULT 1 COMMENT '创建人用户ID',
    MODIFY COLUMN `update_user_id` int NOT NULL DEFAULT 1 COMMENT '最近修改人用户ID';
