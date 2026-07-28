-- HTTP Source parameter metadata for SeaTunnel 2.3.13.
START TRANSACTION;

INSERT INTO `t_seatunnel_web_connector_param_meta`
(`type`, `connector_name`, `connector_type`, `param_name`, `param_desc`,
 `param_type`, `required_flag`, `default_value`, `example_value`, `param_context`,
 `remark`, `deleted`)
VALUES
('connector', 'Http', 'source', 'path', '相对于数据源 Base URL 的请求路径。', 'string', 0, NULL, '/v1/orders', '{"summary":"只允许相对路径，最终 URL 由数据源 Base URL 拼接。"}', 'SeaTunnel Web HTTP Source', 0),
('connector', 'Http', 'source', 'method', 'HTTP 请求方法。', 'enum', 0, 'GET', 'POST', '{"validValues":["GET","POST"]}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'headers', '任务级非认证请求头。', 'map', 0, NULL, '{"Accept":"application/json"}', '{"summary":"认证头由数据源认证配置生成，不允许节点覆盖。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'params', 'HTTP 查询或表单参数。', 'map', 0, NULL, '{"status":"OPEN"}', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'body', 'POST 请求体。', 'string', 0, NULL, '{"status":"OPEN"}', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'format', '响应格式。', 'enum', 0, 'text', 'json', '{"validValues":["json","text"]}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'schema', 'JSON 响应 Schema。', 'json', 0, NULL, '{"fields":{"id":"bigint"}}', '{"summary":"format=json 时必填。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'contentField', '从响应中提取记录集合的 JSONPath。', 'string', 0, NULL, '$.data.*', '{}', 'SeaTunnel 2.3.13 content_field', 0),
('connector', 'Http', 'source', 'jsonField', '字段到 JSONPath 的映射。', 'map', 0, NULL, '{"id":"$.data[*].id"}', '{}', 'SeaTunnel 2.3.13 json_field', 0),
('connector', 'Http', 'source', 'pageing', 'PageNumber 或 Cursor 分页配置。', 'json', 0, NULL, '{"page_type":"PageNumber","page_field":"page","batch_size":100}', '{"summary":"保留 SeaTunnel 原生 pageing 拼写。"}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'pollIntervalMillis', '流模式 HTTP 轮询间隔（毫秒）。', 'number', 0, NULL, '5000', '{}', 'SeaTunnel 2.3.13 poll_interval_millis', 0),
('connector', 'Http', 'source', 'retry', 'IOException 最大重试次数。', 'number', 0, NULL, '3', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'retryBackoffMultiplierMs', '重试退避乘数（毫秒）。', 'number', 0, '100', '100', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'retryBackoffMaxMs', '最大重试退避时间（毫秒）。', 'number', 0, '10000', '10000', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'enableMultiLines', '是否启用多行文本模式。', 'boolean', 0, 'false', 'false', '{}', 'SeaTunnel 2.3.13', 0),
('connector', 'Http', 'source', 'keepParamsAsForm', '是否按旧版本表单方式提交 params。', 'boolean', 0, 'false', 'false', '{}', 'SeaTunnel 2.3.13 keep_params_as_form', 0),
('connector', 'Http', 'source', 'keepPageParamAsHttpParam', '是否把分页字段写入 params。', 'boolean', 0, 'false', 'false', '{}', 'SeaTunnel 2.3.13 keep_page_param_as_http_param', 0),
('connector', 'Http', 'source', 'jsonFieldMissedReturnNull', 'JSON 字段缺失时是否返回 null。', 'boolean', 0, 'false', 'false', '{}', 'SeaTunnel 2.3.13 json_filed_missed_return_null', 0)
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
