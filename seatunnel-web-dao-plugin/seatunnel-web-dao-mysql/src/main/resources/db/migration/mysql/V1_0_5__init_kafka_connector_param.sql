-- Kafka Source/Sink parameter metadata for SeaTunnel 2.3.13.
START TRANSACTION;

INSERT INTO `t_seatunnel_web_connector_param_meta`
(`type`, `connector_name`, `connector_type`, `param_name`, `param_desc`,
 `param_type`, `required_flag`, `default_value`, `example_value`, `param_context`,
 `remark`, `deleted`)
VALUES
('connector', 'Kafka', 'source', 'topic', '订阅 Topic，支持逗号分隔多个 Topic；与 pattern 互斥。', 'string', 0, NULL, 'orders,customers', '{"summary":"多个 Topic 作为一个输入流处理。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'source', 'pattern', 'Topic 正则订阅表达式；与 topic 互斥。', 'string', 0, NULL, 'orders-.*', '{"summary":"正则匹配 Topic。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'source', 'consumerGroup', 'Kafka consumer group。', 'string', 1, NULL, 'seatunnel-orders', '{"summary":"建议每个独立消费任务使用唯一 group。"}', 'SeaTunnel 2.3.13 consumer.group', 0),
('connector', 'Kafka', 'source', 'startMode', '消费起点模式。', 'enum', 0, 'group_offsets', 'latest', '{"validValues":["earliest","group_offsets","latest","specific_offsets","timestamp"]}', 'SeaTunnel 2.3.13 start_mode', 0),
('connector', 'Kafka', 'source', 'startModeOffsets', 'specific_offsets 模式的分区位点。', 'map', 0, NULL, '{"0":100,"1":200}', '{"summary":"仅 specific_offsets 模式生效。"}', 'SeaTunnel 2.3.13 start_mode.offsets', 0),
('connector', 'Kafka', 'source', 'startModeTimestamp', 'timestamp 模式的开始时间戳（毫秒）。', 'number', 0, NULL, '1710000000000', '{"summary":"仅 timestamp 模式必填。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'source', 'startModeEndTimestamp', '消费结束时间戳（毫秒）。', 'number', 0, NULL, '1710003600000', '{"summary":"达到时间范围末尾后结束。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'source', 'commitOnCheckpoint', 'checkpoint 成功时提交 Kafka offset。', 'boolean', 0, 'true', 'true', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'source', 'pollTimeout', 'Kafka poll 超时时间（毫秒）。', 'number', 0, NULL, '1000', '{}', 'SeaTunnel 2.3.13 poll.timeout', 0),
('connector', 'Kafka', 'source', 'format', '消息格式。', 'string', 0, 'json', 'json', '{"summary":"支持 SeaTunnel 2.3.13 Kafka Connector 已提供的格式。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'source', 'schema', '消息 Schema JSON。', 'json', 0, NULL, '{"fields":{"id":"bigint"}}', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'source', 'fieldDelimiter', 'text 格式字段分隔符。', 'string', 0, NULL, ',', '{"summary":"format=text 时必填。"}', 'SeaTunnel 2.3.13 field_delimiter', 0),
('connector', 'Kafka', 'source', 'kafkaConfig', '任务级 Kafka Client 配置。', 'map', 0, NULL, '{"max.poll.records":"500"}', '{"summary":"节点配置覆盖数据源高级配置。"}', 'SeaTunnel 2.3.13 kafka.config', 0),
('connector', 'Kafka', 'sink', 'topic', '固定 Topic 或包含 ${field} 的动态 Topic。', 'string', 1, NULL, 'orders-${tenant}', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'sink', 'format', '消息格式。', 'string', 0, 'json', 'json', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'sink', 'semantics', 'Kafka Sink 投递语义。', 'enum', 0, 'NON', 'AT_LEAST_ONCE', '{"validValues":["NON","AT_LEAST_ONCE","EXACTLY_ONCE"]}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'sink', 'transactionPrefix', 'EXACTLY_ONCE 事务 ID 前缀。', 'string', 0, NULL, 'seatunnel-orders-prod', '{"summary":"EXACTLY_ONCE 时必填且必须唯一。"}', 'SeaTunnel 2.3.13 transaction_prefix', 0),
('connector', 'Kafka', 'sink', 'partition', '固定写入分区。', 'number', 0, NULL, '0', '{"summary":"与 partitionKeyFields 互斥。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Kafka', 'sink', 'partitionKeyFields', '用于计算 Kafka 分区的字段。', 'array', 0, NULL, '["tenant_id","order_id"]', '{"summary":"与 partition 互斥。"}', 'SeaTunnel 2.3.13 partition_key_fields', 0),
('connector', 'Kafka', 'sink', 'kafkaConfig', '任务级 Kafka Client 配置。', 'map', 0, NULL, '{"compression.type":"lz4"}', '{"summary":"节点配置覆盖数据源高级配置。"}', 'SeaTunnel 2.3.13 kafka.config', 0)
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
