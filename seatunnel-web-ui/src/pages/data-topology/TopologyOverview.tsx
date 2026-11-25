import { DATA_SOURCE_REGISTRY } from '@/pages/data-source/dataSourceRegistry';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { ApartmentOutlined, RightOutlined, SearchOutlined } from '@ant-design/icons';
import { Empty, Input, Spin, Tag } from 'antd';
import { useMemo, useState } from 'react';
import type { CatalogSource, Topology } from './service';

export default function TopologyOverview({ sources, topology, busy, unit, system, onOpen }: {
  sources: CatalogSource[];
  topology?: Topology;
  busy: boolean;
  unit: string;
  system: string;
  onOpen: (id: string) => void;
}) {
  const [search, setSearch] = useState('');
  const [selectedType, setSelectedType] = useState<string>();
  const term = search.trim().toLowerCase();
  const types = useMemo(() => {
    const counts = new Map<string, number>();
    for (const { dbType } of sources) {
      if (!dbType) continue;
      const key = dbType.toUpperCase();
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    // 按实际有血缘目录的数据源分组，不展示空类型或尚未支持的类型。
    return DATA_SOURCE_REGISTRY.flatMap(({ dbType, label }) => {
      const count = counts.get(dbType);
      return count ? [{ dbType, label, shortLabel: label.split("（")[0], count }] : [];
    });
  }, [sources]);
  const activeType = types.find(({ dbType }) => dbType === selectedType);
  const visible = useMemo(() => sources.filter((source) =>
    (!activeType || source.dbType?.toUpperCase() === activeType.dbType)
    && (!term || [source.name, source.dbType, source.unitName, source.systemName]
      .some((value) => value?.toLowerCase().includes(term)))), [activeType, sources, term]);
  const stats = useMemo(() => {
    const ids = new Set(visible.map(({ id }) => id));
    const ownerByAsset = new Map(topology?.assets.map(({ id, dataSourceId }) => [id, dataSourceId]));
    const counts = new Map<string, number>();
    const tasks = new Set<string>();
    let links = 0;
    for (const link of topology?.edges ?? []) {
      const owners = new Set([ownerByAsset.get(link.sourceId), ownerByAsset.get(link.targetId)]);
      let matched = false;
      for (const owner of owners) {
        if (!owner || !ids.has(owner)) continue;
        matched = true;
        counts.set(owner, (counts.get(owner) ?? 0) + 1);
      }
      if (!matched) continue;
      links++;
      if (link.task && !link.task.deleted) tasks.add(`${link.task.kind}:${link.task.id}`);
    }
    const assets = topology?.assets.filter(({ dataSourceId }) => ids.has(dataSourceId)).length ?? 0;
    return { assets, links, tasks: tasks.size, counts };
  }, [topology, visible]);
  const metrics = [
    ['数据源', visible.length],
    ['关联资产', stats.assets],
    ['血缘关系', stats.links],
    ['关联任务', stats.tasks],
  ] as const;

  return (
    <div className="data-topology-workspace data-topology-results">
      <nav className="data-topology-browser data-topology-type-nav" aria-label="血缘类型导航">
        <div className="data-topology-overview-heading">
          <h2>血缘导航</h2><span>{sources.length} 个数据源</span>
        </div>
        <button type="button" className="data-topology-type-item"
          aria-pressed={!activeType} onClick={() => setSelectedType(undefined)}>
          <span className="data-topology-overview-icon"><ApartmentOutlined /></span>
          <strong>全部类型</strong><small>{sources.length}</small>
        </button>
        {types.length > 0 && <div className="data-topology-type-list">
          {types.map(({ dbType, label, shortLabel, count }) => (
            <button type="button" key={dbType} className="data-topology-type-item"
              title={label} aria-pressed={activeType?.dbType === dbType} onClick={() => setSelectedType(dbType)}>
              <span className="data-topology-overview-icon"><DatabaseIcons dbType={dbType} /></span>
              <strong>{shortLabel}</strong><small>{count}</small>
            </button>
          ))}
        </div>}
      </nav>
      <section className="data-topology-results-main" aria-label="数据源血缘总览">
        <header className="data-topology-overview-heading">
          <h2>数据源血缘总览</h2>
          <Input allowClear aria-label="搜索数据源" placeholder="搜索数据源、类型或归属"
            prefix={<SearchOutlined />} value={search} onChange={(event) => setSearch(event.target.value)} />
        </header>
        <div className="data-topology-source-list">
          {busy && sources.length === 0 ? <Spin aria-label="正在加载数据源" />
            : !visible.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={term ? '没有匹配的数据源' : '当前范围暂无数据源'} />
              : visible.map((source) => (
                <button type="button" key={source.id} className="data-topology-source-item" onClick={() => onOpen(source.id)}>
                  <span className="data-topology-overview-icon"><DatabaseIcons dbType={source.dbType ?? undefined} /></span>
                  <span className="data-topology-source-copy">
                    <strong>{source.name}</strong>
                    <small>{[source.unitName, source.systemName].filter(Boolean).join(' / ')}</small>
                  </span>
                  {source.dbType && <Tag>{source.dbType}</Tag>}
                  <span className="data-topology-source-count">{topology ? `${stats.counts.get(source.id) ?? 0} 条血缘` : '—'}</span>
                  <RightOutlined />
                </button>
              ))}
        </div>
        <footer>共 {visible.length} 个数据源</footer>
      </section>
      <aside className="data-topology-inspector" aria-label="当前结果摘要">
        <header className="data-topology-panel-heading"><h3>当前结果摘要</h3></header>
        <section className="data-topology-panel-section">
          <h4>当前范围</h4>
          <dl className="data-topology-panel-values">
            <div><dt>单位</dt><dd>{unit}</dd></div>
            <div><dt>业务系统</dt><dd>{system}</dd></div>
            <div><dt>数据源类型</dt><dd>{activeType?.shortLabel ?? '全部类型'}</dd></div>
          </dl>
        </section>
        <section className="data-topology-panel-section">
          <h4>血缘统计</h4>
          <dl className="data-topology-metrics">
            {metrics.map(([label, count]) => (
              <div key={label}><dt>{label}</dt><dd>{topology || label === '数据源' ? count.toLocaleString('zh-CN') : '—'}</dd></div>
            ))}
          </dl>
        </section>
      </aside>
    </div>
  );
}
