import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import type { DataExplorationTable } from '@/pages/data-source/types';
import { ApartmentOutlined, EyeInvisibleOutlined, EyeOutlined, TableOutlined } from '@ant-design/icons';
import { memo, useEffect } from 'react';
import { Handle, type NodeProps, Position, useUpdateNodeInternals } from 'reactflow';
import type { FieldRow } from './graph';
import type { LineageAsset, LineageTask } from './service';

export interface FieldView {
  rows: FieldRow[];
  loading: boolean;
  failed?: boolean;
}

export interface AssetNodeData {
  asset: LineageAsset;
  dbType?: string | null;
  focused: boolean;
  fields?: FieldView;
  summary?: DataExplorationTable;
  onFields: (id: string) => void;
}

export const taskKindLabel = { STREAMING: '实时任务', BATCH: '批量任务' };
export const tableNodeSize = { width: 236, height: 104 };
export const taskNodeSize = { width: 100, height: 28 };

export function formatTableCount(value?: string | number | null) {
  if (value == null) return '—';
  return typeof value === 'string' && /^\d+$/.test(value)
    ? BigInt(value).toLocaleString('zh-CN') : Number(value).toLocaleString('zh-CN');
}

export function fieldHandle(fqn: string, direction: 'from' | 'to') {
  return `${direction}:${encodeURIComponent(fqn)}`;
}

function MainHandles() {
  return (
    <>
      <Handle type="target" position={Position.Left} id="in" />
      <Handle type="source" position={Position.Right} id="out" />
    </>
  );
}

export const TopologyAssetNode = memo(function TopologyAssetNode({ id, data }: NodeProps<AssetNodeData>) {
  const { asset, dbType, focused, fields, summary } = data;
  const table = asset.type === 'table';
  const fieldLabel = `${fields ? '收起' : '展开'} ${asset.name} 字段血缘`;
  const updateNodeInternals = useUpdateNodeInternals();
  useEffect(() => { updateNodeInternals(id); }, [id, fields, updateNodeInternals]);
  return (
    <div className={`data-topology-asset nopan${focused ? ' is-focus' : ''}`}>
      <MainHandles />
      <div className="data-topology-node-heading">
        {dbType ? <DatabaseIcons dbType={dbType} /> : <TableOutlined />}
        <div className="data-topology-node-label">
          <strong title={asset.fullyQualifiedName}>{asset.name}</strong>
        </div>
      </div>
      {table && <p className="data-topology-node-description" title={summary?.description}>
        {summary?.description || '暂无表注释'}
      </p>}
      <div className="data-topology-node-types" title={asset.fullyQualifiedName}>
        <span>{[dbType, summary?.tableType ?? (table ? '表' : asset.type)].filter(Boolean).join(' · ')}</span>
        <small>{asset.fullyQualifiedName}</small>
      </div>
      {table && <div className="data-topology-node-metrics">
        <span>{summary?.rowCount == null ? '行数未统计' : `${formatTableCount(summary.rowCount)} 行`}</span>
        <span>{formatTableCount(summary?.columnCount)} 字段</span>
        <button type="button" className="data-topology-field-toggle nodrag nopan" aria-label={fieldLabel}
          title={fieldLabel} aria-pressed={Boolean(fields)}
          onClick={(event) => {
            event.stopPropagation();
            data.onFields(asset.id);
          }}>
          {fields ? <EyeInvisibleOutlined /> : <EyeOutlined />}
        </button>
      </div>}
      {fields && (
        <div className="data-topology-field-list" aria-label={`${asset.name}字段`}>
          {fields.rows.map(({ fqn, name, type, incoming, outgoing }) => (
            <div key={fqn} className="data-topology-field">
              {incoming && <Handle type="target" position={Position.Left} id={fieldHandle(fqn, 'to')} />}
              <span title={name}>{name}</span>
              {type && <small title={type}>{type}</small>}
              {outgoing && <Handle type="source" position={Position.Right} id={fieldHandle(fqn, 'from')} />}
            </div>
          ))}
          {fields.loading && <div className="data-topology-field-status" role="status">正在读取字段</div>}
          {fields.failed && <div className="data-topology-field-status">字段元数据暂不可用</div>}
          {!fields.loading && !fields.failed && fields.rows.length === 0 &&
            <div className="data-topology-field-status">暂无字段</div>}
        </div>
      )}
    </div>
  );
});

export const TopologyTaskNode = memo(function TopologyTaskNode({ data: task }: NodeProps<LineageTask>) {
  const name = task.name || `任务 ${task.id}`;
  return (
    <div className={`data-topology-task nopan${task.deleted ? ' is-deleted' : ''}`} title={name}>
      <MainHandles />
      <div className="data-topology-node-heading">
        <ApartmentOutlined />
        <strong>{taskKindLabel[task.kind]}</strong>
      </div>
    </div>
  );
});
