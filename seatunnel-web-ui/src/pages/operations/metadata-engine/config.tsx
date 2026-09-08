import {
  ApiOutlined,
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  LinkOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Button, Form, Input, InputNumber, Space, Tag, Typography, message } from 'antd';
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
  baseUrl: string;
  token?: string;
  connectTimeoutMs: number;
  readTimeoutMs: number;
};

type TestResult = {
  status: 'success' | 'error';
  message: string;
};

const responseError = (response: MetadataApiResponse<unknown>, fallback: string) =>
  response.msg || response.message || fallback;

const toPayload = (values: FormValues): OpenMetadataServerPayload => ({
  baseUrl: values.baseUrl?.trim(),
  token: values.token?.trim() || undefined,
  connectTimeoutMs: values.connectTimeoutMs,
  readTimeoutMs: values.readTimeoutMs,
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
          baseUrl: data?.baseUrl || '',
          token: undefined,
          connectTimeoutMs: data?.connectTimeoutMs || 2000,
          readTimeoutMs: data?.readTimeoutMs || 10000,
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

  const ensureToken = (values: FormValues) => {
    if (!config?.tokenConfigured && !values.token?.trim()) {
      message.error('首次配置必须填写 Token');
      return false;
    }
    return true;
  };

  const handleTest = async () => {
    try {
      const values = await form.validateFields();
      if (!ensureToken(values)) return;
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
      if (!ensureToken(values)) return;
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

  const expectedServer = config?.expectedServerVersion || '1.12.10';
  const expectedIngestion = config?.expectedIngestionPatch || '1.12.10.0';

  return (
    <div className="meta-engine-config-page">
      <header className="meta-engine-config-page-header">
        <Button icon={<ArrowLeftOutlined />} onClick={() => history.push('/operations/metadata-engine')}>
          返回探查引擎管理
        </Button>
        <div className="meta-engine-config-page-heading">
          <div className="meta-engine-config-page-icon">
            <ApiOutlined />
          </div>
          <div>
            <Title level={1}>OpenMetadata 配置</Title>
            <Paragraph>
              Base URL 必须以 /api 结尾。Token 不会回显；已配置时留空表示保持原 Token。
            </Paragraph>
          </div>
        </div>
      </header>

      <div className="meta-engine-config-layout">
        <main className="meta-engine-config-main">
          <div className="meta-engine-config-section-heading">
            <div>
              <Text className="meta-engine-kicker">OPENMETADATA CONNECTION</Text>
              <Title level={2}>连接参数</Title>
              <Paragraph>
                配置探查控制面唯一连接。保存成功后集成自动生效，无需额外启用开关。
              </Paragraph>
            </div>
            <Tag color={config?.configured ? 'success' : 'default'}>
              {config?.configured ? '已配置' : '待配置'}
            </Tag>
          </div>

          <Form
            form={form}
            layout="vertical"
            disabled={loading}
            className="meta-engine-config-form"
            initialValues={{
              connectTimeoutMs: 2000,
              readTimeoutMs: 10000,
            }}
          >
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
            <div className="meta-engine-timeout-row">
              <Form.Item
                name="connectTimeoutMs"
                label="连接超时 (ms)"
                rules={[{ required: true, message: '请输入连接超时' }]}
              >
                <InputNumber min={200} max={120000} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="readTimeoutMs"
                label="读超时 (ms)"
                rules={[{ required: true, message: '请输入读超时' }]}
              >
                <InputNumber min={500} max={300000} style={{ width: '100%' }} />
              </Form.Item>
            </div>
            <Text type="secondary" className="meta-engine-version-note">
              期望版本固定为 Server {expectedServer} / Ingestion {expectedIngestion}，不可在此修改。
            </Text>
          </Form>

          {testResult ? (
            <div
              className={`meta-engine-config-test-result meta-engine-config-test-result--${testResult.status}`}
              role={testResult.status === 'error' ? 'alert' : 'status'}
            >
              {testResult.status === 'success' ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
              <span>{testResult.message}</span>
            </div>
          ) : null}

          <div className="meta-engine-config-action-bar">
            <Text type="secondary">Token 仅用于服务端连接测试与保存，不会回显到页面。</Text>
            <Space>
              <Button icon={<LinkOutlined />} loading={testing} disabled={saving || loading} onClick={() => void handleTest()}>
                连接测试
              </Button>
              <Button
                type="primary"
                icon={<SafetyCertificateOutlined />}
                loading={saving}
                disabled={testing || loading}
                onClick={() => void handleSave()}
              >
                保存
              </Button>
            </Space>
          </div>
        </main>
      </div>
    </div>
  );
};

export default MetadataEngineConfigPage;
