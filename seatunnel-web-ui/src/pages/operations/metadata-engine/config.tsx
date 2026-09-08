import { ApiOutlined, ArrowLeftOutlined, CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { Button, Form, Input, InputNumber, Space, Switch, Typography, message } from 'antd';
import { history } from '@umijs/max';
import React, { useEffect, useState } from 'react';
import {
  fetchOpenMetadataServer,
  saveOpenMetadataServer,
  testOpenMetadataServer,
  type MetadataApiResponse,
  type OpenMetadataServerConfig,
  type OpenMetadataServerPayload,
} from '@/services/metadata/server';
import './index.less';

const { Paragraph, Text, Title } = Typography;

type FormValues = {
  enabled: boolean;
  baseUrl: string;
  token?: string;
  connectTimeoutMs: number;
  readTimeoutMs: number;
  kingbaseTunnelHost?: string;
  kingbaseTunnelPort?: number;
};

type TestResult = {
  status: 'success' | 'error';
  message: string;
};

const responseError = (response: MetadataApiResponse<unknown>, fallback: string) =>
  response.msg || response.message || fallback;

const toPayload = (values: FormValues): OpenMetadataServerPayload => ({
  enabled: values.enabled,
  baseUrl: values.baseUrl?.trim(),
  token: values.token?.trim() || undefined,
  connectTimeoutMs: values.connectTimeoutMs,
  readTimeoutMs: values.readTimeoutMs,
  kingbaseTunnelHost: values.kingbaseTunnelHost?.trim() || undefined,
  kingbaseTunnelPort: values.kingbaseTunnelPort ?? 0,
});

const MetadataEngineConfigPage: React.FC = () => {
  const [form] = Form.useForm<FormValues>();
  const [config, setConfig] = useState<OpenMetadataServerConfig>();
  const [loading, setLoading] = useState(true);
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testResult, setTestResult] = useState<TestResult>();

  useEffect(() => {
    let active = true;
    const load = async () => {
      setLoading(true);
      try {
        const response = await fetchOpenMetadataServer();
        if (!active) return;
        if (response.code !== 0) {
          message.error(responseError(response, '读取探查引擎配置失败'));
          return;
        }
        const data = response.data;
        setConfig(data || undefined);
        form.setFieldsValue({
          enabled: Boolean(data?.enabled),
          baseUrl: data?.baseUrl || '',
          token: undefined,
          connectTimeoutMs: data?.connectTimeoutMs || 2000,
          readTimeoutMs: data?.readTimeoutMs || 10000,
          kingbaseTunnelHost: data?.kingbaseTunnelHost || undefined,
          kingbaseTunnelPort: data?.kingbaseTunnelPort || 0,
        });
      } catch (error) {
        if (active) message.error(error instanceof Error ? error.message : '读取探查引擎配置失败');
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => {
      active = false;
    };
  }, [form]);

  const handleTest = async () => {
    try {
      const values = await form.validateFields();
      if (!config?.tokenConfigured && !values.token?.trim()) {
        message.error('首次配置必须填写 Token');
        return;
      }
      setTesting(true);
      setTestResult(undefined);
      const response = await testOpenMetadataServer(toPayload(values));
      if (response.code !== 0) throw new Error(responseError(response, 'OpenMetadata 连接测试失败'));
      if (response.data?.connStatus === 'CONNECTED_SUCCESS') {
        setTestResult({ status: 'success', message: '连接成功：版本契约校验通过。' });
        message.success('OpenMetadata 连接测试成功');
      } else {
        setTestResult({
          status: 'error',
          message: response.data?.lastError || '连接失败，请检查 Base URL、Token 与网络。',
        });
        message.error('OpenMetadata 连接测试失败');
      }
    } catch (error) {
      if ((error as { errorFields?: unknown })?.errorFields) return;
      const errorMessage = error instanceof Error ? error.message : 'OpenMetadata 连接测试失败';
      setTestResult({ status: 'error', message: errorMessage });
      message.error(errorMessage);
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      if (!config?.tokenConfigured && !values.token?.trim()) {
        message.error('首次配置必须填写 Token');
        return;
      }
      setSaving(true);
      const response = await saveOpenMetadataServer(toPayload(values));
      if (response.code !== 0 || !response.data) {
        throw new Error(responseError(response, '保存探查引擎配置失败'));
      }
      setConfig(response.data);
      form.setFieldsValue({ token: undefined });
      message.success('探查引擎配置已保存');
      history.push('/operations/metadata-engine');
    } catch (error) {
      if ((error as { errorFields?: unknown })?.errorFields) return;
      message.error(error instanceof Error ? error.message : '保存探查引擎配置失败');
    } finally {
      setSaving(false);
    }
  };

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
                探查引擎连接配置
              </Title>
              <Paragraph className="metadata-engine-subtitle">
                Base URL 必须以 /api 结尾。Token 不会回显；已配置时留空表示保持原 Token。
              </Paragraph>
            </div>
          </div>
          <Button icon={<ArrowLeftOutlined />} onClick={() => history.push('/operations/metadata-engine')}>
            返回
          </Button>
        </div>

        <div className="metadata-engine-panel">
          <Form
            form={form}
            layout="vertical"
            disabled={loading}
            initialValues={{
              enabled: false,
              connectTimeoutMs: 2000,
              readTimeoutMs: 10000,
              kingbaseTunnelPort: 0,
            }}
          >
            <Form.Item name="enabled" label="启用 OpenMetadata 集成" valuePropName="checked">
              <Switch checkedChildren="启用" unCheckedChildren="禁用" />
            </Form.Item>
            <Form.Item
              name="baseUrl"
              label="Base URL"
              rules={[
                { required: true, message: '请输入 OpenMetadata Base URL' },
                {
                  validator: async (_, value) => {
                    const text = String(value || '').trim();
                    if (!text) return;
                    if (text.includes(':8082') || text.includes('/airflow')) {
                      throw new Error('禁止使用 Airflow 端点');
                    }
                    const normalized = text.endsWith('/') ? text.slice(0, -1) : text;
                    if (!normalized.endsWith('/api')) {
                      throw new Error('Base URL 必须以 /api 结尾');
                    }
                  },
                },
              ]}
            >
              <Input placeholder="http://openmetadata:8585/api" />
            </Form.Item>
            <Form.Item
              name="token"
              label="Bot Token"
              extra={
                config?.tokenConfigured
                  ? '已配置 Token。留空保存将保留现有 Token。'
                  : '首次配置必须填写 Bot JWT。'
              }
            >
              <Input.Password
                placeholder={config?.tokenConfigured ? '已配置，留空保留' : '请输入 Bot JWT'}
                autoComplete="new-password"
              />
            </Form.Item>
            <Space size={16} style={{ display: 'flex' }} wrap>
              <Form.Item
                name="connectTimeoutMs"
                label="连接超时 (ms)"
                rules={[{ required: true, message: '请输入连接超时' }]}
              >
                <InputNumber min={200} max={120000} style={{ width: 180 }} />
              </Form.Item>
              <Form.Item
                name="readTimeoutMs"
                label="读超时 (ms)"
                rules={[{ required: true, message: '请输入读超时' }]}
              >
                <InputNumber min={500} max={300000} style={{ width: 180 }} />
              </Form.Item>
            </Space>
            <Form.Item name="kingbaseTunnelHost" label="Kingbase SSH 隧道主机（可选）">
              <Input placeholder="留空表示不使用隧道" />
            </Form.Item>
            <Form.Item name="kingbaseTunnelPort" label="Kingbase SSH 隧道端口（可选）">
              <InputNumber min={0} max={65535} style={{ width: 180 }} />
            </Form.Item>
            <Text type="secondary">
              期望版本固定为 Server {config?.expectedServerVersion || '1.12.10'} / Ingestion{' '}
              {config?.expectedIngestionPatch || '1.12.10.0'}，不可在此修改。
            </Text>
          </Form>

          {testResult ? (
            <div
              className={`metadata-engine-test-result metadata-engine-test-result--${testResult.status}`}
            >
              {testResult.status === 'success' ? <CheckCircleOutlined /> : <CloseCircleOutlined />}{' '}
              {testResult.message}
            </div>
          ) : null}

          <div className="metadata-engine-actions">
            <Button onClick={() => void handleTest()} loading={testing} disabled={saving || loading}>
              连接测试
            </Button>
            <Button type="primary" onClick={() => void handleSave()} loading={saving} disabled={testing || loading}>
              保存配置
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default MetadataEngineConfigPage;
