-- Elasticsearch Source/Sink parameter metadata for SeaTunnel 2.3.13.
START TRANSACTION;

INSERT INTO `t_seatunnel_web_connector_param_meta`
(`type`, `connector_name`, `connector_type`, `param_name`, `param_desc`,
 `param_type`, `required_flag`, `default_value`, `example_value`, `param_context`,
 `remark`, `deleted`)
VALUES
('connector', 'Elasticsearch', 'source', 'source', '需要读取的字段列表。', 'array', 0, NULL, '["id","name"]', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Elasticsearch', 'source', 'query', 'Elasticsearch DSL 查询对象。', 'map', 0, '{"match_all":{}}', '{"term":{"status":"READY"}}', '{"summary":"search_type=DSL 时使用。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Elasticsearch', 'source', 'searchType', '查询类型。', 'enum', 0, 'DSL', 'DSL', '{"validValues":["DSL","SQL"]}', 'SeaTunnel 2.3.13 search_type', 0),
('connector', 'Elasticsearch', 'source', 'searchApiType', '分页读取 API 类型。', 'enum', 0, 'SCROLL', 'SCROLL', '{"validValues":["SCROLL","PIT"]}', 'SeaTunnel 2.3.13 search_api_type', 0),
('connector', 'Elasticsearch', 'source', 'sqlQuery', 'Elasticsearch SQL 查询语句。', 'string', 0, NULL, 'SELECT * FROM orders', '{"summary":"search_type=SQL 时必填。"}', 'SeaTunnel 2.3.13 sql_query', 0),
('connector', 'Elasticsearch', 'source', 'scrollTime', 'Scroll 上下文保留时间。', 'string', 0, '1m', '1m', '{}', 'SeaTunnel 2.3.13 scroll_time', 0),
('connector', 'Elasticsearch', 'source', 'scrollSize', '每批读取文档数量。', 'number', 0, '100', '100', '{"min":1}', 'SeaTunnel 2.3.13 scroll_size', 0),
('connector', 'Elasticsearch', 'source', 'arrayColumn', '数组字段解析配置。', 'map', 0, NULL, '{"tags":"keyword"}', '{}', 'SeaTunnel 2.3.13 array_column', 0),
('connector', 'Elasticsearch', 'source', 'pitKeepAlive', 'PIT 保留时间。', 'string', 0, '1m', '1m', '{"summary":"search_api_type=PIT 时使用。"}', 'SeaTunnel 2.3.13 pit_keep_alive', 0),
('connector', 'Elasticsearch', 'source', 'pitBatchSize', 'PIT 每批读取文档数量。', 'number', 0, '100', '100', '{"min":1}', 'SeaTunnel 2.3.13 pit_batch_size', 0),
('connector', 'Elasticsearch', 'source', 'runtimeFields', '运行时字段定义。', 'json', 0, NULL, '{"day":{"type":"keyword","script":{"source":"emit(doc.date.value)"}}}', '{}', 'SeaTunnel 2.3.13 runtime_fields', 0),
('connector', 'Elasticsearch', 'sink', 'indexType', '目标索引类型。', 'string', 0, NULL, 'CUSTOM', '{}', 'SeaTunnel 2.3.13 index_type', 0),
('connector', 'Elasticsearch', 'sink', 'primaryKeys', '文档主键字段列表。', 'array', 0, NULL, '["id"]', '{}', 'SeaTunnel 2.3.13 primary_keys', 0),
('connector', 'Elasticsearch', 'sink', 'keyDelimiter', '复合主键字段分隔符。', 'string', 0, '_', '_', '{}', 'SeaTunnel 2.3.13 key_delimiter', 0),
('connector', 'Elasticsearch', 'sink', 'schemaSaveMode', '目标索引 Schema 保存策略。', 'enum', 0, 'CREATE_SCHEMA_WHEN_NOT_EXIST', 'CREATE_SCHEMA_WHEN_NOT_EXIST', '{"validValues":["RECREATE_SCHEMA","CREATE_SCHEMA_WHEN_NOT_EXIST","ERROR_WHEN_SCHEMA_NOT_EXIST","IGNORE"]}', 'SeaTunnel 2.3.13 schema_save_mode', 0),
('connector', 'Elasticsearch', 'sink', 'dataSaveMode', '目标索引数据保存策略。', 'enum', 0, 'APPEND_DATA', 'APPEND_DATA', '{"validValues":["DROP_DATA","APPEND_DATA","ERROR_WHEN_DATA_EXISTS"]}', 'SeaTunnel 2.3.13 data_save_mode', 0),
('connector', 'Elasticsearch', 'sink', 'maxRetryCount', '写入失败最大重试次数。', 'number', 0, '3', '3', '{"min":0}', 'SeaTunnel 2.3.13 max_retry_count', 0),
('connector', 'Elasticsearch', 'sink', 'maxBatchSize', '单批写入文档数量。', 'number', 0, '10', '10', '{"min":1}', 'SeaTunnel 2.3.13 max_batch_size', 0),
('connector', 'Elasticsearch', 'sink', 'vectorizationFields', '需要向量化的字段列表。', 'array', 0, NULL, '["content"]', '{}', 'SeaTunnel 2.3.13 vectorization_fields', 0),
('connector', 'Elasticsearch', 'sink', 'vectorDimensions', '向量字段维度。', 'number', 0, NULL, '1536', '{"min":1}', 'SeaTunnel 2.3.13 vector_dimensions', 0)
AS incoming
ON DUPLICATE KEY UPDATE
    `param_desc` = incoming.`param_desc`,
    `param_type` = incoming.`param_type`,
    `required_flag` = incoming.`required_flag`,
    `default_value` = incoming.`default_value`,
    `example_value` = incoming.`example_value`,
    `param_context` = incoming.`param_context`,
    `remark` = incoming.`remark`,
    `deleted` = incoming.`deleted`;

COMMIT;
