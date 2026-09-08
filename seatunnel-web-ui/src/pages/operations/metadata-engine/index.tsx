import {
  ApiOutlined,
  CheckCircleOutlined,
  CloudServerOutlined,
  InfoCircleOutlined,
  LinkOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Button, Spin, Tag, Typography, message } from 'antd';
import { history } from '@umijs/max';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  fetchOpenMetadataServer,
  type MetadataApiResponse,
  type OpenMetadataServerConfig,
} from '@/services/metadata/server';
import './index.less';

const { Paragraph } = Typography;

type StatusTone = 'success' | 'error' | 'muted';

const responseError = (response: MetadataApiResponse<unknown>, fallback: string) =>
  response.msg || response.message || fallback;

const statusMeta = (config?: OpenMetadataServerConfig): { label: string; tone: StatusTone; description: string } => {
  if (!config?.configured) {
    return {
      label: '待配置',
      tone: 'muted',
      description: '完成 OpenMetadata 连接配置后，这里会展示控制面健康状态。',
    };
  }
  if (config.connStatus === 'CONNECTED_SUCCESS') {
    return {
      label: '已连接',
      tone: 'success',
      description: 'OpenMetadata 控制面可达，版本契约校验通过。',
    };
  }
  if (config.connStatus === 'CONNECTED_FAILED') {
    return {
      label: '连接异常',
      tone: 'error',
      description: config.lastError || '无法连接 OpenMetadata，请检查 Base URL 与 Token。',
    };
  }
  return {
    label: '已配置',
    tone: 'muted',
    description: '连接参数已保存，可刷新状态或重新测试连接。',
  };
};

const displayValue = (value?: number | string | null) =>
  value === undefined || value === null || value === '' ? '--' : String(value);

const hostFromBaseUrl = (baseUrl?: string) => {
  if (!baseUrl) return '待配置';
  try {
    const uri = new URL(baseUrl);
    return `${uri.host}${uri.pathname.replace(/\/$/, '')}`;
  } catch {
    return baseUrl;
  }
};

const MetricCard: React.FC<{
  label: string;
  value: string;
  description: string;
  icon: React.ReactNode;
  tone?: StatusTone;
}> = ({ label, value, description, icon, tone = 'success' }) => (
  <div className="meta-engine-metric">
    <div className="meta-engine-metric-topline">
      <span>{label}</span>
      <span className={`meta-engine-metric-icon meta-engine-metric-icon--${tone}`}>{icon}</span>
    </div>
    <div className={`meta-engine-metric-value meta-engine-metric-value--${tone}`}>{value}</div>
    <div className="meta-engine-metric-description">{description}</div>
  </div>
);

const MetadataEngineOverviewPage: React.FC = () => {
  const [config, setConfig] = useState<OpenMetadataServerConfig>();
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetchOpenMetadataServer();
      if (response.code !== 0) {
        message.error(responseError(response, '读取探查引擎配置失败'));
        return;
      }
      setConfig(response.data || undefined);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '读取探查引擎配置失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const clusterStatus = useMemo(() => statusMeta(config), [config]);
  const health = config?.health;
  const configured = Boolean(config?.configured);
  const expectedServer = config?.expectedServerVersion || '1.12.10';
  const expectedIngestion = config?.expectedIngestionPatch || '1.12.10.0';
  const versionTone: StatusTone = !configured
    ? 'muted'
    : health?.versionCompatible
      ? 'success'
      : 'error';

  return (
    <div className="meta-engine-overview">
      <header className="meta-engine-header">
        <div className="meta-engine-heading">
          <div className="meta-engine-icon"><ApiOutlined /></div>
          <div>
            <h1>探查引擎管理</h1>
            <Paragraph>
              配置 OpenMetadata 控制面连接。参数保存在系统数据库；配置完成后探查能力自动可用。
            </Paragraph>
          </div>
        </div>
        <div className="meta-engine-actions">
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
            刷新状态
          </Button>
        </div>
      </header>

      <section className="meta-engine-shell">
        <aside className="meta-engine-sidebar">
          <div className="meta-engine-sidebar-eyebrow">EXPLORATION</div>
          <div className="meta-engine-sidebar-title">探查引擎</div>

          <button type="button" className="meta-engine-sidebar-item meta-engine-sidebar-item--active">
            <span className="meta-engine-sidebar-item-icon"><ApiOutlined /></span>
            <span className="meta-engine-sidebar-item-copy">
              <strong>OpenMetadata</strong>
              <small>元数据控制面</small>
            </span>
            <span className={`meta-engine-status-dot meta-engine-status-dot--${clusterStatus.tone}`} />
          </button>

          <div className="meta-engine-sidebar-summary">
            <div className="meta-engine-sidebar-summary-row">
              <span>连接状态</span>
              <strong>{clusterStatus.label}</strong>
            </div>
            <div className="meta-engine-sidebar-summary-row">
              <span>Token</span>
              <strong>{config?.tokenConfigured ? '已配置' : '未配置'}</strong>
            </div>
            <div className="meta-engine-sidebar-summary-row">
              <span>配置版本</span>
              <strong>{config?.configVersion ? `v${config.configVersion}` : '--'}</strong>
            </div>
            <div className="meta-engine-sidebar-summary-row">
              <span>版本契约</span>
              <strong>{expectedServer}</strong>
            </div>
          </div>

          <nav className="meta-engine-nav" aria-label="探查引擎导航">
            <div className="meta-engine-nav-label">管理入口</div>
            <button type="button" className="meta-engine-nav-item meta-engine-nav-item--active">
              <CloudServerOutlined /> 引擎概览
            </button>
            <button
              type="button"
              className="meta-engine-nav-item"
              onClick={() => history.push('/operations/metadata-engine/config')}
            >
              <SettingOutlined /> 连接配置
            </button>
          </nav>

          <div className="meta-engine-sidebar-foot">
            <span>固定契约</span>
            <strong>Server {expectedServer} / Ingestion {expectedIngestion}</strong>
          </div>
        </aside>

        <main className="meta-engine-content">
          <Spin spinning={loading}>
            <section className="meta-engine-hero">
              <div className="meta-engine-hero-main">
                <div className="meta-engine-logo"><ApiOutlined /></div>
                <div className="meta-engine-hero-copy">
                  <div className="meta-engine-title-row">
                    <h2>OpenMetadata</h2>
                    <Tag className={`meta-engine-status-tag meta-engine-status-tag--${clusterStatus.tone}`}>
                      <span className="meta-engine-status-tag-dot" />
                      {clusterStatus.label}
                    </Tag>
                  </div>
                  <div className="meta-engine-subtitle">Exploration Control Plane</div>
                  <div className="meta-engine-endpoints">
                    <span><i /> {hostFromBaseUrl(config?.baseUrl)}</span>
                    <span><i /> Token {config?.tokenConfigured ? '已配置' : '未配置'}</span>
                  </div>
                  <div className={`meta-engine-description meta-engine-description--${clusterStatus.tone}`}>
                    {clusterStatus.tone === 'success' ? <CheckCircleOutlined /> : <WarningOutlined />}
                    <span>{config?.lastError || clusterStatus.description}</span>
                  </div>
                </div>
              </div>
              <div className="meta-engine-hero-actions">
                <Button
                  type="primary"
                  icon={<SettingOutlined />}
                  onClick={() => history.push('/operations/metadata-engine/config')}
                >
                  编辑连接配置
                </Button>
                <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
                  刷新指标
                </Button>
              </div>
            </section>

            {!configured ? (
              <div className="meta-engine-notice">
                <div className="meta-engine-notice-icon"><SettingOutlined /></div>
                <div>
                  <strong>还没有配置探查引擎连接</strong>
                  <p>先填写 OpenMetadata Base URL 与 Bot Token，保存后即可在这里查看控制面健康状态。</p>
                </div>
                <Button type="primary" onClick={() => history.push('/operations/metadata-engine/config')}>
                  开始配置
                </Button>
              </div>
            ) : null}

            <section className="meta-engine-section">
              <div className="meta-engine-section-heading">
                <div>
                  <h3>健康摘要</h3>
                  <p>同步 OpenMetadata 服务、编排器与固定版本契约的兼容情况</p>
                </div>
                <span className="meta-engine-section-caption">
                  <InfoCircleOutlined /> 只读采集 · 配置版本 {displayValue(config?.configVersion)}
                </span>
              </div>
              <div className="meta-engine-metrics">
                <MetricCard
                  label="OpenMetadata"
                  value={displayValue(health?.openMetadata)}
                  description="控制面可达性"
                  icon={<CloudServerOutlined />}
                  tone={health?.openMetadata === 'UP' ? 'success' : configured ? 'error' : 'muted'}
                />
                <MetricCard
                  label="Orchestrator"
                  value={displayValue(health?.orchestrator)}
                  description="编排器状态"
                  icon={<LinkOutlined />}
                  tone={health?.orchestrator === 'UP' ? 'success' : configured ? 'error' : 'muted'}
                />
                <MetricCard
                  label="Server 版本"
                  value={displayValue(health?.version)}
                  description={`期望 ${expectedServer}`}
                  icon={<InfoCircleOutlined />}
                  tone={versionTone}
                />
                <MetricCard
                  label="Ingestion 版本"
                  value={displayValue(health?.ingestionVersion)}
                  description={`期望 ${expectedIngestion}`}
                  icon={<SafetyCertificateOutlined />}
                  tone={versionTone}
                />
                <MetricCard
                  label="版本兼容"
                  value={!configured ? '--' : health?.versionCompatible ? '兼容' : '不兼容'}
                  description="固定契约校验结果"
                  icon={health?.versionCompatible ? <CheckCircleOutlined /> : <WarningOutlined />}
                  tone={versionTone}
                />
              </div>
            </section>

            <section className="meta-engine-section">
              <div className="meta-engine-section-heading">
                <div>
                  <h3>连接参数</h3>
                  <p>当前生效的 OpenMetadata 连接摘要；敏感 Token 不会回显</p>
                </div>
              </div>
              <div className="meta-engine-details">
                <div className="meta-engine-detail-row">
                  <span>Base URL</span>
                  <strong>{displayValue(config?.baseUrl)}</strong>
                </div>
                <div className="meta-engine-detail-row">
                  <span>连接超时</span>
                  <strong>{config?.connectTimeoutMs != null ? `${config.connectTimeoutMs} ms` : '--'}</strong>
                </div>
                <div className="meta-engine-detail-row">
                  <span>读超时</span>
                  <strong>{config?.readTimeoutMs != null ? `${config.readTimeoutMs} ms` : '--'}</strong>
                </div>
                <div className="meta-engine-detail-row">
                  <span>期望 Server</span>
                  <strong>{expectedServer}</strong>
                </div>
                <div className="meta-engine-detail-row">
                  <span>期望 Ingestion</span>
                  <strong>{expectedIngestion}</strong>
                </div>
              </div>
            </section>
          </Spin>
        </main>
      </section>
    </div>
  );
};

export default MetadataEngineOverviewPage;
