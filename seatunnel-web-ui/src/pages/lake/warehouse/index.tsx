import {
  CheckCircleOutlined,
  CloudServerOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DesktopOutlined,
  HddOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  SettingOutlined,
  TableOutlined,
  TeamOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Button, Spin, Tag, Typography, message } from 'antd';
import { history } from '@umijs/max';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { fetchLakeDorisHardware, fetchLakeDorisStatus, fetchLakeWarehouse } from '@/services/lake';
import type { LakeDorisHardware, LakeDorisNode, LakeDorisStatus, LakeWarehouseConfig } from '@/services/lake';
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

const nodeStatusLabel = (status?: string) => {
  if (status === 'ALIVE') return '在线';
  return status ? '离线' : '未知';
};

const nodeStatusTone = (status?: string): StatusTone => {
  if (status === 'ALIVE') return 'success';
  return status ? 'error' : 'muted';
};

const percentValue = (value?: string | number | null) => {
  const parsed = Number.parseFloat(String(value ?? '').replace('%', '').trim());
  return Number.isFinite(parsed) ? Math.min(100, Math.max(0, parsed)) : undefined;
};

const formatPercent = (value?: string | number | null) => {
  if (value === undefined || value === null || value === '') return '--';
  const text = String(value).trim();
  return text.endsWith('%') ? text : `${text}%`;
};

const maxBackendUsage = (nodes?: LakeDorisNode[]) => {
  const values = nodes?.map((node) => percentValue(node.usedPct)).filter((value): value is number => value !== undefined) || [];
  return values.length ? Math.max(...values) : undefined;
};

const UsageMeter: React.FC<{ value?: string }> = ({ value }) => {
  const percent = percentValue(value);
  if (percent === undefined) return <span className="lake-node-usage-empty">--</span>;

  return (
    <span className="lake-node-usage" title={`存储占用 ${formatPercent(value)}`}>
      <span className="lake-node-usage-track" aria-hidden="true">
        <span
          className={`lake-node-usage-fill lake-node-usage-fill--${percent >= 80 ? 'high' : 'normal'}`}
          style={{ width: `${percent}%` }}
        />
      </span>
      <strong>{formatPercent(value)}</strong>
    </span>
  );
};

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
      <table className="lake-node-table" aria-label={`${kind === 'FE' ? 'FE' : 'BE'} 节点监控列表`}>
        <thead>
          <tr>
            <th>节点</th>
            <th>角色</th>
            <th>版本</th>
            <th>HTTP 端口</th>
            {kind === 'BE' ? <th>存储占用</th> : null}
            <th>状态</th>
            <th>最近心跳</th>
          </tr>
        </thead>
        <tbody>
          {nodes.map((node, index) => {
            const tone = nodeStatusTone(node.status);
            return (
              <tr key={`${kind}-${node.id || node.host || index}`}>
                <td>
                  <div className="lake-node-name">{node.host || '--'}</div>
                  <div className="lake-node-id">{node.id || `${kind}-${index + 1}`}</div>
                </td>
                <td>{node.role || '--'}</td>
                <td>{node.version || '--'}</td>
                <td>{node.port || '--'}</td>
                {kind === 'BE' ? <td><UsageMeter value={node.usedPct} /></td> : null}
                <td>
                  <span className={`lake-inline-status lake-inline-status--${tone}`}>
                    <span />
                    {nodeStatusLabel(node.status)}
                  </span>
                </td>
                <td>{formatTime(node.lastHeartbeat)}</td>
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

const HardwareMetric: React.FC<{
  label: string;
  value: string;
  description: string;
  icon: React.ReactNode;
  progress?: number;
  tone?: StatusTone;
}> = ({ label, value, description, icon, progress, tone = 'success' }) => (
  <div className="lake-hardware-metric">
    <div className="lake-cluster-metric-topline">
      <span>{label}</span>
      <span className={`lake-cluster-metric-icon lake-cluster-metric-icon--${tone}`}>{icon}</span>
    </div>
    <div className={`lake-hardware-metric-value lake-hardware-metric-value--${tone}`}>{value}</div>
    {progress !== undefined ? (
      <div className="lake-hardware-progress" aria-hidden="true">
        <span style={{ width: `${Math.min(100, Math.max(0, progress))}%` }} />
      </div>
    ) : null}
    <div className="lake-cluster-metric-description">{description}</div>
  </div>
);

const WarehousePage: React.FC = () => {
  const [config, setConfig] = useState<LakeWarehouseConfig>();
  const [status, setStatus] = useState<LakeDorisStatus>();
  const [hardware, setHardware] = useState<LakeDorisHardware>();
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [configResult, statusResult, hardwareResult] = await Promise.allSettled([
        fetchLakeWarehouse(),
        fetchLakeDorisStatus(),
        fetchLakeDorisHardware(),
      ]);
      if (configResult.status === 'fulfilled' && configResult.value.code === 0) {
        setConfig(configResult.value.data || undefined);
      } else {
        const response = configResult.status === 'fulfilled' ? configResult.value : undefined;
        message.error(response?.msg || response?.message || '读取数据湖配置失败');
      }
      if (statusResult.status === 'fulfilled' && statusResult.value.code === 0) {
        setStatus(statusResult.value.data || undefined);
      } else {
        const response = statusResult.status === 'fulfilled' ? statusResult.value : undefined;
        message.error(response?.msg || response?.message || '读取 Doris 集群状态失败');
      }
      if (hardwareResult.status === 'fulfilled' && hardwareResult.value.code === 0) {
        setHardware(hardwareResult.value.data || undefined);
      } else {
        setHardware({ status: 'CONNECTED_FAILED', message: 'Doris FE 主机硬件信息暂不可用' });
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
  const checkedAt = formatTime(status?.checkedAt);
  const backendUsage = maxBackendUsage(status?.backends);
  const totalNodes = (status?.frontendCount || 0) + (status?.backendCount || 0);
  const aliveNodes = (status?.aliveFrontendCount || 0) + (status?.aliveBackendCount || 0);
  const nodeHealthTone: StatusTone = totalNodes && aliveNodes < totalNodes ? 'error' : clusterStatus.tone;
  const hardwareCheckedAt = formatTime(hardware?.checkedAt || status?.checkedAt);
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
            <Paragraph>统一管理 Doris 数据湖连接与集群运行指标，持续掌握 FE/BE 健康状态。</Paragraph>
          </div>
        </div>
        <div className="lake-overview-actions">
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新状态</Button>
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
            <div className="lake-sidebar-summary-row"><span>在线节点</span><strong>{totalNodes ? `${aliveNodes} / ${totalNodes}` : '--'}</strong></div>
            <div className="lake-sidebar-summary-row"><span>最高存储占用</span><strong>{backendUsage === undefined ? '--' : `${backendUsage}%`}</strong></div>
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
                  <span>{status?.message || clusterStatus.description}</span>
                </div>
              </div>
            </div>
            <div className="lake-cluster-hero-actions">
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
                <h3>运行监控</h3>
                <p>同步 Doris Home 可见的集群版本、节点健康和存储使用概况</p>
              </div>
              <span className="lake-section-caption"><InfoCircleOutlined /> 只读采集 · {checkedAt}</span>
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
                icon={<InfoCircleOutlined />}
                tone={clusterStatus.tone}
              />
              <MetricCard
                label="存储占用"
                value={backendUsage === undefined ? '--' : `${backendUsage}%`}
                description="BE 节点最高使用率"
                icon={<HddOutlined />}
                tone={backendUsage !== undefined && backendUsage >= 80 ? 'error' : clusterStatus.tone}
              />
            </div>
          </section>

          <section className="lake-cluster-section lake-hardware-section">
            <div className="lake-section-heading">
              <div>
                <h3>主机硬件</h3>
                <p>将 Doris Home 的 Hardware Info 归纳为可读指标，快速判断 FE 主机资源压力。</p>
              </div>
              <span className="lake-section-caption"><InfoCircleOutlined /> FE 主机 · {hardwareCheckedAt}</span>
            </div>
            {loading && !hardware ? (
              <div className="lake-hardware-empty"><Spin size="small" /> 正在读取 Doris FE 主机信息</div>
            ) : hardware?.status !== 'CONNECTED_SUCCESS' ? (
              <div className="lake-hardware-empty lake-hardware-empty--muted">
                <WarningOutlined />
                <span>{hardware?.message || 'Doris FE 主机硬件信息暂不可用，仍可查看集群与节点状态。'}</span>
              </div>
            ) : (
              <div className="lake-hardware-layout">
                <div className="lake-hardware-grid">
                  <HardwareMetric
                    label="CPU 负载"
                    value={displayValue(hardware.cpuLoad)}
                    description={`${displayValue(hardware.cpuCores)} 核 · ${displayValue(hardware.cpuModel)}`}
                    icon={<DashboardOutlined />}
                    progress={percentValue(hardware.cpuLoad)}
                  />
                  <HardwareMetric
                    label="内存"
                    value={hardware.memoryUsed && hardware.memoryTotal ? `${hardware.memoryUsed} / ${hardware.memoryTotal}` : '--'}
                    description={`${displayValue(hardware.memoryUsedPercent)} 已使用`}
                    icon={<DesktopOutlined />}
                    progress={percentValue(hardware.memoryUsedPercent)}
                  />
                  <HardwareMetric
                    label="文件系统"
                    value={hardware.filesystemFree ? `${hardware.filesystemFree} 可用` : '--'}
                    description={`总容量 ${displayValue(hardware.filesystemTotal)} · ${displayValue(hardware.filesystemFreePercent)} 剩余`}
                    icon={<HddOutlined />}
                    progress={percentValue(hardware.filesystemFreePercent)}
                  />
                  <HardwareMetric
                    label="进程 / 线程"
                    value={`${displayValue(hardware.processCount)} / ${displayValue(hardware.threadCount)}`}
                    description="当前 FE 主机运行规模"
                    icon={<TeamOutlined />}
                  />
                </div>
                <div className="lake-hardware-details">
                  <div className="lake-hardware-details-title">主机环境</div>
                  <div className="lake-hardware-details-grid">
                    <div className="lake-hardware-detail-row"><span>主机名</span><strong>{displayValue(hardware.hostName)}</strong></div>
                    <div className="lake-hardware-detail-row"><span>IPv4</span><strong>{displayValue(hardware.ipv4)}</strong></div>
                    <div className="lake-hardware-detail-row"><span>操作系统</span><strong>{displayValue(hardware.os)}</strong></div>
                    <div className="lake-hardware-detail-row"><span>运行时长</span><strong>{displayValue(hardware.uptime)}</strong></div>
                    <div className="lake-hardware-detail-row"><span>构建版本</span><strong>{displayValue(hardware.version)}</strong></div>
                    <div className="lake-hardware-detail-row"><span>网络流量</span><strong>{displayValue(hardware.networkReceive)} / {displayValue(hardware.networkTransmit)}</strong></div>
                  </div>
                </div>
              </div>
            )}
          </section>

          <section className="lake-cluster-section lake-nodes-section">
            <div className="lake-section-heading">
              <div>
                <h3>节点监控</h3>
                <p>展示 FE / BE 节点的角色、版本、端口、心跳和存储占用，异常节点会优先标红。</p>
              </div>
              <span className={`lake-section-health lake-section-health--${nodeHealthTone}`}>
                <span /> {totalNodes ? `${aliveNodes} / ${totalNodes} 节点在线` : '等待节点数据'}
              </span>
            </div>
            <div className="lake-node-section-grid">
              <div className="lake-node-panel">
                <div className="lake-node-panel-heading">
                  <div><h3>FE 节点</h3><p>Frontend · 元数据与查询协调</p></div>
                  <span>{displayValue(status?.aliveFrontendCount)} / {displayValue(status?.frontendCount)} 在线</span>
                </div>
                {loading ? <div className="lake-loading"><Spin size="small" /> 正在同步节点状态</div> : <NodeRows nodes={status?.frontends} kind="FE" />}
              </div>
              <div className="lake-node-panel">
                <div className="lake-node-panel-heading">
                  <div><h3>BE 节点</h3><p>Backend · 数据存储与计算</p></div>
                  <span>{displayValue(status?.aliveBackendCount)} / {displayValue(status?.backendCount)} 在线</span>
                </div>
                {loading ? <div className="lake-loading"><Spin size="small" /> 正在同步节点状态</div> : <NodeRows nodes={status?.backends} kind="BE" />}
              </div>
            </div>
          </section>
        </main>
      </section>
    </div>
  );
};

export default WarehousePage;
