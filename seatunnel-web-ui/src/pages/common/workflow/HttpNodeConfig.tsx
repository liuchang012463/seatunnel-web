import {
  DeleteOutlined,
  InfoCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import {
  Button,
  Collapse,
  Input,
  InputNumber,
  message,
  Select,
  Switch,
  Tooltip,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { dataSourceCatalogApi } from '@/pages/data-source/service';
import './HttpNodeConfig.less';
import {
  asRecord,
  toObject,
  toRows,
  type KeyValueRow,
} from './HttpNodeConfigUtils';
import {
  buildHttpSchemaFieldCandidates,
  findHttpContentFields,
  formatHttpSchemaSample,
  SEATUNNEL_HTTP_SCHEMA_TYPES,
  type HttpContentFieldOption,
  type HttpSchemaFieldCandidate,
} from './HttpResponseSchemaUtils';

type Props = {
  streaming?: boolean;
  isIncremental?: boolean;
  config: Record<string, any>;
  onChange: (patch: Record<string, any>) => void;
};

const DEFAULT_HTTP_CONFIG = {
  method: 'GET',
  format: 'text',
  retry: 3,
  retryBackoffMultiplierMs: 1000,
  retryBackoffMaxMs: 10000,
  enableMultiLines: false,
  keepParamsAsForm: false,
  keepPageParamAsHttpParam: false,
};
const DEFAULT_HTTP_TIME_FORMAT = 'yyyy-MM-dd HH:mm:ss';

function getHttpErrorMessage(error: any, fallback: string) {
  const candidates = [
    error?.response?.msg,
    error?.response?.message,
    error?.data?.msg,
    error?.data?.message,
    error?.msg,
    error?.message,
  ];
  const messageText = candidates.find(
    (value) => typeof value === 'string' && value.trim() && value.trim() !== '[object Object]',
  );
  return messageText ? messageText.trim() : fallback;
}

function Field({
  label,
  hint,
  required = false,
  children,
}: {
  label: string;
  hint?: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="workflow-panel__field workflow-panel__field--full">
      <div className="workflow-panel__label">
        {required && <span className="mr-1 text-rose-500">*</span>}
        {label}
        {hint && (
          <Tooltip title={hint}>
            <InfoCircleOutlined className="ml-1 text-slate-400" />
          </Tooltip>
        )}
      </div>
      {children}
    </div>
  );
}

function KeyValueEditor({
  value,
  keyPlaceholder,
  valuePlaceholder,
  onChange,
}: {
  value: unknown;
  keyPlaceholder: string;
  valuePlaceholder: string;
  onChange: (value: Record<string, string>) => void;
}) {
  const [rows, setRows] = useState<KeyValueRow[]>(() => toRows(value));
  const emittedValueRef = useRef<string | null>(null);

  useEffect(() => {
    const serializedValue = JSON.stringify(asRecord(value));
    if (emittedValueRef.current === serializedValue) {
      emittedValueRef.current = null;
      return;
    }
    setRows(toRows(value));
  }, [value]);

  const updateRows = (nextRows: KeyValueRow[]) => {
    setRows(nextRows);
    const nextValue = toObject(nextRows);
    emittedValueRef.current = JSON.stringify(nextValue);
    onChange(nextValue);
  };

  return (
    <div className="flex flex-col gap-2">
      {rows.map((row, index) => (
        <div className="flex items-center gap-2" key={`${row.key}-${index}`}>
          <Input
            value={row.key}
            placeholder={keyPlaceholder}
            onChange={(event) => {
              const nextRows = [...rows];
              nextRows[index] = { ...row, key: event.target.value };
              updateRows(nextRows);
            }}
          />
          <Input
            value={row.value}
            placeholder={valuePlaceholder}
            onChange={(event) => {
              const nextRows = [...rows];
              nextRows[index] = { ...row, value: event.target.value };
              updateRows(nextRows);
            }}
          />
          <Button
            type="text"
            danger
            icon={<DeleteOutlined />}
            aria-label="删除参数"
            onClick={() => updateRows(rows.filter((_, rowIndex) => rowIndex !== index))}
          />
        </div>
      ))}
      <Button
        type="dashed"
        icon={<PlusOutlined />}
        onClick={() => updateRows([...rows, { key: '', value: '' }])}
      >
        添加一行
      </Button>
    </div>
  );
}

function ChoiceGroup({
  value,
  options,
  onChange,
}: {
  value: string;
  options: Array<{ label: string; value: string }>;
  onChange: (value: string) => void;
}) {
  return (
    <div className="http-node-config__choice-group" role="radiogroup">
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={selected}
            className={`http-node-config__choice${selected ? ' is-selected' : ''}`}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

export default function HttpNodeConfig({ streaming, isIncremental = false, config, onChange }: Props) {
  const method = String(config.method || DEFAULT_HTTP_CONFIG.method).toUpperCase();
  const format = String(config.format || DEFAULT_HTTP_CONFIG.format).toLowerCase();
  const pageType = config.pageing
    ? config.pageing.page_type || 'PageNumber'
    : 'NONE';
  const pageConfig = asRecord(config.pageing);

  useEffect(() => {
    const defaults = Object.entries(DEFAULT_HTTP_CONFIG).reduce<Record<string, any>>(
      (patch, [key, value]) => {
        if (config[key] === undefined || config[key] === null) {
          patch[key] = value;
        }
        return patch;
      },
    );

    if (Object.keys(defaults).length) {
      onChange(defaults);
    }
  }, [config, onChange]);

  useEffect(() => {
    if (!isIncremental) {
      return;
    }
    const incrementalConfig = asRecord(config.incrementalConfig);
    const patch: Record<string, any> = {};
    if (incrementalConfig.enabled !== true) {
      patch.enabled = true;
    }
    if (incrementalConfig.fieldName === undefined || incrementalConfig.fieldName === null) {
      patch.fieldName = '';
    }
    if (!incrementalConfig.startValue) {
      patch.startValue = '1970-01-01 00:00:00';
    }
    if (!incrementalConfig.timeFormat) {
      patch.timeFormat = DEFAULT_HTTP_TIME_FORMAT;
    }
    if (Object.keys(patch).length) {
      onChange({ incrementalConfig: { ...incrementalConfig, ...patch } });
    }
  }, [config, isIncremental, onChange]);

  const patchPageing = (patch: Record<string, any>) =>
    onChange({ pageing: { ...pageConfig, ...patch } });

  const setPageType = (value: string) =>
    onChange({ pageing: value === 'NONE' ? undefined : { page_type: value } });

  const schemaFields = asRecord(config.schema).fields;
  const incrementalConfig = asRecord(config.incrementalConfig);
  const [parseLoading, setParseLoading] = useState(false);
  const [parsedJson, setParsedJson] = useState<unknown>();
  const [parsedStatus, setParsedStatus] = useState<number>();
  const [contentFieldOptions, setContentFieldOptions] = useState<HttpContentFieldOption[]>([]);
  const [schemaCandidates, setSchemaCandidates] = useState<HttpSchemaFieldCandidate[]>([]);
  const parsedRequestRef = useRef<string>();

  const requestSignature = JSON.stringify({
    path: config.path || '',
    method,
    params: config.params || {},
    headers: config.headers || {},
    body: config.body || '',
  });
  const selectedContentField = String(config.contentField || '');
  const selectedSchemaFields = asRecord(schemaFields);
  const timeFieldOptions = schemaCandidates
    .filter((field) => /date|time|timestamp/i.test(String(selectedSchemaFields[field.name] || '')))
    .map((field) => ({
      label: `${field.name}（${selectedSchemaFields[field.name]}）`,
      value: field.name,
    }));

  useEffect(() => {
    if (parsedRequestRef.current && parsedRequestRef.current !== requestSignature) {
      parsedRequestRef.current = undefined;
      setParsedJson(undefined);
      setParsedStatus(undefined);
      setContentFieldOptions([]);
      setSchemaCandidates([]);
      if (selectedContentField || Object.keys(selectedSchemaFields).length > 0) {
        onChange({
          contentField: '',
          schema: { fields: {} },
          ...(isIncremental
            ? { incrementalConfig: { ...incrementalConfig, fieldName: '' } }
            : {}),
        });
      }
    }
  }, [
    requestSignature,
    onChange,
    selectedContentField,
    selectedSchemaFields,
    isIncremental,
    incrementalConfig,
  ]);

  const handleParseHttp = async () => {
    if (!config.dataSourceId) {
      message.warning('请先选择 HTTP 数据源');
      return;
    }
    if (!String(config.path || '').trim()) {
      message.warning('请先填写请求相对路径');
      return;
    }

    setParseLoading(true);
    try {
      const response = await dataSourceCatalogApi.parseHttpResponse(String(config.dataSourceId), {
        path: config.path,
        method,
        params: config.params || {},
        headers: config.headers || {},
        body: config.body || '',
      });
      if (response?.code !== 0) {
        message.error(getHttpErrorMessage(response, '接口解析失败，请检查请求参数和响应格式'));
        return;
      }

      const responseJson = response?.data?.json ?? JSON.parse(response?.data?.body || 'null');
      const options = findHttpContentFields(responseJson);
      parsedRequestRef.current = requestSignature;
      setParsedJson(responseJson);
      setParsedStatus(response?.data?.status);
      setContentFieldOptions(options);
      setSchemaCandidates([]);
      onChange({
        contentField: '',
        schema: { fields: {} },
        ...(isIncremental
          ? { incrementalConfig: { ...incrementalConfig, fieldName: '' } }
          : {}),
      });
    } catch (error: any) {
      message.error(getHttpErrorMessage(error, '接口解析失败，请检查请求参数和响应格式'));
    } finally {
      setParseLoading(false);
    }
  };

  const handleContentFieldChange = (value: string) => {
    const candidates = buildHttpSchemaFieldCandidates(parsedJson, value);
    const nextFields = candidates.reduce<Record<string, string>>((fields, candidate) => {
      fields[candidate.name] = String(selectedSchemaFields[candidate.name] || '');
      return fields;
    }, {});
    setSchemaCandidates(candidates);
    onChange({
      contentField: value,
      schema: { fields: nextFields },
      ...(isIncremental
        ? { incrementalConfig: { ...incrementalConfig, fieldName: '' } }
        : {}),
    });
  };

  const handleSchemaTypeChange = (fieldName: string, value: string) => {
    onChange({
      schema: {
        fields: {
          ...selectedSchemaFields,
          [fieldName]: value,
        },
      },
    });
  };

  return (
    <div className="workflow-panel__form-grid">
      <Field
        label="请求相对路径"
        required
        hint="只填写相对 Base URL 的接口路径，例如 /v1/orders；不要再填写 http:// 或 https://。"
      >
        <Input
          value={config.path || ''}
          placeholder="/v1/orders（不可填写完整 URL）"
          onChange={(event) => onChange({ path: event.target.value })}
        />
      </Field>

      <Field label="请求方法" required hint="SeaTunnel HTTP Source 支持 GET 和 POST。">
        <ChoiceGroup
          value={method}
          options={[
            { label: 'GET', value: 'GET' },
            { label: 'POST', value: 'POST' },
          ]}
          onChange={(value) => onChange({ method: value })}
        />
      </Field>

      <Field
        label="响应格式"
        required
        hint="JSON 响应需要在下方填写 Schema；纯文本响应会输出 content 字段。"
      >
        <ChoiceGroup
          value={format}
          options={[
            { label: '文本', value: 'text' },
            { label: 'JSON', value: 'json' },
          ]}
          onChange={(value) => onChange({ format: value })}
        />
      </Field>

      {isIncremental && (
        <Field
          label="增量时间格式"
          required
          hint="先填写接口时间字段的格式，例如 yyyy-MM-dd HH:mm:ss；系统调度时会用它格式化 start_time 和 end_time。"
        >
          <Input
            value={incrementalConfig.timeFormat || DEFAULT_HTTP_TIME_FORMAT}
            placeholder={DEFAULT_HTTP_TIME_FORMAT}
            onChange={(event) =>
              onChange({
                incrementalConfig: {
                  ...incrementalConfig,
                  enabled: true,
                  timeFormat: event.target.value,
                },
              })
            }
          />
        </Field>
      )}

      <Field
        label="Query Params"
        hint={
          isIncremental
            ? '每行一个键值对；GET 请求会由系统自动追加 start_time 和 end_time。其它值仍可使用时间占位符。'
            : '每行一个键值对；值可以使用 ${window_start} 和 ${window_end} 动态时间占位符。'
        }
      >
        <KeyValueEditor
          value={config.params}
          keyPlaceholder="参数名，例如 status"
          valuePlaceholder="参数值，例如 active"
          onChange={(value) => onChange({ params: value })}
        />
      </Field>

      <Field
        label="Headers"
        hint="每行一个键值对；认证 Header 由数据源认证方式统一生成，不需要在这里重复填写。"
      >
        <KeyValueEditor
          value={config.headers}
          keyPlaceholder="Header 名，例如 Accept"
          valuePlaceholder="Header 值，例如 application/json"
          onChange={(value) => onChange({ headers: value })}
        />
      </Field>

      {method === 'POST' && (
        <Field
          label="请求体 Body"
          hint={
            isIncremental
              ? '请填写 JSON 对象中的固定业务参数；系统会在每次调度时自动注入 start_time 和 end_time。'
              : '按接口要求填写 JSON 或文本；值可以使用 ${window_start} 和 ${window_end}。'
          }
        >
          <Input.TextArea
            rows={5}
            value={config.body || ''}
            placeholder={
              isIncremental
                ? '例如 {"status":"active"}；start_time/end_time 由系统自动注入'
                : '{"start_time":"${window_start}","end_time":"${window_end}"}'
            }
            onChange={(event) => onChange({ body: event.target.value })}
          />
        </Field>
      )}

      {format === 'json' && (
        <div className="workflow-panel__field workflow-panel__field--full http-node-config__schema-flow">
          <div className="workflow-panel__label">
            <span className="mr-1 text-rose-500">*</span>
            响应字段与 Schema
            <Tooltip title="先解析接口响应，再选择内容字段，最后为每个字段选择 SeaTunnel 类型。">
              <InfoCircleOutlined className="ml-1 text-slate-400" />
            </Tooltip>
          </div>
          <div className="http-node-config__parse-actions">
            <Button type="primary" loading={parseLoading} onClick={handleParseHttp}>
              解析接口
            </Button>
            <span>填写完路径、方法、参数和请求体后执行一次请求。</span>
          </div>

          {parsedJson !== undefined && (
            <>
              <div className="http-node-config__parse-result">
                接口返回 {parsedStatus ? `HTTP ${parsedStatus}` : 'JSON'}，请选择记录所在的内容字段。
              </div>
              <Field
                label="内容字段"
                required
                hint="选择响应中代表记录数组的 JSONPath，例如 $.data.*。"
              >
                <Select
                  value={selectedContentField || undefined}
                  placeholder="请选择内容字段"
                  options={contentFieldOptions}
                  onChange={handleContentFieldChange}
                  style={{ width: '100%' }}
                />
              </Field>
              {selectedContentField && (
                <Field label="Schema 字段" required hint="为每个解析出的字段逐一选择 SeaTunnel 类型。">
                  {schemaCandidates.length > 0 ? (
                    <div className="http-node-config__schema-list">
                      {schemaCandidates.map((field) => (
                        <div className="http-node-config__schema-row" key={field.name}>
                          <div className="http-node-config__schema-name">{field.name}</div>
                          <div className="http-node-config__schema-sample">
                            示例：{formatHttpSchemaSample(field.sample)} · 建议 {field.inferredType}
                          </div>
                          <Select
                            value={selectedSchemaFields[field.name] || undefined}
                            placeholder="请选择类型"
                            options={SEATUNNEL_HTTP_SCHEMA_TYPES.map((type) => ({
                              label: type,
                              value: type,
                            }))}
                            onChange={(value) => handleSchemaTypeChange(field.name, value)}
                            style={{ width: 150, flexShrink: 0 }}
                          />
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="http-node-config__parse-result">当前内容字段没有可生成的对象字段。</div>
                  )}
                </Field>
              )}
              {isIncremental && selectedContentField && (
                <Field
                  label="代表时间字段"
                  required
                  hint="这是响应记录中用于增量水位判断的字段，不是系统自动传入请求的 start_time 或 end_time。"
                >
                  <Select
                    value={incrementalConfig.fieldName || undefined}
                    options={timeFieldOptions}
                    disabled={!String(incrementalConfig.timeFormat || '').trim() || timeFieldOptions.length === 0}
                    placeholder="请先为时间字段选择 date / time / timestamp 类型"
                    onChange={(value) =>
                      onChange({
                        incrementalConfig: {
                          ...incrementalConfig,
                          enabled: true,
                          fieldName: value,
                        },
                      })
                    }
                    style={{ width: '100%' }}
                  />
                  {timeFieldOptions.length === 0 && (
                    <div className="mt-1 text-xs text-slate-400">
                      请先在上方为响应中的时间字段选择 date、time 或 timestamp 类型。
                    </div>
                  )}
                </Field>
              )}
              <details className="http-node-config__response-details">
                <summary>查看响应样例</summary>
                <pre>{JSON.stringify(parsedJson, null, 2)}</pre>
              </details>
            </>
          )}
        </div>
      )}

      <Collapse
        className="workflow-panel__collapse workflow-panel__field--full"
        items={[
          {
            key: 'advanced',
            label: '高级参数',
            children: (
              <div className="workflow-panel__form-grid">
                {format === 'json' && (
                  <>
                    <Field label="JSON 字段映射" hint="把响应 JSONPath 映射为输出字段。">
                      <KeyValueEditor
                        value={config.jsonField}
                        keyPlaceholder="输出字段名，例如 order_id"
                        valuePlaceholder="JSONPath，例如 $.orderId"
                        onChange={(value) => onChange({ jsonField: value })}
                      />
                    </Field>
                    <Field label="缺失 JSON 字段返回 null">
                      <Switch
                        checked={config.jsonFieldMissedReturnNull ?? false}
                        onChange={(checked) => onChange({ jsonFieldMissedReturnNull: checked })}
                      />
                    </Field>
                  </>
                )}

                <Field label="分页方式" hint="分页参数可以放在 Params、Headers 或 Body 中，并使用 ${page}。">
                  <Select
                    value={pageType}
                    style={{ width: '100%' }}
                    options={[
                      { label: '不分页', value: 'NONE' },
                      { label: 'PageNumber', value: 'PageNumber' },
                      { label: 'Cursor', value: 'Cursor' },
                    ]}
                    onChange={setPageType}
                  />
                </Field>

                {pageType === 'PageNumber' && (
                  <>
                    <Field label="页码参数名">
                      <Input
                        value={pageConfig.page_field || 'page'}
                        placeholder="page"
                        onChange={(event) => patchPageing({ page_field: event.target.value })}
                      />
                    </Field>
                    <Field label="起始页 / 每页数量">
                      <div className="flex gap-2">
                        <InputNumber
                          value={pageConfig.page_start_from ?? 1}
                          min={1}
                          placeholder="1"
                          className="!w-1/2"
                          onChange={(value) => patchPageing({ page_start_from: value })}
                        />
                        <InputNumber
                          value={pageConfig.batch_size ?? 100}
                          min={1}
                          placeholder="100"
                          className="!w-1/2"
                          onChange={(value) => patchPageing({ batch_size: value })}
                        />
                      </div>
                    </Field>
                    <Field label="启用占位符替换" hint="将 ${page} 替换为当前页码。">
                      <Switch
                        checked={pageConfig.use_placeholder_replacement ?? true}
                        onChange={(checked) => patchPageing({ use_placeholder_replacement: checked })}
                      />
                    </Field>
                  </>
                )}

                {pageType === 'Cursor' && (
                  <>
                    <Field label="游标请求参数名">
                      <Input
                        value={pageConfig.cursor_field || 'cursor'}
                        placeholder="cursor"
                        onChange={(event) => patchPageing({ cursor_field: event.target.value })}
                      />
                    </Field>
                    <Field label="响应游标字段">
                      <Input
                        value={pageConfig.cursor_response_field || ''}
                        placeholder="$.nextCursor"
                        onChange={(event) => patchPageing({ cursor_response_field: event.target.value })}
                      />
                    </Field>
                  </>
                )}

                <Field label="重试次数" hint="请求失败后的重试次数，默认 3 次。">
                  <InputNumber
                    value={config.retry ?? DEFAULT_HTTP_CONFIG.retry}
                    min={0}
                    style={{ width: '100%' }}
                    onChange={(value) => onChange({ retry: value })}
                  />
                </Field>
                <Field label="退避初始 / 最大毫秒">
                  <div className="flex gap-2">
                    <InputNumber
                      value={config.retryBackoffMultiplierMs ?? DEFAULT_HTTP_CONFIG.retryBackoffMultiplierMs}
                      min={1}
                      className="!w-1/2"
                      onChange={(value) => onChange({ retryBackoffMultiplierMs: value })}
                    />
                    <InputNumber
                      value={config.retryBackoffMaxMs ?? DEFAULT_HTTP_CONFIG.retryBackoffMaxMs}
                      min={1}
                      className="!w-1/2"
                      onChange={(value) => onChange({ retryBackoffMaxMs: value })}
                    />
                  </div>
                </Field>

                {streaming && (
                  <Field label="轮询间隔（毫秒）">
                    <InputNumber
                      value={config.pollIntervalMillis}
                      min={1}
                      style={{ width: '100%' }}
                      onChange={(value) => onChange({ pollIntervalMillis: value })}
                    />
                  </Field>
                )}

                <Field label="多行文本模式">
                  <Switch
                    checked={config.enableMultiLines ?? DEFAULT_HTTP_CONFIG.enableMultiLines}
                    onChange={(checked) => onChange({ enableMultiLines: checked })}
                  />
                </Field>
                <Field label="兼容请求参数模式" hint="仅在接口兼容旧版 SeaTunnel 参数行为时开启。">
                  <div className="flex flex-wrap gap-4">
                    <Switch
                      checked={config.keepParamsAsForm ?? DEFAULT_HTTP_CONFIG.keepParamsAsForm}
                      checkedChildren="Form"
                      unCheckedChildren="Form"
                      onChange={(checked) => onChange({ keepParamsAsForm: checked })}
                    />
                    <Switch
                      checked={config.keepPageParamAsHttpParam ?? DEFAULT_HTTP_CONFIG.keepPageParamAsHttpParam}
                      checkedChildren="Page Param"
                      unCheckedChildren="Page Param"
                      onChange={(checked) => onChange({ keepPageParamAsHttpParam: checked })}
                    />
                  </div>
                </Field>
              </div>
            ),
          },
        ]}
      />
    </div>
  );
}
