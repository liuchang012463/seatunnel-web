-- Amazon S3/MinIO binary object Source/Sink metadata for SeaTunnel 2.3.13.
START TRANSACTION;

INSERT INTO `t_seatunnel_web_connector_param_meta`
(`type`, `connector_name`, `connector_type`, `param_name`, `param_desc`,
 `param_type`, `required_flag`, `default_value`, `example_value`, `param_context`,
 `remark`, `deleted`)
VALUES
('S3', 'S3File', 'SOURCE', 'path', '来源对象前缀。', 'string', 1, NULL, '/incoming', '{}', 'SeaTunnel 2.3.13 binary source', 0),
('S3', 'S3File', 'SOURCE', 'file_filter_pattern', '对象名正则过滤。', 'string', 0, '.*', '.*\\.(zip|bin)', '{}', 'SeaTunnel 2.3.13', 0),
('S3', 'S3File', 'SOURCE', 'binary_chunk_size', '二进制分块大小（字节）。', 'number', 0, '1048576', '1048576', '{}', 'SeaTunnel 2.3.13', 0),
('S3', 'S3File', 'SOURCE', 'binary_complete_file_mode', '单条记录是否包含完整对象。', 'boolean', 0, 'true', 'true', '{}', 'SeaTunnel 2.3.13', 0),
('S3', 'S3File', 'SINK', 'path', '目标对象前缀。', 'string', 1, NULL, '/archive', '{}', 'SeaTunnel 2.3.13 binary sink', 0),
('MINIO', 'S3File', 'SOURCE', 'path', '来源对象前缀。', 'string', 1, NULL, '/incoming', '{}', 'SeaTunnel 2.3.13 binary source', 0),
('MINIO', 'S3File', 'SOURCE', 'file_filter_pattern', '对象名正则过滤。', 'string', 0, '.*', '.*\\.(zip|bin)', '{}', 'SeaTunnel 2.3.13', 0),
('MINIO', 'S3File', 'SOURCE', 'binary_chunk_size', '二进制分块大小（字节）。', 'number', 0, '1048576', '1048576', '{}', 'SeaTunnel 2.3.13', 0),
('MINIO', 'S3File', 'SOURCE', 'binary_complete_file_mode', '单条记录是否包含完整对象。', 'boolean', 0, 'true', 'true', '{}', 'SeaTunnel 2.3.13', 0),
('MINIO', 'S3File', 'SINK', 'path', '目标对象前缀。', 'string', 1, NULL, '/archive', '{}', 'SeaTunnel 2.3.13 binary sink', 0);

COMMIT;
