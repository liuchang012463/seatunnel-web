import { fetchTimeVariableList } from '@/pages/knowledge-management/api';
import { Alert, Button, Collapse, Select, Space, Switch } from 'antd';
import React, { useEffect, useId, useState } from 'react';

type Validity = { enabled?: boolean; fieldName?: string; timeVariable?: string };

/** 只保存可选筛选配置；执行时复用现有时间变量，不改变普通任务。 */
export default function SourceValidityFields({ value = {}, fields = [], onChange, onLoadFields }: {
  value?: Validity;
  fields?: any[];
  onChange: (value: Validity) => void;
  onLoadFields: () => void;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [variables, setVariables] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const result = await fetchTimeVariableList();
      if (result.code !== 0) throw new Error(result.message || '时间变量读取失败');
      setVariables(result.data || []);
    } catch (e: any) {
      setError(e.message || '时间变量读取失败');
    } finally { setLoading(false); }
  };
  useEffect(() => { if (open) void load(); }, [open]);
  const options = fields.filter((column) => /date|time/i.test(String(column.type || column.fieldType || column.dataType || '')))
    .map((column) => ({ value: column.originFieldName || column.fieldName || column.name,
      label: column.originFieldName || column.fieldName || column.name }));
  return <Collapse activeKey={open ? ['time'] : []} onChange={(keys) => setOpen(keys.includes('time'))}
    items={[{ key: 'time', label: `按时间筛选${value.enabled ? ' · 已启用' : '（可选）'}`, children: <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space><Switch id={`${id}-enabled`} checked={!!value.enabled} onChange={(enabled) => onChange({ ...value, enabled })} />
        <label htmlFor={`${id}-enabled`}>只采集指定时间之后的数据</label></Space>
      {value.enabled && <>
        <div><label htmlFor={`${id}-field`}>时间字段</label>
          <Select id={`${id}-field`} value={value.fieldName} options={options} style={{ width: '100%', marginTop: 8 }}
            showSearch placeholder="选择时间字段" onChange={(fieldName) => onChange({ ...value, fieldName })} />
          {!options.length && <Button type="link" onClick={onLoadFields}>解析来源字段</Button>}</div>
        <div><label htmlFor={`${id}-variable`}>起始时间变量</label>
          <Select id={`${id}-variable`} loading={loading} value={value.timeVariable} style={{ width: '100%', marginTop: 8 }}
            options={variables.map((item) => ({ value: item.label, label: item.label }))}
            showSearch optionFilterProp="label" placeholder="选择已有时间变量" onChange={(timeVariable) => onChange({ ...value, timeVariable })} /></div>
        {error && <Alert type="error" message={error} action={<Button onClick={load}>重试</Button>} />}
        {!loading && !error && !variables.length && <Alert type="info" message="暂无启用的时间变量，请先在知识管理的时间变量中配置。" />}
        <Alert type="info" message={value.fieldName && value.timeVariable
          ? <code>{`${value.fieldName} >= \u0024{var:${value.timeVariable}}`}</code> : '选择字段和变量后，系统自动合并 WHERE 条件。'}
          description="每次执行重新计算时间变量。任务配置预览包含此条件；来源样例预览仍展示原始样例。" />
      </>}
    </Space> }]} />;
}
