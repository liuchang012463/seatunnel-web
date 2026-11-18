import { Descriptions, Empty, Table } from 'antd';
import type { Node } from 'reactflow';
import PanelShell from './components/PanelShell';

type Mapping = { sourceField?: string; targetField?: string; targetType?: string };

// Use the saved node directly. The editing panels can refresh and replace configured fields.
export default function SavedNodePanel({ node, onClose }: { node: Node | null; onClose: () => void }) {
  if (!node?.data) return null;
  const config = node.data.config || {};
  const mappings: Mapping[] = Array.isArray(config.mappings) ? config.mappings : [];
  const sink = node.data.nodeType === 'sink';
  const transform = node.data.nodeType === 'transform';
  const title = node.data.title || node.data.label || (sink ? '目标' : '来源');
  const values = [
    ['数据源 ID', config.dataSourceId],
    ['数据库', config.database],
    ['Schema', config.schemaName],
    [sink ? '目标表' : '来源表', config.targetTableName || config.table],
    ['写入方式', config.writeMode === 'overwrite' ? '全量覆盖' : config.writeMode === 'append' ? '追加' : config.writeMode],
    ['SQL', config.sql],
  ].filter(([, value]) => (typeof value === 'string' && value.length > 0) || typeof value === 'number');

  return (
    <PanelShell title={title} heroTitle={title} dbType={node.data.dbType} onClose={onClose}>
      {mappings.length ? (
        <Table<Mapping>
          rowKey={(row) => JSON.stringify([row.sourceField, row.targetField])}
          dataSource={mappings}
          pagination={false}
          size="small"
          columns={[
            { title: '来源字段', dataIndex: 'sourceField' },
            { title: '目标字段', dataIndex: 'targetField' },
            { title: '目标类型', dataIndex: 'targetType' },
          ]}
        />
      ) : values.length ? (
        <Descriptions bordered size="small" column={1} items={values.map(([label, value]) => ({
          key: label,
          label,
          children: <span style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{value}</span>,
        }))} />
      ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={transform ? '此节点无已保存的字段映射或 SQL' : '此节点无已保存的表配置'} />}
    </PanelShell>
  );
}
