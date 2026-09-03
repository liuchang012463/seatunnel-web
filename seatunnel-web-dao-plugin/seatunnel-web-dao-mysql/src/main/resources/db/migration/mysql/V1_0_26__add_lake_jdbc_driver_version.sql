ALTER TABLE `t_seatunnel_web_lake_jdbc_driver`
    ADD COLUMN `version` bigint NOT NULL DEFAULT 1 COMMENT '驱动注册版本，用于节点缓存失效' AFTER `status`;
