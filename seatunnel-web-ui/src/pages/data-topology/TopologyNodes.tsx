import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { ApartmentOutlined, TableOutlined } from '@ant-design/icons';
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
}

export const taskKindLabel = { STREAMING: '实时任务', BATCH: '批量任务' };

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
  const { asset, dbType, focused, fields } = data;
  const updateNodeInternals = useUpdateNodeInternals();
  useEffect(() => { updateNodeInternals(id); }, [id, fields, updateNodeInternals]);
  return (
    <div className={`data-topology-asset nopan${focused ? ' is-focus' : ''}`}>
      <MainHandles />
      <div className="data-topology-node-heading">
        {dbType ? <DatabaseIcons dbType={dbType} /> : <TableOutlined />}
        <div className="data-topology-node-label">
          <strong title={asset.name}>{asset.name}</strong>
          <small className="data-topology-node-fqn" title={asset.fullyQualifiedName}>{asset.fullyQualifiedName}</small>
        </div>
      </div>
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
    <div className={`data-topology-task nopan${task.deleted ? ' is-deleted' : ''}`}>
      <MainHandles />
      <div className="data-topology-node-heading">
        <ApartmentOutlined />
        <div className="data-topology-node-label">
          <strong title={name}>{name}</strong>
          <small>{taskKindLabel[task.kind]}{task.deleted && ' · 已删除'}</small>
        </div>
      </div>
    </div>
  );
});
