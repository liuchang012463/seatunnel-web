-- FTP/SFTP binary file Source/Sink metadata for SeaTunnel 2.3.13.
START TRANSACTION;

INSERT INTO `t_seatunnel_web_connector_param_meta`
(`type`, `connector_name`, `connector_type`, `param_name`, `param_desc`,
 `param_type`, `required_flag`, `default_value`, `example_value`, `param_context`,
 `remark`, `deleted`)
VALUES
('FTP', 'FtpFile', 'SOURCE', 'path', '远程来源目录。', 'string', 1, NULL, '/incoming', '{}', 'SeaTunnel 2.3.13 binary source', 0),
('FTP', 'FtpFile', 'SOURCE', 'file_filter_pattern', '文件名正则过滤。', 'string', 0, '.*', '.*\\.(zip|bin)', '{}', 'SeaTunnel 2.3.13', 0),
('FTP', 'FtpFile', 'SOURCE', 'binary_chunk_size', '二进制分块大小（字节）。', 'number', 0, '1048576', '1048576', '{}', 'SeaTunnel 2.3.13', 0),
('FTP', 'FtpFile', 'SOURCE', 'binary_complete_file_mode', '单条记录是否包含完整文件。', 'boolean', 0, 'true', 'true', '{}', 'SeaTunnel 2.3.13', 0),
('FTP', 'FtpFile', 'SINK', 'path', '远程目标目录。', 'string', 1, NULL, '/archive', '{}', 'SeaTunnel 2.3.13 binary sink', 0),
('SFTP', 'SftpFile', 'SOURCE', 'path', '远程来源目录。', 'string', 1, NULL, '/incoming', '{}', 'SeaTunnel 2.3.13 binary source', 0),
('SFTP', 'SftpFile', 'SOURCE', 'file_filter_pattern', '文件名正则过滤。', 'string', 0, '.*', '.*\\.(zip|bin)', '{}', 'SeaTunnel 2.3.13', 0),
('SFTP', 'SftpFile', 'SOURCE', 'binary_chunk_size', '二进制分块大小（字节）。', 'number', 0, '1048576', '1048576', '{}', 'SeaTunnel 2.3.13', 0),
('SFTP', 'SftpFile', 'SOURCE', 'binary_complete_file_mode', '单条记录是否包含完整文件。', 'boolean', 0, 'true', 'true', '{}', 'SeaTunnel 2.3.13', 0),
('SFTP', 'SftpFile', 'SINK', 'path', '远程目标目录。', 'string', 1, NULL, '/archive', '{}', 'SeaTunnel 2.3.13 binary sink', 0);

COMMIT;
