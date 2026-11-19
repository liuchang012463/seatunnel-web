import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { ApartmentOutlined, AppstoreOutlined, CaretDownOutlined, CaretUpOutlined, DatabaseOutlined, LinkOutlined, TableOutlined } from '@ant-design/icons';
import { useEffect } from 'react';
import { Handle, type NodeProps, Position, useUpdateNodeInternals } from 'reactflow';
import type { Group } from './graph';
import type { LineageTask } from './service';

export interface FieldPort {
  fqn: string;
  incoming?: boolean;
  outgoing?: boolean;
}

export interface AssetNodeData {
  group: Group;
  parents: Group[];
  assetCount: number;
  internalCount: number;
  fields: FieldPort[];
  fieldState?: 'loading' | 'error' | 'open';
  onExpand: (group: Group) => void;
  onCollapse: (id: string) => void;
  onFields: (group: Group) => void;
}

export interface TaskNodeData {
  task?: LineageTask | null;
  checking: boolean;
  onOpen: (task: LineageTask) => void;
}

export const groupLabels = { source: '数据源', database: '库', schema: 'Schema', table: '表', asset: '资产' };

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

export function TopologyAssetNode({ id, data }: NodeProps<AssetNodeData>) {
  const { group, parents, fields, fieldState } = data;
  const updateNodeInternals = useUpdateNodeInternals();
  useEffect(() => { updateNodeInternals(id); }, [id, fields, updateNodeInternals]);
  const parent = parents.at(-1);
  const path = [...parents.map(({ name }) => name), group.name].join(' / ');
  const expandedFields = Boolean(fieldState || fields.length);
  const icon = group.kind === 'source' ? <DatabaseIcons dbType={group.source.dbType ?? undefined} />
    : group.kind === 'database' ? <DatabaseOutlined />
      : group.kind === 'schema' ? <AppstoreOutlined /> : <TableOutlined />;
  return (
    <div className="data-topology-asset nopan" data-kind={group.kind}>
      <MainHandles />
      <div className="data-topology-node-heading">
        {icon}
        <div className="data-topology-node-label">
          {parents.length > 0 && (
            <span className="data-topology-node-parent" title={path}>{parents.map(({ name }) => name).join(' / ')}</span>
          )}
          <strong title={path}>{group.name}</strong>
        </div>
        {parent && <button
          type="button" className="data-topology-icon nodrag nopan"
          aria-label={`收起 ${parent.name}`} title={`收起 ${groupLabels[parent.kind]}`}
          onClick={() => data.onCollapse(parent.id)}
        ><CaretUpOutlined /></button>}
        {group.asset ? (
          <button
            type="button" className="data-topology-icon data-topology-fields-toggle nodrag nopan"
            aria-pressed={fieldState !== undefined}
            aria-label={`${fieldState ? '收起' : '展开'}字段 ${group.name}`}
            onClick={() => data.onFields(group)}
          >字段</button>
        ) : <button
          type="button" className="data-topology-icon nodrag nopan"
          aria-label={`展开 ${group.name}`} title={`展开 ${groupLabels[group.kind]}`}
          onClick={() => data.onExpand(group)}
        ><CaretDownOutlined /></button>}
      </div>
      {!group.asset && (
        <div className="data-topology-node-count">
          {data.assetCount} 个资产{data.internalCount > 0 && ` · 内部血缘 ${data.internalCount} 条`}
        </div>
      )}
      {expandedFields && (
        <div className="data-topology-field-list">
          {fields.map(({ fqn, incoming, outgoing }) => {
            const name = fqn.slice((group.asset?.fullyQualifiedName.length ?? 0) + 1);
            return (
              <div key={fqn} className="data-topology-field" title={fqn}>
                {incoming && <Handle type="target" position={Position.Left} id={fieldHandle(fqn, 'to')} />}
                <span>{name.startsWith('"') && name.endsWith('"') ? name.slice(1, -1) : name}</span>
                {outgoing && <Handle type="source" position={Position.Right} id={fieldHandle(fqn, 'from')} />}
              </div>
            );
          })}
          {fieldState === 'loading' && <div className="data-topology-field-status" role="status">正在读取字段映射</div>}
          {fieldState === 'error' && <div className="data-topology-field-status" role="alert">读取字段映射失败</div>}
          {fieldState === 'open' && fields.length === 0 && <div className="data-topology-field-status">暂无字段映射</div>}
        </div>
      )}
    </div>
  );
}

export function TopologyTaskNode({ data }: NodeProps<TaskNodeData>) {
  const { task, checking, onOpen } = data;
  const label = task?.name || (task ? `任务 ${task.id}` : '未关联任务');
  const kind = task?.kind === 'STREAMING' ? '实时任务' : '批量任务';
  return (
    <div className={`data-topology-task nopan${!task || task.deleted ? ' data-topology-task--unavailable' : ''}`}>
      <MainHandles />
      <div className="data-topology-task-heading">
        <ApartmentOutlined />
        {task && !task.deleted ? (
          <button
            type="button" className="data-topology-task-link nodrag nopan"
            aria-label={`查看任务定义 ${label}`} title={label} disabled={checking}
            onClick={() => onOpen(task)}
          ><span>{label}</span><LinkOutlined /></button>
        ) : <strong title={label}>{label}</strong>}
      </div>
      {task && <div className="data-topology-node-count">
        {kind}{task.deleted ? ' · 已删除' : checking ? ' · 正在检查' : ''}
      </div>}
    </div>
  );
}
