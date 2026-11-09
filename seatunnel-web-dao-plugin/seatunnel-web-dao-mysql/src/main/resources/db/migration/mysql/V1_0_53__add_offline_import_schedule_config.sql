ALTER TABLE `t_seatunnel_web_offline_import_task`
    ADD COLUMN `schedule_config_json` mediumtext DEFAULT NULL COMMENT '编辑器基础与调度配置JSON';
