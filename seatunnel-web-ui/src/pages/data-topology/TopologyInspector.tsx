import { readTaskDefinition } from '@/pages/common/workflow/TaskDefinitionView';
import { ApartmentOutlined, LinkOutlined, TableOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Spin, Tag } from 'antd';
import { useMemo } from 'react';
import { fetchTableMetadata, type LineageAsset, type LineageLink, type LineageTask } from './service';
import { taskKindLabel } from './TopologyNodes';
import useLineageRead from './useLineageRead';

function TableInspector({ asset, expanded, onFields }: {
  asset: LineageAsset;
  expanded: boolean;
  onFields: (id: string) => void;
}) {
  const load = useMemo(() => asset.type === 'table' ? () => fetchTableMetadata(asset) : undefined, [asset]);
  const { data, loading, failed } = useLineageRead(load);
  return (
    <>
      <div className="data-topology-inspector-title">
        <TableOutlined />
        <h3 title={asset.name}>{data?.displayName || asset.name}</h3>
      </div>
      <Descriptions size="small" column={1} items={[
        { key: 'fqn', label: 'FQN', children: <code>{asset.fullyQualifiedName}</code> },
        ...(asset.database ? [{ key: 'database', label: '数据库', children: asset.database }] : []),
        ...(asset.schema ? [{ key: 'schema', label: 'Schema', children: asset.schema }] : []),
        ...(data ? [
          { key: 'type', label: '表类型', children: data.tableType || '—' },
          { key: 'fields', label: '字段数', children: data.columns.length },
        ] : []),
      ]} />
      {data?.description && <p className="data-topology-description">{data.description}</p>}
      {loading && <Spin size="small" aria-label="正在读取表信息" />}
      {failed && <Alert showIcon type="warning" message="表信息暂不可用" />}
      {asset.type === 'table' && <Button block type={expanded ? 'default' : 'primary'}
        aria-pressed={expanded} onClick={() => onFields(asset.id)}>
        {expanded ? '收起字段血缘' : '查看字段血缘'}
      </Button>}
    </>
  );
}

function TaskInspector({ task, source, target, onOpenTask }: {
  task: LineageTask;
  source?: LineageAsset;
  target?: LineageAsset;
  onOpenTask: (task: LineageTask) => void;
}) {
  const load = useMemo(() => task.deleted ? undefined : () => readTaskDefinition(task.kind, task.id), [task]);
  const { data, loading, failed } = useLineageRead(load);
  const deleted = task.deleted || data === null;
  const name = data?.jobName || task.name || `任务 ${task.id}`;
  const releaseState = data?.releaseState === 'ONLINE' ? '已上线'
    : data?.releaseState === 'OFFLINE' ? '已下线' : data?.releaseState;
  return (
    <>
      <div className="data-topology-inspector-title">
        <ApartmentOutlined />
        <h3 title={name}>{name}</h3>
      </div>
      <div className="data-topology-task-tags">
        <Tag>{taskKindLabel[task.kind]}</Tag>
        {releaseState && <Tag>{releaseState}</Tag>}
      </div>
      <Descriptions size="small" column={1} items={[
        { key: 'id', label: '任务定义 ID', children: <code>{task.id}</code> },
        { key: 'source', label: '来源', children: source?.fullyQualifiedName || data?.sourceTable || '—' },
        { key: 'target', label: '去向', children: target?.fullyQualifiedName || data?.sinkTable || '—' },
      ]} />
      {data?.jobDesc && <p className="data-topology-description">{data.jobDesc}</p>}
      {loading && <Spin size="small" aria-label="正在读取任务信息" />}
      {deleted && <Alert showIcon type="warning" message="任务已删除" />}
      {failed && <Alert showIcon type="warning" message="任务详情暂不可用" />}
      {!deleted && <Button block type="primary" icon={<LinkOutlined />} onClick={() => onOpenTask(task)}>查看任务管理</Button>}
    </>
  );
}

export default function TopologyInspector({ asset, link, assets, fieldId, onFields, onOpenTask }: {
  asset?: LineageAsset;
  link?: LineageLink;
  assets: Map<string, LineageAsset>;
  fieldId?: string;
  onFields: (id: string) => void;
  onOpenTask: (task: LineageTask) => void;
}) {
  return (
    <aside className="data-topology-inspector" aria-label="节点详情">
      {asset ? <TableInspector asset={asset} expanded={fieldId === asset.id} onFields={onFields} />
        : link?.task ? <TaskInspector task={link.task} source={assets.get(link.sourceId)}
          target={assets.get(link.targetId)} onOpenTask={onOpenTask} />
          : <div className="data-topology-inspector-empty">
            <h3>节点详情</h3>
            <p>点击画布中的表或任务，查看详情。</p>
          </div>}
    </aside>
  );
}
