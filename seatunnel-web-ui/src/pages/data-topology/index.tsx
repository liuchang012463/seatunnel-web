import { fetchBusinessSystemOptions, fetchDataSourceUnitOptions, unwrapMasterDataList } from '@/pages/data-source/service';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import { Alert, Button, Select, Spin } from 'antd';
import { useMemo, useState } from 'react';
import { catalogSources, fetchCatalogSources, fetchTopology, type CatalogSource } from './service';
import TopologyOverview from './TopologyOverview';
import TopologyWorkspace from './TopologyWorkspace';
import useLineageRead from './useLineageRead';
import './index.less';

const loadUnits = async () => unwrapMasterDataList(await fetchDataSourceUnitOptions());

function belongsTo(source: CatalogSource, unitId?: string, systemId?: string) {
  return (!unitId || source.unitId === unitId) && (!systemId || source.businessSystemId === systemId);
}

export default function DataTopologyPage() {
  const location = useLocation();
  const params = new URLSearchParams(location.search);
  const unitId = params.get('unitId') || undefined;
  const systemId = unitId ? params.get('businessSystemId') || undefined : undefined;
  const sourceId = params.get('dataSourceId') || undefined;
  const [revision, setRevision] = useState(0);
  const topology = useLineageRead(fetchTopology, revision);
  const directory = useLineageRead(fetchCatalogSources, revision);
  const units = useLineageRead(loadUnits, revision);
  const loadSystems = useMemo(() => unitId
    ? async () => unwrapMasterDataList(await fetchBusinessSystemOptions(unitId)) : undefined, [unitId]);
  const systems = useLineageRead(loadSystems, revision);
  const sources = useMemo(() => catalogSources(directory.data ?? [], topology.data)
    .filter((source) => belongsTo(source, unitId, systemId)), [directory.data, systemId, topology.data, unitId]);
  const source = sources.find(({ id }) => id === sourceId);
  const busy = topology.loading || directory.loading;

  function navigate(nextUnit?: string, nextSystem?: string, nextSource?: string) {
    const query = new URLSearchParams();
    if (nextUnit) query.set('unitId', nextUnit);
    if (nextUnit && nextSystem) query.set('businessSystemId', nextSystem);
    if (nextSource) query.set('dataSourceId', nextSource);
    history.push({ pathname: location.pathname, search: query.size ? `?${query}` : '' });
  }

  function changeScope(nextUnit?: string, nextSystem?: string) {
    const retained = source && belongsTo(source, nextUnit, nextSystem) ? source.id : undefined;
    navigate(nextUnit, nextSystem, retained);
  }

  function openSource(id: string) {
    navigate(unitId, systemId, id);
  }

  return (
    <div className="data-topology-page">
      <header className="data-topology-header">
        {source && <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(unitId, systemId)}>返回总览</Button>}
        <div className="data-topology-scope-filters" aria-label="血缘范围筛选">
          <label>
            <span>单位</span>
            <Select aria-label="单位" allowClear showSearch optionFilterProp="label" value={unitId}
              loading={units.loading} placeholder="全部单位"
              options={units.data?.map(({ id, unitName }) => ({ label: unitName, value: String(id) }))}
              onChange={(id) => changeScope(id)} />
          </label>
          <label>
            <span>业务系统</span>
            <Select aria-label="业务系统" allowClear showSearch optionFilterProp="label" value={systemId}
              loading={systems.loading} disabled={!unitId} placeholder={unitId ? '全部业务系统' : '先选择单位'}
              options={systems.data?.map(({ id, systemName }) => ({ label: systemName, value: String(id) }))}
              onChange={(id) => changeScope(unitId, id)} />
          </label>
          <Button icon={<ReloadOutlined />} loading={busy} onClick={() => setRevision((current) => current + 1)}>刷新血缘</Button>
        </div>
      </header>

      {topology.failed && <Alert showIcon type="warning"
        message={topology.data ? '读取血缘失败，当前显示上次成功读取的结果' : '读取血缘失败，请刷新重试。'} />}
      {directory.failed && <Alert showIcon type="warning" message="读取数据源目录失败，请刷新重试。" />}
      {source ? <TopologyWorkspace key={source.id} sources={sources} source={source} topology={topology.data}
        topologyLoading={topology.loading} revision={revision} onSourceChange={openSource} />
        : sourceId && busy ? <div className="data-topology-empty"><Spin aria-label="正在读取血缘" /></div>
          : <TopologyOverview sources={sources} topology={topology.data} busy={busy} onOpen={openSource}
            unit={unitId ? units.data?.find(({ id }) => String(id) === unitId)?.unitName ?? '所选单位' : '全部'}
            system={systemId ? systems.data?.find(({ id }) => String(id) === systemId)?.systemName ?? '所选系统' : '全部'} />}
    </div>
  );
}
