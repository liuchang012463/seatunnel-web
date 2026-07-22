-- PostgreSQL CDC Source parameter metadata for SeaTunnel 2.3.13.
START TRANSACTION;

INSERT INTO `t_seatunnel_web_connector_param_meta`
(`type`, `connector_name`, `connector_type`, `param_name`, `param_desc`,
 `param_type`, `required_flag`, `default_value`, `example_value`, `param_context`,
 `remark`, `deleted`)
VALUES
('connector', 'Postgres-CDC', 'source', 'slot.name', 'PostgreSQL 逻辑复制 slot 名称；同一 slot 不可被多个任务同时使用。', 'string', 1, NULL, 'seatunnel_orders', '{"summary":"预创建的 pgoutput replication slot。","cautions":["必须全局唯一。","Web 不会自动创建或删除 slot。"]}', 'CDC 必填参数', 0),
('connector', 'Postgres-CDC', 'source', 'publicationName', '预创建的 PostgreSQL publication 名称，Builder 会作为 Debezium publication.name 透传。', 'string', 1, NULL, 'seatunnel_orders_pub', '{"summary":"仅使用预创建 publication。","cautions":["publication 必须覆盖已选表。","不自动创建 publication。"]}', 'CDC 必填参数', 0),
('connector', 'Postgres-CDC', 'source', 'startup.mode', 'CDC 启动模式，可选 initial、earliest 或 latest。', 'enum', 0, 'initial', 'initial', '{"validValues":["initial","earliest","latest"],"summary":"initial 先读取快照，再读取 WAL 增量。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Postgres-CDC', 'source', 'snapshot.split.size', '快照读取时每个 split 的目标行数。', 'number', 0, '8096', '20000', '{"summary":"控制快照分片粒度。"}', 'CDC 调优参数', 0),
('connector', 'Postgres-CDC', 'source', 'snapshot.fetch.size', '快照读取每次轮询获取的最大行数。', 'number', 0, '1024', '2048', '{"summary":"控制 JDBC 拉取批次。"}', 'CDC 调优参数', 0),
('connector', 'Postgres-CDC', 'source', 'connection.pool.size', '快照阶段 JDBC 连接池大小。', 'number', 0, '20', '20', '{"summary":"应与源库连接上限协调。"}', 'CDC 调优参数', 0),
('connector', 'Postgres-CDC', 'source', 'connect.timeout.ms', '连接 PostgreSQL 的超时时间（毫秒）。', 'duration', 0, '30000', '60000', '{"summary":"网络或认证失败的最大等待时间。"}', 'CDC 调优参数', 0),
('connector', 'Postgres-CDC', 'source', 'connect.max-retries', '建立 PostgreSQL 连接失败后的最大重试次数。', 'number', 0, '3', '5', '{"summary":"短暂网络故障的重试次数。"}', 'CDC 调优参数', 0),
('connector', 'Postgres-CDC', 'source', 'exactly_once', '是否启用 Exactly Once 语义。', 'boolean', 0, 'false', 'true', '{"summary":"还需要可靠 checkpoint 与下游配合。"}', 'CDC 调优参数', 0),
('connector', 'Postgres-CDC', 'source', 'format', 'CDC 输出格式，可选 DEFAULT 或 COMPATIBLE_DEBEZIUM_JSON。', 'enum', 0, 'DEFAULT', 'DEFAULT', '{"validValues":["DEFAULT","COMPATIBLE_DEBEZIUM_JSON"]}', 'CDC 调优参数', 0),
('connector', 'Postgres-CDC', 'source', 'table-names-config', '表级主键与快照分片列覆盖配置。', 'array', 0, NULL, '[{"table":"db.public.orders","primaryKeys":["id"],"snapshotSplitColumn":"id"}]', '{"summary":"用于无主键或需要覆盖快照分片列的表。"}', 'CDC 高级参数', 0),
('connector', 'Postgres-CDC', 'source', 'debezium', '透传 Debezium PostgreSQL 高级配置。', 'map', 0, NULL, '{"heartbeat.interval.ms":"10000"}', '{"summary":"publication.name 由 publicationName 自动设置。","cautions":["不要覆盖 publication.name 或 publication.autocreate.mode。"]}', 'CDC 高级参数', 0)
AS incoming
ON DUPLICATE KEY UPDATE
    `param_desc` = incoming.`param_desc`,
    `param_type` = incoming.`param_type`,
    `required_flag` = incoming.`required_flag`,
    `default_value` = incoming.`default_value`,
    `example_value` = incoming.`example_value`,
    `param_context` = incoming.`param_context`,
    `remark` = incoming.`remark`,
    `update_time` = CURRENT_TIMESTAMP;

COMMIT;
