import {
  DeleteOutlined,
  InfoCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import {
  Button,
  Collapse,
  DatePicker,
  Input,
  InputNumber,
  message,
  Modal,
  Select,
  Switch,
  Tooltip,
} from 'antd';
import dayjs from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import { dataSourceCatalogApi } from '@/pages/data-source/service';
import './HttpNodeConfig.less';
import {
  asRecord,
  getHttpIncrementalDefaults,
  toObject,
  toRows,
  type KeyValueRow,
} from './HttpNodeConfigUtils';
import {
  buildHttpSchemaFieldCandidates,
  findHttpContentFields,
  formatHttpSchemaSample,
  mergeHttpSchemaFieldTypes,
  SEATUNNEL_HTTP_SCHEMA_TYPES,
  type HttpContentFieldOption,
  type HttpSchemaFieldCandidate,
} from './HttpResponseSchemaUtils';
import { detectHttpTimeFormat } from './HttpTimeFormatUtils';

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
      {},
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
    const requestDefaults = getHttpIncrementalDefaults(config);
    const patch: Record<string, any> = {};
    if (incrementalConfig.enabled !== true) {
      patch.enabled = true;
    }
    if (incrementalConfig.fieldName === undefined || incrementalConfig.fieldName === null) {
      patch.fieldName = '';
    }
    if (!incrementalConfig.timeFormat) {
      patch.timeFormat = DEFAULT_HTTP_TIME_FORMAT;
    }
    if (incrementalConfig.startValue === undefined || incrementalConfig.startValue === null) {
      patch.startValue = '1970-01-01 00:00:00';
    }
    if (Object.keys(patch).length || Object.keys(requestDefaults).length) {
      onChange({
        ...requestDefaults,
        ...(Object.keys(patch).length
          ? { incrementalConfig: { ...incrementalConfig, ...patch } }
          : {}),
      });
    }
  }, [config, isIncremental, onChange]);

  const patchPageing = (patch: Record<string, any>) =>
    onChange({ pageing: { ...pageConfig, ...patch } });

  const setPageType = (value: string) =>
    onChange({ pageing: value === 'NONE' ? undefined : { page_type: value } });

  const schemaConfig = asRecord(config.schema);
  const schemaFields = asRecord(schemaConfig.fields ?? schemaConfig);
  const incrementalConfig = asRecord(config.incrementalConfig);
  const [parseLoading, setParseLoading] = useState(false);
  const [parsedJson, setParsedJson] = useState<unknown>();
  const [parsedStatus, setParsedStatus] = useState<number>();
  const [contentFieldOptions, setContentFieldOptions] = useState<HttpContentFieldOption[]>([]);
  const [schemaCandidates, setSchemaCandidates] = useState<HttpSchemaFieldCandidate[]>([]);
  const [timeFormatSample, setTimeFormatSample] = useState('');
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
  const timeFieldOptions = Object.entries(selectedSchemaFields)
    .filter(([, type]) => /date|time|timestamp/i.test(String(type || '')))
    .map(([name, type]) => ({
      label: `${name}（${type}）`,
      value: name,
    }));
  // Render the saved schema even when the endpoint cannot be parsed again,
  // so opening an existing task never hides the recognized fields.
  const savedSchemaCandidates = Object.entries(selectedSchemaFields).map(([name, type]) => ({
    name,
    sample: undefined,
    inferredType: String(type || 'string'),
  }));
  const schemaFieldRows = schemaCandidates.length ? schemaCandidates : savedSchemaCandidates;
  const isSavedSchemaOnly = schemaCandidates.length === 0 && savedSchemaCandidates.length > 0;

  useEffect(() => {
    if (format !== 'json' || !selectedContentField) {
      return;
    }

    const persistedCandidates = Object.entries(selectedSchemaFields).map(([name, type]) => ({
      name,
      sample: undefined,
      inferredType: String(type || 'string'),
    }));

    // Render the saved selection immediately. The best-effort request below
    // enriches it with the latest response sample, but a failed request must
    // never hide the schema already stored in the task definition.
    setContentFieldOptions((current) => {
      if (current.some((option) => option.value === selectedContentField)) {
        return current;
      }
      return [
        { value: selectedContentField, label: `${selectedContentField}（已保存）` },
        ...current,
      ];
    });
    setSchemaCandidates((current) => (current.length ? current : persistedCandidates));

    if (!config.dataSourceId || !String(config.path || '').trim()) {
      return;
    }

    let cancelled = false;
    const restoreResponseSchema = async () => {
      try {
        const response = await dataSourceCatalogApi.parseHttpResponse(String(config.dataSourceId), {
          path: config.path,
          method,
          params: config.params || {},
          headers: config.headers || {},
          body: config.body || '',
        });
        if (cancelled || response?.code !== 0) {
          return;
        }

        const responseJson = response?.data?.json ?? JSON.parse(response?.data?.body || 'null');
        const options = findHttpContentFields(responseJson);
        if (!options.some((option) => option.value === selectedContentField)) {
          options.unshift({ value: selectedContentField, label: `${selectedContentField}（已保存）` });
        }
        setParsedJson(responseJson);
        setParsedStatus(response?.data?.status);
        setContentFieldOptions(options);
        const responseCandidates = buildHttpSchemaFieldCandidates(responseJson, selectedContentField);
        setSchemaCandidates(responseCandidates.length ? responseCandidates : persistedCandidates);
        // Fill types that were never saved (for example tasks created before
        // type inference existed) with the recommended type from the sample.
        const recommendedTypes = responseCandidates.reduce<Record<string, string>>(
          (patch, candidate) => {
            if (!String(selectedSchemaFields[candidate.name] || '').trim() && candidate.inferredType) {
              patch[candidate.name] = candidate.inferredType;
            }
            return patch;
          },
          {},
        );
        if (Object.keys(recommendedTypes).length) {
          onChange({ schema: { fields: { ...selectedSchemaFields, ...recommendedTypes } } });
        }
      } catch {
        // Keep the persisted field names and types visible when the endpoint
        // is temporarily unavailable while opening the edit page.
      }
    };

    void restoreResponseSchema();
    return () => {
      cancelled = true;
    };
  }, [config.body, config.dataSourceId, config.headers, config.params, config.path, format, method, selectedContentField, selectedSchemaFields]);

  useEffect(() => {
    if (parsedRequestRef.current && parsedRequestRef.current !== requestSignature) {
      parsedRequestRef.current = undefined;
      setParsedJson(undefined);
      setParsedStatus(undefined);
      setContentFieldOptions([]);
      setSchemaCandidates([]);
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
    const nextFields = mergeHttpSchemaFieldTypes(candidates, selectedSchemaFields);
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

  const handleDetectTimeFormat = () => {
    const detection = detectHttpTimeFormat(timeFormatSample);
    if (detection.kind === 'unsupported') {
      const warning = {
        timezone: '样例包含时区；请先统一时区并去掉时区后缀，再识别本地日期时间格式。',
        epoch: '样例像 Unix 时间戳；请先转换为日期时间文本，再识别格式。',
        invalid: '未识别该样例。请粘贴有效的本地日期或日期时间，例如 2024-06-15T09:30:12.123456。',
      }[detection.reason];
      message.warning(warning);
      return;
    }

    Modal.confirm({
      title: '确认应用识别到的时间格式？',
      content: (
        <div className="space-y-2 text-sm">
          <div>样例预览：<code>{detection.sample}</code></div>
          <div>Java 时间格式：<code>{detection.pattern}</code></div>
          <div>只识别本地日期时间；时区和 Unix 时间戳不会自动转换。</div>
        </div>
      ),
      okText: '应用格式',
      cancelText: '保留当前格式',
      onOk: () =>
        onChange({
          incrementalConfig: {
            ...incrementalConfig,
            enabled: true,
            timeFormat: detection.pattern,
          },
        }),
    });
  };

  return (
    <div className="workflow-panel__form-grid">
      <Field
        label="请求相对路径"
        required
        hint="只填相对路径，例如 /v1/orders。"
      >
        <Input
          value={config.path || ''}
          placeholder="/v1/orders（不可填写完整 URL）"
          onChange={(event) => onChange({ path: event.target.value })}
        />
      </Field>

      <Field label="请求方法" required hint="支持 GET 和 POST。">
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
        hint="JSON 需配置 Schema；文本输出 content 字段。"
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
        <>
          <Field
            label="增量时间格式"
            required
            hint="时间字段的格式，例如 yyyy-MM-dd HH:mm:ss。"
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
          <Field
            label="粘贴时间样例"
            hint="自动识别本地日期时间格式。"
          >
            <div className="flex gap-2">
              <Input
                value={timeFormatSample}
                placeholder="2024-06-15T09:30:12.123456"
                onChange={(event) => setTimeFormatSample(event.target.value)}
                onPressEnter={handleDetectTimeFormat}
              />
              <Button onClick={handleDetectTimeFormat} disabled={!timeFormatSample.trim()}>
                识别格式
              </Button>
            </div>
          </Field>
        </>
      )}

      <Field
        label="Query Params"
        hint={
          isIncremental
            ? '键名按接口文档；时间值用 ${window_start}/${window_end}。首次 start 为起始时间、end 为调度时间，之后自动接续上次 end。'
            : '每行一个键值对；值支持 ${window_start}/${window_end}。'
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
        hint="认证 Header 由数据源统一生成，无需重复填写。"
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
              ? '键名按接口文档；时间值用 ${window_start}/${window_end}。首次 start 为起始时间、end 为调度时间，之后自动接续上次 end。'
              : '按接口要求填写；值支持 ${window_start}/${window_end}。'
          }
        >
          <Input.TextArea
            rows={5}
            value={config.body || ''}
            placeholder={
              isIncremental
                ? '{"from":${window_start},"to":${window_end}}'
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
            <Tooltip title="先解析响应，再选内容字段和类型。">
              <InfoCircleOutlined className="ml-1 text-slate-400" />
            </Tooltip>
          </div>
          <div className="http-node-config__parse-actions">
            <Button type="primary" loading={parseLoading} onClick={handleParseHttp}>
              解析接口
            </Button>
            <span>填好请求参数后执行一次请求。</span>
          </div>

          {parsedJson !== undefined && (
            <div className="http-node-config__parse-result">
              接口返回 {parsedStatus ? `HTTP ${parsedStatus}` : 'JSON'}，请选择记录所在的内容字段。
            </div>
          )}

          {(parsedJson !== undefined || selectedContentField || schemaFieldRows.length > 0) && (
            <>
              <Field
                label="内容字段"
                required
                hint="选择记录数组的路径，例如 $.data.*。"
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
                <Field label="Schema 字段" required hint="已按样例预填类型，可手动修改。">
                  {schemaFieldRows.length > 0 ? (
                    <div className="http-node-config__schema-list">
                      {schemaFieldRows.map((field) => (
                        <div className="http-node-config__schema-row" key={field.name}>
                          <div className="http-node-config__schema-name">{field.name}</div>
                          <div className="http-node-config__schema-sample">
                            {isSavedSchemaOnly
                              ? `已保存类型 ${selectedSchemaFields[field.name] || 'string'}`
                              : `示例：${formatHttpSchemaSample(field.sample)} · 建议 ${field.inferredType}`}
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
                  label="代表时间字段（可选）"
                  hint="不选时按调度窗口取数，不依赖响应时间字段。"
                >
                  <Select
                    value={incrementalConfig.fieldName ?? ''}
                    options={[
                      { label: '不指定（按调度窗口）', value: '' },
                      ...timeFieldOptions,
                    ]}
                    placeholder="不指定（按调度窗口）"
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
                      如需按响应时间增量，请先把上方时间字段类型设为 date、time 或 timestamp。
                    </div>
                  )}
                </Field>
              )}
              {isIncremental && selectedContentField && incrementalConfig.fieldName && (
                <Field
                  label="增量起始时间"
                  hint="首次请求的 window_start；之后自动接续上次 window_end。"
                >
                  <DatePicker
                    showTime
                    format="YYYY-MM-DD HH:mm:ss"
                    style={{ width: '100%' }}
                    value={
                      incrementalConfig.startValue
                        ? dayjs(incrementalConfig.startValue, 'YYYY-MM-DD HH:mm:ss')
                        : null
                    }
                    placeholder="请选择首次请求的起始时间"
                    onChange={(value) =>
                      onChange({
                        incrementalConfig: {
                          ...incrementalConfig,
                          enabled: true,
                          startValue: value ? value.format('YYYY-MM-DD HH:mm:ss') : '',
                        },
                      })
                    }
                  />
                </Field>
              )}
              {parsedJson !== undefined && (
                <details className="http-node-config__response-details">
                  <summary>查看响应样例</summary>
                  <pre>{JSON.stringify(parsedJson, null, 2)}</pre>
                </details>
              )}
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
                    <Field label="JSON 字段映射" hint="把响应 JSONPath 映射为输出字段。">                      <KeyValueEditor
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

                <Field label="分页方式" hint="分页参数用 ${page} 传递。">
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
                    <Field label="启用占位符替换" hint="把 ${page} 替换为当前页码。">
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

                <Field label="重试次数" hint="失败后的重试次数，默认 3 次。">
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
                <Field label="兼容请求参数模式" hint="仅兼容旧版参数行为时开启。">
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
