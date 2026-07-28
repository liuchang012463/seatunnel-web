import { Input, InputNumber, Segmented, Select, Switch, message } from 'antd';
import { useEffect, useState } from 'react';

type Props = {
  streaming?: boolean;
  config: Record<string, any>;
  onChange: (patch: Record<string, any>) => void;
};

const Field = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <div className="workflow-panel__field workflow-panel__field--full">
    <div className="workflow-panel__label">{label}</div>
    {children}
  </div>
);

function JsonField({
  value,
  placeholder,
  onChange,
}: {
  value: unknown;
  placeholder: string;
  onChange: (value: Record<string, any>) => void;
}) {
  const [text, setText] = useState('');

  useEffect(() => {
    setText(value && typeof value === 'object' ? JSON.stringify(value, null, 2) : '');
  }, [value]);

  return (
    <Input.TextArea
      value={text}
      rows={4}
      placeholder={placeholder}
      onChange={(event) => setText(event.target.value)}
      onBlur={() => {
        if (!text.trim()) {
          onChange({});
          return;
        }
        try {
          const parsed = JSON.parse(text);
          if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
            throw new Error('not an object');
          }
          onChange(parsed);
        } catch {
          message.error('请输入合法的 JSON 对象');
        }
      }}
    />
  );
}

export default function HttpNodeConfig({ streaming, config, onChange }: Props) {
  const method = String(config.method || 'GET').toUpperCase();
  const format = String(config.format || 'text').toLowerCase();
  const pageType = config.pageing?.page_type || 'NONE';
  const patchPageing = (patch: Record<string, any>) =>
    onChange({ pageing: { ...(config.pageing || {}), ...patch } });

  return (
    <div className="workflow-panel__form-grid">
      <Field label="相对路径">
        <Input
          value={config.path}
          placeholder="/v1/orders（不可填写完整 URL）"
          onChange={(event) => onChange({ path: event.target.value })}
        />
      </Field>
      <Field label="请求方法">
        <Segmented
          block
          value={method}
          options={['GET', 'POST']}
          onChange={(value) => onChange({ method: value })}
        />
      </Field>
      <Field label="Headers（JSON）">
        <JsonField
          value={config.headers}
          placeholder={'例如 {"Accept":"application/json"}；不可覆盖认证头'}
          onChange={(value) => onChange({ headers: value })}
        />
      </Field>
      <Field label="Query Params（JSON）">
        <JsonField
          value={config.params}
          placeholder={'例如 {"status":"active"}'}
          onChange={(value) => onChange({ params: value })}
        />
      </Field>
      {method === 'POST' && (
        <Field label="请求体">
          <Input.TextArea
            rows={4}
            value={config.body}
            placeholder="POST body，可使用 SeaTunnel 占位符"
            onChange={(event) => onChange({ body: event.target.value })}
          />
        </Field>
      )}
      <Field label="响应格式">
        <Segmented
          block
          value={format}
          options={['text', 'json']}
          onChange={(value) => onChange({ format: value })}
        />
      </Field>
      {format === 'json' && (
        <>
          <Field label="Schema（JSON）">
            <JsonField
              value={config.schema}
              placeholder='SeaTunnel schema，例如 {"fields":{"id":"bigint"}}'
              onChange={(value) => onChange({ schema: value })}
            />
          </Field>
          <Field label="内容字段">
            <Input
              value={config.contentField}
              placeholder="例如 $.data"
              onChange={(event) => onChange({ contentField: event.target.value })}
            />
          </Field>
          <Field label="JSON 字段映射（JSON）">
            <JsonField
              value={config.jsonField}
              placeholder={'例如 {"id":"$.orderId"}'}
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
      <Field label="分页方式">
        <Select
          value={pageType}
          style={{ width: '100%' }}
          options={[
            { label: '不分页', value: 'NONE' },
            { label: 'PageNumber', value: 'PageNumber' },
            { label: 'Cursor', value: 'Cursor' },
          ]}
          onChange={(value) =>
            onChange({ pageing: value === 'NONE' ? undefined : { page_type: value } })
          }
        />
      </Field>
      {pageType === 'PageNumber' && (
        <>
          <Field label="页码参数名">
            <Input
              value={config.pageing?.page_field}
              placeholder="page"
              onChange={(event) => patchPageing({ page_field: event.target.value })}
            />
          </Field>
          <Field label="起始页 / 每页数量">
            <div style={{ display: 'flex', gap: 8 }}>
              <InputNumber
                value={config.pageing?.page_start_from}
                placeholder="1"
                style={{ width: '50%' }}
                onChange={(value) => patchPageing({ page_start_from: value })}
              />
              <InputNumber
                value={config.pageing?.page_size}
                min={1}
                placeholder="100"
                style={{ width: '50%' }}
                onChange={(value) => patchPageing({ page_size: value })}
              />
            </div>
          </Field>
        </>
      )}
      {pageType === 'Cursor' && (
        <>
          <Field label="游标请求参数名">
            <Input
              value={config.pageing?.cursor_field}
              placeholder="cursor"
              onChange={(event) => patchPageing({ cursor_field: event.target.value })}
            />
          </Field>
          <Field label="响应游标字段">
            <Input
              value={config.pageing?.cursor_response_field}
              placeholder="$.nextCursor"
              onChange={(event) => patchPageing({ cursor_response_field: event.target.value })}
            />
          </Field>
        </>
      )}
      <Field label="重试次数">
        <InputNumber
          value={config.retry}
          min={0}
          style={{ width: '100%' }}
          onChange={(value) => onChange({ retry: value })}
        />
      </Field>
      <Field label="退避初始 / 最大毫秒">
        <div style={{ display: 'flex', gap: 8 }}>
          <InputNumber
            value={config.retryBackoffMultiplierMs}
            min={1}
            style={{ width: '50%' }}
            onChange={(value) => onChange({ retryBackoffMultiplierMs: value })}
          />
          <InputNumber
            value={config.retryBackoffMaxMs}
            min={1}
            style={{ width: '50%' }}
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
          checked={config.enableMultiLines ?? false}
          onChange={(checked) => onChange({ enableMultiLines: checked })}
        />
      </Field>
      <Field label="兼容请求参数模式">
        <div style={{ display: 'flex', gap: 20 }}>
          <Switch
            checked={config.keepParamsAsForm ?? false}
            checkedChildren="Form"
            unCheckedChildren="Form"
            onChange={(checked) => onChange({ keepParamsAsForm: checked })}
          />
          <Switch
            checked={config.keepPageParamAsHttpParam ?? false}
            checkedChildren="Page Param"
            unCheckedChildren="Page Param"
            onChange={(checked) => onChange({ keepPageParamAsHttpParam: checked })}
          />
        </div>
      </Field>
    </div>
  );
}
