import { Input, InputNumber, Select, message } from 'antd';
import { useEffect, useState } from 'react';

type Props = {
  role: 'source' | 'sink';
  config: Record<string, any>;
  indexOptions?: any[];
  indexLoading?: boolean;
  onChange: (patch: Record<string, any>) => void;
};

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
      onChange={(event) => {
        const next = event.target.value;
        setText(next);
        if (!next.trim()) {
          onChange({});
        }
      }}
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

const Field = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <div className="workflow-panel__field workflow-panel__field--full">
    <div className="workflow-panel__label">{label}</div>
    {children}
  </div>
);

function normalizeIndexValues(config: Record<string, any>, sink: boolean) {
  const rawList = config.index_list;
  if (Array.isArray(rawList)) {
    return rawList
      .map((item) => (item && typeof item === 'object' ? item.index : item))
      .filter(Boolean)
      .map(String);
  }

  const value = sink ? config.targetTableName || config.index : config.index;
  return value
    ? String(value)
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean)
    : [];
}

function normalizeList(value: unknown) {
  if (Array.isArray(value)) return value.map(String).filter(Boolean);
  if (typeof value === 'string') {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  }
  return [];
}

export default function ElasticsearchNodeConfig({
  role,
  config,
  indexOptions = [],
  indexLoading,
  onChange,
}: Props) {
  const source = role === 'source';
  const indexes = normalizeIndexValues(config, !source);
  const sourceFields = normalizeList(config.source);
  const primaryKeys = normalizeList(config.primaryKeys || config.primary_keys);
  const searchType = String(config.searchType || config.search_type || 'DSL').toUpperCase();
  const searchApiType = String(config.searchApiType || config.search_api_type || 'SCROLL').toUpperCase();

  const updateIndexes = (values: string[]) => {
    const next = values.filter(Boolean);
    if (source) {
      onChange(
        next.length <= 1
          ? { index: next[0], index_list: undefined }
          : { index: undefined, index_list: next.map((index) => ({ index })) },
      );
      return;
    }
    onChange({ index: next.at(-1) || undefined, targetTableName: next.at(-1) || '' });
  };

  return (
    <div className="workflow-panel__form-grid">
      <Field label={source ? '来源索引' : '目标索引'}>
        <Select
          mode="tags"
          maxCount={source ? undefined : 1}
          value={indexes}
          options={indexOptions}
          loading={indexLoading}
          showSearch
          allowClear
          optionFilterProp="rawLabel"
          placeholder={source ? '选择或输入索引、通配符；多个索引用逗号分隔' : '选择或输入目标索引'}
          style={{ width: '100%' }}
          onChange={updateIndexes}
        />
      </Field>

      {source ? (
        <>
          <Field label="读取字段（可选）">
            <Select
              mode="tags"
              value={sourceFields}
              placeholder="留空读取完整文档；输入字段名后回车"
              style={{ width: '100%' }}
              onChange={(value) => onChange({ source: value })}
            />
          </Field>
          <Field label="查询类型">
            <Select
              value={searchType}
              style={{ width: '100%' }}
              options={['DSL', 'SQL'].map((value) => ({ label: value, value }))}
              onChange={(value) => onChange({ searchType: value })}
            />
          </Field>
          {searchType === 'SQL' ? (
            <Field label="Elasticsearch SQL">
              <Input.TextArea
                rows={4}
                value={config.sqlQuery || ''}
                placeholder="例如 SELECT * FROM orders"
                onChange={(event) => onChange({ sqlQuery: event.target.value })}
              />
            </Field>
          ) : (
            <Field label="DSL 查询（JSON）">
              <JsonField
                value={config.query}
                placeholder={'例如 {"term":{"status":"READY"}}'}
                onChange={(value) => onChange({ query: value })}
              />
            </Field>
          )}
          <Field label="分页 API">
            <Select
              value={searchApiType}
              style={{ width: '100%' }}
              options={['SCROLL', 'PIT'].map((value) => ({ label: value, value }))}
              onChange={(value) => onChange({ searchApiType: value })}
            />
          </Field>
          {searchApiType === 'SCROLL' ? (
            <>
              <Field label="Scroll 保留时间">
                <Input
                  value={config.scrollTime || '1m'}
                  placeholder="1m"
                  onChange={(event) => onChange({ scrollTime: event.target.value })}
                />
              </Field>
              <Field label="Scroll 批大小">
                <InputNumber
                  min={1}
                  value={config.scrollSize || 100}
                  style={{ width: '100%' }}
                  onChange={(value) => onChange({ scrollSize: value })}
                />
              </Field>
            </>
          ) : (
            <>
              <Field label="PIT 保留时间">
                <Input
                  value={config.pitKeepAlive || '1m'}
                  placeholder="1m"
                  onChange={(event) => onChange({ pitKeepAlive: event.target.value })}
                />
              </Field>
              <Field label="PIT 批大小">
                <InputNumber
                  min={1}
                  value={config.pitBatchSize || 100}
                  style={{ width: '100%' }}
                  onChange={(value) => onChange({ pitBatchSize: value })}
                />
              </Field>
            </>
          )}
        </>
      ) : (
        <>
          <Field label="索引类型（可选）">
            <Input
              value={config.indexType || config.index_type || ''}
              placeholder="例如 CUSTOM"
              onChange={(event) => onChange({ indexType: event.target.value })}
            />
          </Field>
          <Field label="Schema 保存策略">
            <Select
              value={config.schemaSaveMode || config.schema_save_mode || 'CREATE_SCHEMA_WHEN_NOT_EXIST'}
              style={{ width: '100%' }}
              options={[
                'RECREATE_SCHEMA',
                'CREATE_SCHEMA_WHEN_NOT_EXIST',
                'ERROR_WHEN_SCHEMA_NOT_EXIST',
                'IGNORE',
              ].map((value) => ({ label: value, value }))}
              onChange={(value) => onChange({ schemaSaveMode: value })}
            />
          </Field>
          <Field label="数据保存策略">
            <Select
              value={config.dataSaveMode || config.data_save_mode || 'APPEND_DATA'}
              style={{ width: '100%' }}
              options={['DROP_DATA', 'APPEND_DATA', 'ERROR_WHEN_DATA_EXISTS'].map((value) => ({ label: value, value }))}
              onChange={(value) => onChange({ dataSaveMode: value })}
            />
          </Field>
          <Field label="主键字段（可选）">
            <Select
              mode="tags"
              value={primaryKeys}
              placeholder="输入字段名后回车"
              style={{ width: '100%' }}
              onChange={(value) => onChange({ primaryKeys: value })}
            />
          </Field>
          <Field label="主键分隔符">
            <Input
              value={config.keyDelimiter || config.key_delimiter || '_'}
              onChange={(event) => onChange({ keyDelimiter: event.target.value })}
            />
          </Field>
        </>
      )}
    </div>
  );
}
