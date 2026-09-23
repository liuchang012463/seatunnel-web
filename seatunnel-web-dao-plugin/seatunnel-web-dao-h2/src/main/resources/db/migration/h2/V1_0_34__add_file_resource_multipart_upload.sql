ALTER TABLE `t_seatunnel_web_file_upload_record`
    ADD COLUMN `logical_path` varchar(1024),
    ADD COLUMN `object_key` varchar(1024),
    ADD COLUMN `content_type` varchar(255),
    ADD COLUMN `multipart_upload_id` varchar(1024),
    ADD COLUMN `part_size` bigint;
