ALTER TABLE `t_seatunnel_web_file_upload_record`
    ADD COLUMN `started_at` timestamp;

ALTER TABLE `t_seatunnel_web_file_upload_record`
    ADD COLUMN `finished_at` timestamp;

ALTER TABLE `t_seatunnel_web_file_upload_record`
    ADD COLUMN `duration_ms` bigint;
