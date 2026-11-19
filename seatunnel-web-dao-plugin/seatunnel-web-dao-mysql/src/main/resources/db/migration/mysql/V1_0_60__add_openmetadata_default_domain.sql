ALTER TABLE `t_seatunnel_web_openmetadata_config`
    ADD COLUMN `default_domain_fqn` VARCHAR(1024) NULL COMMENT '新建 OpenMetadata 服务使用的默认 Domain FQN';
