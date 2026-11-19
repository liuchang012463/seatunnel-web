ALTER TABLE `t_seatunnel_web_file_upload_record`
    ADD COLUMN `started_at` datetime DEFAULT NULL COMMENT '上传开始时间',
    ADD COLUMN `finished_at` datetime DEFAULT NULL COMMENT '上传完成时间',
    ADD COLUMN `duration_ms` bigint DEFAULT NULL COMMENT '上传耗时毫秒';
