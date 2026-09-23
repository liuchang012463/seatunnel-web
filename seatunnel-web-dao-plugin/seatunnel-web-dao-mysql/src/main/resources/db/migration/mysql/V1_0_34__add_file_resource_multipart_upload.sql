ALTER TABLE `t_seatunnel_web_file_upload_record`
    ADD COLUMN `logical_path` varchar(1024) DEFAULT NULL COMMENT '上传文件逻辑路径',
    ADD COLUMN `object_key` varchar(1024) DEFAULT NULL COMMENT '上传文件对象存储Key',
    ADD COLUMN `content_type` varchar(255) DEFAULT NULL COMMENT '上传文件MIME类型',
    ADD COLUMN `multipart_upload_id` varchar(1024) DEFAULT NULL COMMENT '对象存储分片上传ID',
    ADD COLUMN `part_size` bigint DEFAULT NULL COMMENT '对象存储分片字节数';
