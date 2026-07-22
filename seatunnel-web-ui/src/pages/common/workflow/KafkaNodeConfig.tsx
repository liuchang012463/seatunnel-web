import { Input, InputNumber, Segmented, Select, Switch, message } from 'antd';
import { useEffect, useState } from 'react';

type Props = {
  role: 'source' | 'sink';
  config: Record<string, any>;
  topicOptions?: any[];
  topicLoading?: boolean;
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

export default function KafkaNodeConfig({
  role,
  config,
  topicOptions = [],
  topicLoading,
  onChange,
}: Props) {
  const source = role === 'source';
  const subscription = config.pattern ? 'pattern' : 'topic';
  const startMode = config.startMode || 'group_offsets';
  const format = config.format || 'json';
  const semantics = config.semantics || 'NON';

  const topicSelect = (
    <Select
      mode="tags"
      maxCount={1}
      value={config.topic ? [config.topic] : []}
      options={topicOptions}
      loading={topicLoading}
      showSearch
      allowClear
      optionFilterProp="rawLabel"
      placeholder={source ? '选择或输入 Topic；多个 Topic 用逗号分隔' : '选择或输入固定 / ${field} 动态 Topic'}
      style={{ width: '100%' }}
      onChange={(values) => onChange({ topic: values.at(-1) || undefined })}
    />
  );

  return (
    <div className="workflow-panel__form-grid">
      {source ? (
        <>
          <Field label="订阅方式">
            <Segmented
              block
              value={subscription}
              options={[{ label: 'Topic', value: 'topic' }, { label: '正则', value: 'pattern' }]}
              onChange={(value) => onChange(value === 'topic'
                ? { pattern: undefined }
                : { topic: undefined })}
            />
          </Field>
          {subscription === 'topic' ? (
            <Field label="Topic">{topicSelect}</Field>
          ) : (
            <Field label="Topic 正则">
              <Input
                value={config.pattern}
                placeholder="例如 orders-.*"
                onChange={(event) => onChange({ pattern: event.target.value })}
              />
            </Field>
          )}
          <Field label="消费组">
            <Input
              value={config.consumerGroup}
              placeholder="consumer.group"
              onChange={(event) => onChange({ consumerGroup: event.target.value })}
            />
          </Field>
          <Field label="消费起点">
            <Select
              value={startMode}
              style={{ width: '100%' }}
              options={['earliest', 'group_offsets', 'latest', 'specific_offsets', 'timestamp']
                .map((value) => ({ label: value, value }))}
              onChange={(value) => onChange({ startMode: value })}
            />
          </Field>
          {startMode === 'specific_offsets' && (
            <Field label="指定分区位点">
              <JsonField
                value={config.startModeOffsets}
                placeholder={'例如 {"0": 100, "1": 200}'}
                onChange={(value) => onChange({ startModeOffsets: value })}
              />
            </Field>
          )}
          {startMode === 'timestamp' && (
            <Field label="开始时间戳（毫秒）">
              <InputNumber
                value={config.startModeTimestamp}
                min={0}
                style={{ width: '100%' }}
                onChange={(value) => onChange({ startModeTimestamp: value })}
              />
            </Field>
          )}
          <Field label="结束时间戳（可选，毫秒）">
            <InputNumber
              value={config.startModeEndTimestamp}
              min={0}
              style={{ width: '100%' }}
              onChange={(value) => onChange({ startModeEndTimestamp: value })}
            />
          </Field>
          <Field label="Checkpoint 提交 Offset">
            <Switch
              checked={config.commitOnCheckpoint ?? true}
              onChange={(checked) => onChange({ commitOnCheckpoint: checked })}
            />
          </Field>
          <Field label="Poll Timeout（毫秒）">
            <InputNumber
              value={config.pollTimeout}
              min={1}
              style={{ width: '100%' }}
              onChange={(value) => onChange({ pollTimeout: value })}
            />
          </Field>
        </>
      ) : (
        <>
          <Field label="Topic">{topicSelect}</Field>
          <Field label="交付语义">
            <Select
              value={semantics}
              style={{ width: '100%' }}
              options={['NON', 'AT_LEAST_ONCE', 'EXACTLY_ONCE'].map((value) => ({ label: value, value }))}
              onChange={(value) => onChange({ semantics: value })}
            />
          </Field>
          {semantics === 'EXACTLY_ONCE' && (
            <Field label="Transaction Prefix（任务唯一）">
              <Input
                value={config.transactionPrefix}
                onChange={(event) => onChange({ transactionPrefix: event.target.value })}
              />
            </Field>
          )}
          <Field label="固定分区">
            <InputNumber
              value={config.partition}
              min={0}
              disabled={Array.isArray(config.partitionKeyFields) && config.partitionKeyFields.length > 0}
              style={{ width: '100%' }}
              onChange={(value) => onChange({ partition: value })}
            />
          </Field>
          <Field label="分区键字段">
            <Select
              mode="tags"
              value={config.partitionKeyFields || []}
              disabled={config.partition !== undefined && config.partition !== null}
              placeholder="输入字段名后回车"
              style={{ width: '100%' }}
              onChange={(value) => onChange({ partitionKeyFields: value })}
            />
          </Field>
        </>
      )}

      <Field label="消息格式">
        <Select
          value={format}
          style={{ width: '100%' }}
          options={['json', 'text', 'csv', 'avro', 'protobuf'].map((value) => ({ label: value, value }))}
          onChange={(value) => onChange({ format: value })}
        />
      </Field>
      {source && (
        <Field label="Schema（JSON）">
          <JsonField
            value={config.schema}
            placeholder="SeaTunnel Kafka schema JSON"
            onChange={(value) => onChange({ schema: value })}
          />
        </Field>
      )}
      {source && format === 'text' && (
        <Field label="字段分隔符">
          <Input
            value={config.fieldDelimiter}
            onChange={(event) => onChange({ fieldDelimiter: event.target.value })}
          />
        </Field>
      )}
      <Field label="任务级 kafka.config（JSON）">
        <JsonField
          value={config.kafkaConfig}
          placeholder={'例如 {"compression.type": "lz4"}'}
          onChange={(value) => onChange({ kafkaConfig: value })}
        />
      </Field>
    </div>
  );
}
