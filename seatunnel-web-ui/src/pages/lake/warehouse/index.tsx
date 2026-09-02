import {
  ArrowRightOutlined,
  CheckCircleOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  LinkOutlined,
  ReloadOutlined,
  SettingOutlined,
  TableOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Button, Spin, Tag, Typography, message } from 'antd';
import { history } from '@umijs/max';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { fetchLakeDorisStatus, fetchLakeWarehouse } from '@/services/lake';
import type { LakeDorisNode, LakeDorisStatus, LakeWarehouseConfig } from '@/services/lake';
import './index.less';

const { Paragraph } = Typography;

type StatusTone = 'success' | 'error' | 'muted';

const statusMeta = (status?: string): { label: string; tone: StatusTone; description: string } => {
  if (status === 'CONNECTED_SUCCESS') {
    return { label: '运行正常', tone: 'success', description: 'Doris FE 已响应，集群节点状态已同步。' };
  }
  if (status === 'CONNECTED_FAILED') {
    return { label: '连接异常', tone: 'error', description: '无法读取 Doris 集群状态，请检查连接配置。' };
  }
  return { label: '待配置', tone: 'muted', description: '完成 Doris 连接配置后，这里会展示真实集群状态。' };
};

const displayValue = (value?: number | string | null) =>
  value === undefined || value === null || value === '' ? '--' : String(value);

const formatTime = (value?: string) => {
  if (!value) return '尚未检查';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').replace(/\.\d+Z$/, '');
  return date.toLocaleString('zh-CN', { hour12: false });
};

const hostFromJdbcUrl = (jdbcUrl?: string) => {
  const authority = jdbcUrl?.match(/^jdbc:[^:]+:\/\/([^/]+)/i)?.[1];
  const host = authority?.replace(/:\d+$/, '');
  return host || 'localhost';
};

const nodeStatusLabel = (status?: string) => status === 'ALIVE' ? '在线' : '离线';

const NodeRows: React.FC<{ nodes?: LakeDorisNode[]; kind: 'FE' | 'BE' }> = ({ nodes, kind }) => {
  if (!nodes?.length) {
    return (
      <div className="lake-node-empty">
        <DatabaseOutlined />
        <span>{kind} 节点数据将在连接 Doris 后显示</span>
      </div>
    );
  }

  return (
    <div className="lake-node-table-wrap">
      <table className="lake-node-table">
        <thead>
          <tr>
            <th>节点</th>
            <th>角色</th>
            <th>端口</th>
            <th>状态</th>
            <th>最近心跳</th>
          </tr>
        </thead>
        <tbody>
          {nodes.map((node, index) => {
            const alive = node.status === 'ALIVE';
            return (
              <tr key={`${kind}-${node.id || node.host || index}`}>
                <td>
                  <div className="lake-node-name">{node.host || '--'}</div>
                  <div className="lake-node-id">{node.id || `${kind}-${index + 1}`}</div>
                </td>
                <td>{node.role || '--'}</td>
                <td>{node.port || '--'}</td>
                <td>
                  <span className={`lake-inline-status lake-inline-status--${alive ? 'success' : 'error'}`}>
                    <span />
                    {nodeStatusLabel(node.status)}
                  </span>
                </td>
                <td>{node.lastHeartbeat || '--'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

const MetricCard: React.FC<{
  label: string;
  value: string;
  description: string;
  icon: React.ReactNode;
  tone?: StatusTone;
}> = ({ label, value, description, icon, tone = 'success' }) => (
  <div className="lake-cluster-metric">
    <div className="lake-cluster-metric-topline">
      <span>{label}</span>
      <span className={`lake-cluster-metric-icon lake-cluster-metric-icon--${tone}`}>{icon}</span>
    </div>
    <div className={`lake-cluster-metric-value lake-cluster-metric-value--${tone}`}>{value}</div>
    <div className="lake-cluster-metric-description">{description}</div>
  </div>
);

const WarehousePage: React.FC = () => {
  const [config, setConfig] = useState<LakeWarehouseConfig>();
  const [status, setStatus] = useState<LakeDorisStatus>();
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [configResponse, statusResponse] = await Promise.all([
        fetchLakeWarehouse(),
        fetchLakeDorisStatus(),
      ]);
      if (configResponse.code === 0) {
        setConfig(configResponse.data || undefined);
      } else {
        message.error(configResponse.msg || configResponse.message || '读取数据湖配置失败');
      }
      if (statusResponse.code === 0) {
        setStatus(statusResponse.data || undefined);
      } else {
        message.error(statusResponse.msg || statusResponse.message || '读取 Doris 集群状态失败');
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '读取数据湖状态失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const clusterStatus = useMemo(() => statusMeta(status?.status), [status?.status]);
  const configured = Boolean(config?.configured || status?.configured);
  const consoleUrl = `http://${hostFromJdbcUrl(config?.jdbcUrl)}:8030/home`;
  const checkedAt = formatTime(status?.checkedAt);
  const feEndpoint = status?.masterHost
    ? `${status.masterHost}:${status.httpPort || '8030'}`
    : '待配置';
  const mysqlEndpoint = config?.jdbcUrl
    ? `${hostFromJdbcUrl(config.jdbcUrl)}:${status?.queryPort || '9030'}`
    : '待配置';

  return (
    <div className="lake-warehouse-overview">
      <header className="lake-overview-header">
        <div className="lake-overview-heading">
          <div className="lake-overview-icon"><CloudServerOutlined /></div>
          <div>
            <h1>数据湖管理</h1>
            <Paragraph>统一管理 Doris 数据湖连接、集群状态与 ODS 资源，让入湖链路始终可见。</Paragraph>
          </div>
        </div>
        <div className="lake-overview-actions">
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新状态</Button>
          <Button type="primary" icon={<SettingOutlined />} onClick={() => history.push('/lake/warehouse/config')}>
            配置 Doris
          </Button>
        </div>
      </header>

      <section className="lake-warehouse-shell">
        <aside className="lake-warehouse-sidebar">
          <div className="lake-sidebar-eyebrow">DATA LAKE</div>
          <div className="lake-sidebar-title">数据湖列表</div>

          <button type="button" className="lake-sidebar-item lake-sidebar-item--active">
            <span className="lake-sidebar-item-icon"><DatabaseIcons dbType="DORIS" width="20" height="20" /></span>
            <span className="lake-sidebar-item-copy">
              <strong>Doris 数据湖</strong>
              <small>ODS 主存储</small>
            </span>
            <span className={`lake-status-dot lake-status-dot--${clusterStatus.tone}`} />
          </button>

          <div className="lake-sidebar-summary">
            <div className="lake-sidebar-summary-row"><span>连接状态</span><strong>{clusterStatus.label}</strong></div>
            <div className="lake-sidebar-summary-row"><span>查询端口</span><strong>{displayValue(status?.queryPort || '9030')}</strong></div>
            <div className="lake-sidebar-summary-row"><span>配置版本</span><strong>{config?.configVersion ? `v${config.configVersion}` : '--'}</strong></div>
          </div>

          <nav className="lake-warehouse-nav" aria-label="数据湖导航">
            <div className="lake-warehouse-nav-label">管理入口</div>
            <button type="button" className="lake-warehouse-nav-item lake-warehouse-nav-item--active">
              <CloudServerOutlined /> 集群概览
            </button>
            <button type="button" className="lake-warehouse-nav-item" onClick={() => history.push('/lake/warehouse/config')}>
              <SettingOutlined /> 连接配置
            </button>
            <button type="button" className="lake-warehouse-nav-item" onClick={() => history.push('/lake/resources')}>
              <TableOutlined /> 物理入湖
            </button>
            <button type="button" className="lake-warehouse-nav-item" onClick={() => history.push('/lake/logical-access')}>
              <LinkOutlined /> 逻辑入湖
            </button>
          </nav>

          <div className="lake-sidebar-foot">
            <span>最近检查</span>
            <strong>{checkedAt}</strong>
          </div>
        </aside>

        <main className="lake-warehouse-content">
          <section className="lake-cluster-hero">
            <div className="lake-cluster-hero-main">
              <div className="lake-cluster-logo"><DatabaseIcons dbType="DORIS" width="30" height="30" /></div>
              <div className="lake-cluster-hero-copy">
                <div className="lake-cluster-title-row">
                  <h2>Doris 数据湖</h2>
                  <Tag className={`lake-status-tag lake-status-tag--${clusterStatus.tone}`}>
                    <span className="lake-status-tag-dot" />
                    {clusterStatus.label}
                  </Tag>
                </div>
                <div className="lake-cluster-subtitle">Doris ODS Cluster</div>
                <div className="lake-cluster-endpoints">
                  <span><i /> FE HTTP {feEndpoint}</span>
                  <span><i /> MySQL {mysqlEndpoint}</span>
                </div>
                <div className={`lake-cluster-description lake-cluster-description--${clusterStatus.tone}`}>
                  {clusterStatus.tone === 'success' ? <CheckCircleOutlined /> : <WarningOutlined />}
                  <span>{clusterStatus.description}</span>
                </div>
              </div>
            </div>
            <div className="lake-cluster-hero-actions">
              <a href={consoleUrl} target="_blank" rel="noreferrer" className="lake-console-link">
                打开 Doris 管理页 <ArrowRightOutlined />
              </a>
              <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新指标</Button>
            </div>
          </section>

          {!configured ? (
            <div className="lake-cluster-notice">
              <div className="lake-cluster-notice-icon"><SettingOutlined /></div>
              <div>
                <strong>还没有配置数据湖连接</strong>
                <p>先配置 Doris FE 节点、查询端口和本地 JDBC 驱动，保存后即可在这里查看集群健康状态。</p>
              </div>
              <Button type="primary" onClick={() => history.push('/lake/warehouse/config')}>开始配置</Button>
            </div>
          ) : null}

          <section className="lake-cluster-section">
            <div className="lake-section-heading">
              <div>
                <h3>集群状态</h3>
                <p>从 Doris 集群只读元数据提取的实时概览</p>
              </div>
              <span className="lake-section-caption">{checkedAt}</span>
            </div>
            <div className="lake-cluster-metrics">
              <MetricCard
                label="FE 节点"
                value={status ? `${displayValue(status.aliveFrontendCount)} / ${displayValue(status.frontendCount)}` : '--'}
                description="在线 / 总数"
                icon={<CloudServerOutlined />}
                tone={status && status.frontendCount !== status.aliveFrontendCount ? 'error' : clusterStatus.tone}
              />
              <MetricCard
                label="BE 节点"
                value={status ? `${displayValue(status.aliveBackendCount)} / ${displayValue(status.backendCount)}` : '--'}
                description="在线 / 总数"
                icon={<DatabaseOutlined />}
                tone={status && status.backendCount !== status.aliveBackendCount ? 'error' : clusterStatus.tone}
              />
              <MetricCard
                label="数据库"
                value={displayValue(status?.databaseCount)}
                description="可发现的数据库数量"
                icon={<TableOutlined />}
                tone={clusterStatus.tone}
              />
              <MetricCard
                label="Doris 版本"
                value={displayValue(status?.version)}
                description="当前集群版本"
                icon={<LinkOutlined />}
                tone={clusterStatus.tone}
              />
            </div>
          </section>

          <section className="lake-node-section-grid">
            <div className="lake-node-panel">
              <div className="lake-node-panel-heading">
                <div><h3>FE 节点</h3><p>Frontend · 负责元数据与查询协调</p></div>
                <span>{displayValue(status?.aliveFrontendCount)} 在线</span>
              </div>
              {loading ? <div className="lake-loading"><Spin size="small" /> 正在同步节点状态</div> : <NodeRows nodes={status?.frontends} kind="FE" />}
            </div>
            <div className="lake-node-panel">
              <div className="lake-node-panel-heading">
                <div><h3>BE 节点</h3><p>Backend · 负责数据存储与计算</p></div>
                <span>{displayValue(status?.aliveBackendCount)} 在线</span>
              </div>
              {loading ? <div className="lake-loading"><Spin size="small" /> 正在同步节点状态</div> : <NodeRows nodes={status?.backends} kind="BE" />}
            </div>
          </section>

          <section className="lake-quick-links">
            <div>
              <div className="lake-sidebar-eyebrow">NEXT STEP</div>
              <h3>继续管理数据湖资源</h3>
              <p>连接状态稳定后，可以进入物理入湖或逻辑入湖继续配置数据资产。</p>
            </div>
            <div className="lake-quick-link-actions">
              <Button onClick={() => history.push('/lake/resources')}>物理入湖 <ArrowRightOutlined /></Button>
              <Button onClick={() => history.push('/lake/logical-access')}>逻辑入湖 <ArrowRightOutlined /></Button>
            </div>
          </section>
        </main>
      </section>
    </div>
  );
};

export default WarehousePage;
