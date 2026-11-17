import { dataSourceCatalogApi } from '@/pages/data-source/service';
import HttpUtils from '@/utils/HttpUtils';
import { Alert, Button, Form, Radio, Space, Tree, Typography } from 'antd';
import type { FormInstance, TreeDataNode } from 'antd';
import { useEffect, useRef, useState } from 'react';

type Namespace = { database: string; schema?: string; namespace: string };

/** Native JDBC identities match Doris: MySQL database, PostgreSQL/Oracle schema. */
export default function CatalogScopePicker({ form, sourceId, adapter }: { form: FormInstance; sourceId?: number; adapter?: string }) {
  const [nodes, setNodes] = useState<TreeDataNode[]>([]);
  const [namespaces, setNamespaces] = useState<Namespace[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const epoch = useRef(0);
  const scope = Form.useWatch('scope', { form, preserve: true }) || 'TABLE';
  const databases: string[] = Form.useWatch('databaseInclude', { form, preserve: true }) || [];
  const tables: string[] = Form.useWatch('tableInclude', { form, preserve: true }) || [];
  useEffect(() => {
    const request = ++epoch.current;
    setNodes([]);
    setNamespaces([]);
    setError('');
    if (!sourceId || !adapter || scope === 'ALL') { setLoading(false); return; }
    setLoading(true);
    void HttpUtils.get<Namespace[]>(`/api/v1/lake/logical/datasources/${sourceId}/namespaces`).then((result) => {
      if (request !== epoch.current) return;
      if (result.code !== 0) throw new Error(result.message || '库 / Schema 读取失败');
      setNamespaces(result.data || []);
      setNodes((result.data || []).map((item) => ({ key: `ns:${item.namespace}`, title: item.schema ? `${item.database} / ${item.schema}` : item.database,
        isLeaf: scope === 'DATABASE', disableCheckbox: scope === 'TABLE' })));
    }).catch((e) => { if (request === epoch.current) setError(e.message || '源端结构读取失败'); })
      .finally(() => { if (request === epoch.current) setLoading(false); });
    return () => { epoch.current += 1; };
  }, [sourceId, adapter, scope, revision]);

  const loadTables = async (node: any) => {
    if (node.children || scope !== 'TABLE') return;
    const namespace = namespaces.find((item) => `ns:${item.namespace}` === node.key);
    if (!namespace || !sourceId) return;
    const request = epoch.current;
    try {
      const result = await dataSourceCatalogApi.listTablesByDatabase(String(sourceId), namespace.database, namespace.schema);
      if (request !== epoch.current) return;
      if (result.code !== 0) throw new Error(result.message || '表读取失败');
      const children = (result.data || []).map((item) => {
        const name = String(item.value);
        return { key: `table:${namespace.namespace}.${name}`, title: name, isLeaf: true };
      });
      setNodes((current) => current.map((item) => item.key === node.key ? { ...item, children, isLeaf: !children.length } : item));
      setError('');
    } catch (e: any) { setError(e.message || '表读取失败'); throw e; }
  };
  const selected = scope === 'TABLE'
    ? tables.map((table) => `table:${table.includes('.') ? table : `${databases[0]}.${table}`}`)
    : databases.map((database) => `ns:${database}`);
  return <Space direction="vertical" style={{ width: '100%' }} size="middle">
    <Form.Item name="scope" label="挂载范围" style={{ marginBottom: 0 }}>
      <Radio.Group options={[{ value: 'TABLE', label: '指定表' }, { value: 'DATABASE', label: adapter === 'MYSQL' ? '整库' : '整个 Schema' }, { value: 'ALL', label: '全部' }]}
        onChange={() => form.setFieldsValue({ databaseInclude: [], tableInclude: [] })} /></Form.Item>
    <Form.Item name="databaseInclude" hidden rules={[{ validator: async (_, value) => { if (scope !== 'ALL' && !value?.length) throw new Error('请勾选要挂载的库、Schema 或表'); } }]}><StoreValue /></Form.Item>
    <Form.Item name="tableInclude" hidden rules={[{ validator: async (_, value) => { if (scope === 'TABLE' && !value?.length) throw new Error('请至少勾选一张表'); } }]}><StoreValue /></Form.Item>
    {scope === 'ALL' ? <Alert type="info" message="挂载此连接可访问的全部库表，后续新增对象也会包含在内。" /> : <>
      <Typography.Text type="secondary">{scope === 'TABLE' ? '展开库 / Schema，勾选需要的表。' : '所选范围包含现有和后续新增的表。'}{adapter === 'POSTGRESQL' ? ' PostgreSQL 仅挂载当前连接数据库中的 Schema。' : ''}</Typography.Text>
      {error && <Alert type="error" message={error} action={<Button onClick={() => setRevision((value) => value + 1)}>重新读取</Button>} />}
      <div aria-label="源端库表选择" style={{ minHeight: 240, maxHeight: 360, overflow: 'auto', border: '1px solid var(--ant-color-border-secondary)', padding: 12, borderRadius: 8 }}>
        {loading ? '正在读取源端结构…' : !nodes.length ? '暂无可选库 / Schema，请检查数据源连接及读取权限。' : <Tree checkable checkStrictly blockNode selectable={false} treeData={nodes} loadData={loadTables} checkedKeys={selected}
          onCheck={(keys) => {
            const checked = Array.isArray(keys) ? keys : keys.checked;
            if (scope === 'TABLE') {
              const included = checked.filter((key) => String(key).startsWith('table:')).map((key) => String(key).slice(6));
              form.setFieldsValue({ tableInclude: included, databaseInclude: [...new Set(included.map((name) => name.slice(0, name.indexOf('.'))))] });
            } else form.setFieldsValue({ databaseInclude: checked.map((key) => String(key).slice(3)), tableInclude: [] });
          }} />}
      </div>
      <Space><Typography.Text>已选 {scope === 'TABLE' ? `${tables.length} 张表` : `${databases.length} 个库 / Schema`}</Typography.Text><Button type="link" onClick={() => form.setFieldsValue({ databaseInclude: [], tableInclude: [] })}>清空选择</Button></Space>
    </>}
  </Space>;
}

// A registered value holder keeps both arrays available to normal form submission.
const StoreValue = (_props: { value?: string[]; onChange?: (value: string[]) => void }) => null;
