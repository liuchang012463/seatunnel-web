import { readTaskDefinition } from '@/pages/common/workflow/TaskDefinitionView';
import type { DataExplorationTable } from '@/pages/data-source/types';
import { ApartmentOutlined, ArrowRightOutlined, EyeOutlined, LinkOutlined, TableOutlined } from '@ant-design/icons';
import { Alert, Button, Spin, Tag } from 'antd';
import { useMemo } from 'react';
import { linkId, type TaskGroup } from './graph';
import type { LineageAsset, LineageLink, LineageTask, ReadTableMetadata } from './service';
import { formatTableCount, taskKindLabel } from './TopologyNodes';
import useLineageRead from './useLineageRead';

function TableInspector({ asset, summary, expanded, readTable, onFields }: {
  asset: LineageAsset;
  summary?: DataExplorationTable;
  expanded: boolean;
  readTable: ReadTableMetadata;
  onFields: (id: string) => void;
}) {
  const table = asset.type === 'table';
  const load = useMemo(() => table ? () => readTable(asset) : undefined, [asset, readTable, table]);
  const { data, loading, failed } = useLineageRead(load);
  const description = data?.description || summary?.description;
  const tableType = data?.tableType || summary?.tableType;
  return (
    <>
      <header className="data-topology-panel-heading">
        <span className="data-topology-overview-icon"><TableOutlined /></span>
        <h3>{data?.displayName || summary?.displayName || asset.name}</h3>
      </header>
      <section className="data-topology-panel-section">
        <h4>元数据摘要</h4>
        {table && <dl className="data-topology-metrics">
          <div><dt>数据行数</dt><dd>{formatTableCount(data?.rowCount ?? summary?.rowCount)}</dd></div>
          <div><dt>字段数</dt><dd>{formatTableCount(data?.columns.length ?? summary?.columnCount)}</dd></div>
        </dl>}
        <dl className="data-topology-panel-values">
          <div><dt>类型</dt><dd>{tableType || (table ? '表' : asset.type)}</dd></div>
          {asset.database && <div><dt>数据库</dt><dd>{asset.database}</dd></div>}
          {asset.schema && <div><dt>Schema</dt><dd>{asset.schema}</dd></div>}
        </dl>
        <code className="data-topology-fqn" title={asset.fullyQualifiedName}>{asset.fullyQualifiedName}</code>
      </section>
      {table && <section className="data-topology-panel-section">
        <h4>表注释</h4>
        <p>{description || '尚未补充注释'}</p>
      </section>}
      {Boolean(data?.tableConstraints.length) && <section className="data-topology-panel-section">
        <h4>约束</h4>
        <ul className="data-topology-panel-list">
          {data?.tableConstraints.map((constraint, index) => (
            <li key={index}><strong>{constraint.constraintType}</strong>
              <small>{constraint.columns?.join(', ')}</small></li>
          ))}
        </ul>
      </section>}
      {table && <div className="data-topology-panel-section">
        {loading && <Spin size="small" aria-label="正在读取表信息" />}
        {failed && <Alert showIcon type="warning" message="表信息暂不可用" />}
        <Button block type={expanded ? 'default' : 'primary'} icon={<EyeOutlined />} aria-pressed={expanded}
          onClick={() => onFields(asset.id)}>{expanded ? '收起字段血缘' : '查看字段血缘'}</Button>
      </div>}
    </>
  );
}

function TaskInspector({ group, assets, selectedPairId, onOpenTask, onPair }: {
  group: TaskGroup;
  assets: Map<string, LineageAsset>;
  selectedPairId?: string;
  onOpenTask: (task: LineageTask) => void;
  onPair: (link: LineageLink) => void;
}) {
  const { task, links } = group;
  const load = useMemo(() => task.deleted ? undefined : () => readTaskDefinition(task.kind, task.id), [task]);
  const { data, loading, failed } = useLineageRead(load);
  const deleted = task.deleted || data === null;
  const name = data?.jobName || task.name || `任务 ${task.id}`;
  const releaseState = data?.releaseState === 'ONLINE' ? '已上线'
    : data?.releaseState === 'OFFLINE' ? '已下线' : data?.releaseState;
  const active = links.find((link) => linkId(link) === selectedPairId);
  const endpoints = active ? [
    { label: '来源', asset: assets.get(active.sourceId), name: data?.sourceTable },
    { label: '去向', asset: assets.get(active.targetId), name: data?.sinkTable },
  ] : [];
  return (
    <>
      <header className="data-topology-panel-heading">
        <span className="data-topology-overview-icon"><ApartmentOutlined /></span>
        <h3>{name}</h3>
      </header>
      <section className="data-topology-panel-section">
        <h4>任务信息</h4>
        <div className="data-topology-task-tags">
          <Tag>{taskKindLabel[task.kind]}</Tag>
          {releaseState && <Tag>{releaseState}</Tag>}
        </div>
        <dl className="data-topology-panel-values">
          <div><dt>任务定义 ID</dt><dd><code>{task.id}</code></dd></div>
        </dl>
      </section>
      {active && <section className="data-topology-panel-section">
        <h4>数据流向</h4>
        <ul className="data-topology-panel-list data-topology-flow">
          {endpoints.map(({ label, asset, name: table }) => (
            <li key={label}>
              <small>{label}</small><strong>{asset?.name || table || '—'}</strong>
              {asset && <code className="data-topology-fqn" title={asset.fullyQualifiedName}>{asset.fullyQualifiedName}</code>}
            </li>
          ))}
        </ul>
      </section>}
      {links.length > 1 && <section className="data-topology-panel-section">
        <div className="data-topology-pair-heading"><h4>表关系</h4><span>{links.length} 对表</span></div>
        <ul className="data-topology-panel-list data-topology-pairs">
          {links.map((link) => {
            const id = linkId(link);
            const source = assets.get(link.sourceId);
            const target = assets.get(link.targetId);
            if (!source || !target) return null;
            const same = source.name === target.name;
            return (
              <li key={id}>
                <button type="button" aria-pressed={selectedPairId === id} onClick={() => onPair(link)}
                  title={`${source.fullyQualifiedName} → ${target.fullyQualifiedName}`}>
                  <strong>{source.name}</strong>
                  <span>{same ? [source.database, source.schema].filter(Boolean).join('.') : '来源表'}</span>
                  <ArrowRightOutlined />
                  <span>{same ? [target.database, target.schema].filter(Boolean).join('.') : target.name}</span>
                </button>
              </li>
            );
          })}
        </ul>
      </section>}
      {data?.jobDesc && <section className="data-topology-panel-section"><h4>任务说明</h4><p>{data.jobDesc}</p></section>}
      <div className="data-topology-panel-section">
        {loading && <Spin size="small" aria-label="正在读取任务信息" />}
        {deleted && <Alert showIcon type="warning" message="任务已删除" />}
        {failed && <Alert showIcon type="warning" message="任务详情暂不可用" />}
        {!deleted && <Button block type="primary" icon={<LinkOutlined />}
          onClick={() => onOpenTask(task)}>查看任务管理</Button>}
      </div>
    </>
  );
}

export default function TopologyInspector({
  asset, group, assets, selectedPairId, summary, expanded, readTable, onFields, onOpenTask, onPair,
}: {
  asset?: LineageAsset;
  group?: TaskGroup;
  assets: Map<string, LineageAsset>;
  selectedPairId?: string;
  summary?: DataExplorationTable;
  expanded: boolean;
  readTable: ReadTableMetadata;
  onFields: (id: string) => void;
  onOpenTask: (task: LineageTask) => void;
  onPair: (link: LineageLink) => void;
}) {
  return (
    <aside className="data-topology-inspector" aria-label="节点详情">
      {asset ? <TableInspector asset={asset} summary={summary} expanded={expanded} readTable={readTable} onFields={onFields} />
        : group ? <TaskInspector group={group} assets={assets} selectedPairId={selectedPairId}
          onOpenTask={onOpenTask} onPair={onPair} />
          : <>
            <header className="data-topology-panel-heading"><h3>节点详情</h3></header>
            <section className="data-topology-panel-section"><p>点击表或任务查看详情。</p></section>
          </>}
    </aside>
  );
}
