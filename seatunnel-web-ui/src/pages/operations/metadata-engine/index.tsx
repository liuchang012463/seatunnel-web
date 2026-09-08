import {
  ApiOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Button, Spin, Tag, Typography, message } from 'antd';
import { history } from '@umijs/max';
import React, { useCallback, useEffect, useState } from 'react';
import {
  fetchOpenMetadataServer,
  type MetadataApiResponse,
  type OpenMetadataServerConfig,
} from '@/services/metadata/server';
import './index.less';

const { Paragraph, Text, Title } = Typography;

const responseError = (response: MetadataApiResponse<unknown>, fallback: string) =>
  response.msg || response.message || fallback;

const statusTag = (config?: OpenMetadataServerConfig) => {
  if (!config?.configured) {
    return <Tag>待配置</Tag>;
  }
  if (!config.enabled) {
    return <Tag>已禁用</Tag>;
  }
  if (config.connStatus === 'CONNECTED_SUCCESS') {
    return <Tag color="success">已连接</Tag>;
  }
  if (config.connStatus === 'CONNECTED_FAILED') {
    return <Tag color="error">连接失败</Tag>;
  }
  return <Tag>已配置</Tag>;
};

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

  const health = config?.health;

  return (
    <div className="metadata-engine-page">
      <div className="metadata-engine-shell">
        <div className="metadata-engine-header">
          <div className="metadata-engine-heading">
            <div className="metadata-engine-icon">
              <ApiOutlined />
            </div>
            <div>
              <Title level={3} className="metadata-engine-title">
                探查引擎管理
              </Title>
              <Paragraph className="metadata-engine-subtitle">
                配置 OpenMetadata 控制面连接。连接参数保存在本系统，不再依赖进程环境变量作为运行时来源。
              </Paragraph>
            </div>
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void load()} disabled={loading}>
            刷新
          </Button>
        </div>

        <Spin spinning={loading}>
          <div className="metadata-engine-panel">
            <div className="metadata-engine-status">
              {statusTag(config)}
              <Text type="secondary">
                固定版本契约：Server {config?.expectedServerVersion || '1.12.10'} / Ingestion{' '}
                {config?.expectedIngestionPatch || '1.12.10.0'}
              </Text>
            </div>
            {config?.lastError ? (
              <div className="metadata-engine-error">{config.lastError}</div>
            ) : null}
            <div className="metadata-engine-metrics">
              <div className="metadata-engine-metric">
                <span className="metadata-engine-metric-label">启用状态</span>
                <div className="metadata-engine-metric-value">{config?.enabled ? '已启用' : '未启用'}</div>
              </div>
              <div className="metadata-engine-metric">
                <span className="metadata-engine-metric-label">Base URL</span>
                <div className="metadata-engine-metric-value">{config?.baseUrl || '--'}</div>
              </div>
              <div className="metadata-engine-metric">
                <span className="metadata-engine-metric-label">Token</span>
                <div className="metadata-engine-metric-value">
                  {config?.tokenConfigured ? '已配置' : '未配置'}
                </div>
              </div>
              <div className="metadata-engine-metric">
                <span className="metadata-engine-metric-label">配置版本</span>
                <div className="metadata-engine-metric-value">{config?.configVersion ?? '--'}</div>
              </div>
            </div>
            <div className="metadata-engine-actions">
              <Button
                type="primary"
                icon={<SettingOutlined />}
                onClick={() => history.push('/operations/metadata-engine/config')}
              >
                编辑连接配置
              </Button>
            </div>
          </div>

          <div className="metadata-engine-panel">
            <Title level={5} className="metadata-engine-panel-title">
              健康摘要
            </Title>
            <div className="metadata-engine-metrics">
              <div className="metadata-engine-metric">
                <span className="metadata-engine-metric-label">OpenMetadata</span>
                <div className="metadata-engine-metric-value">{health?.openMetadata || '--'}</div>
              </div>
              <div className="metadata-engine-metric">
                <span className="metadata-engine-metric-label">Orchestrator</span>
                <div className="metadata-engine-metric-value">{health?.orchestrator || '--'}</div>
              </div>
              <div className="metadata-engine-metric">
                <span className="metadata-engine-metric-label">实际 Server 版本</span>
                <div className="metadata-engine-metric-value">{health?.version || '--'}</div>
              </div>
              <div className="metadata-engine-metric">
                <span className="metadata-engine-metric-label">实际 Ingestion 版本</span>
                <div className="metadata-engine-metric-value">{health?.ingestionVersion || '--'}</div>
              </div>
              <div className="metadata-engine-metric">
                <span className="metadata-engine-metric-label">版本兼容</span>
                <div className="metadata-engine-metric-value">
                  {health?.versionCompatible ? (
                    <>
                      <CheckCircleOutlined /> 兼容
                    </>
                  ) : (
                    <>
                      <CloseCircleOutlined /> 不兼容/未知
                    </>
                  )}
                </div>
              </div>
            </div>
          </div>
        </Spin>
      </div>
    </div>
  );
};

export default MetadataEngineOverviewPage;
