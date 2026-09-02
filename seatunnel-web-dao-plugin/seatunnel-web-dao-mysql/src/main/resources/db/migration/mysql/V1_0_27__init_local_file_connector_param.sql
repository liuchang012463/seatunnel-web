-- LOCAL_FILE binary file Source/Sink metadata for SeaTunnel 2.3.13.
START TRANSACTION;

INSERT INTO `t_seatunnel_web_connector_param_meta`
(`type`, `connector_name`, `connector_type`, `param_name`, `param_desc`,
 `param_type`, `required_flag`, `default_value`, `example_value`, `param_context`,
 `remark`, `deleted`)
VALUES
('LOCAL_FILE', 'LocalFile', 'SOURCE', 'path', '本机来源目录。', 'string', 1, NULL, '/data/seatunnel/localfile/incoming', '{}', 'SeaTunnel 2.3.13 binary source', 0),
('LOCAL_FILE', 'LocalFile', 'SOURCE', 'file_filter_pattern', '文件名正则过滤。', 'string', 0, '.*', '.*\\.(zip|bin)', '{}', 'SeaTunnel 2.3.13', 0),
('LOCAL_FILE', 'LocalFile', 'SOURCE', 'filename_extension', '扩展名过滤。', 'string', 0, NULL, 'zip,bin', '{}', 'SeaTunnel 2.3.13', 0),
('LOCAL_FILE', 'LocalFile', 'SOURCE', 'binary_chunk_size', '二进制分块大小（字节）。', 'number', 0, '1048576', '1048576', '{}', 'SeaTunnel 2.3.13', 0),
('LOCAL_FILE', 'LocalFile', 'SOURCE', 'binary_complete_file_mode', '单条记录是否包含完整文件。', 'boolean', 0, 'true', 'true', '{}', 'SeaTunnel 2.3.13', 0),
('LOCAL_FILE', 'LocalFile', 'SINK', 'path', '本机目标目录。', 'string', 1, NULL, '/data/seatunnel/localfile/archive', '{}', 'SeaTunnel 2.3.13 binary sink', 0);

COMMIT;
