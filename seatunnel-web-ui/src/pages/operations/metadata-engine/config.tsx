import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  LinkOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Button, Checkbox, Form, Input, InputNumber, Select, Space, Tag, Typography, message } from 'antd';
import { useFormSave } from '@/pages/common/hooks/useFormSave';
import { returnToTrigger } from '@/pages/common/utils/returnFocus';
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

const { Text, Title } = Typography;

type FormValues = {
  baseUrl: string;
  token?: string;
  defaultDomainFqn?: string;
  connectTimeoutMs: number;
  readTimeoutMs: number;
  rebuildBindings: boolean;
};

type TestResult = {
  status: 'success' | 'error';
  message: string;
};

const responseError = (response: MetadataApiResponse<unknown> | undefined, fallback: string) =>
  response?.msg || response?.message || fallback;

const toPayload = (values: FormValues): OpenMetadataServerPayload => ({
  baseUrl: values.baseUrl?.trim(),
  token: values.token?.trim() || undefined,
  defaultDomainFqn: values.defaultDomainFqn?.trim() ?? '',
  connectTimeoutMs: values.connectTimeoutMs,
  readTimeoutMs: values.readTimeoutMs,
  rebuildBindings: values.rebuildBindings,
});

const MetadataEngineConfigPage: React.FC = () => {
  const [form] = Form.useForm<FormValues>();
  const [config, setConfig] = useState<OpenMetadataServerConfig>();
  const [domainInfo, setDomainInfo] = useState<OpenMetadataServerConfig>();
  const [loading, setLoading] = useState(true);
  const [testing, setTesting] = useState(false);
  const submission = useFormSave('探查引擎配置');
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
        setDomainInfo(data || undefined);
        form.setFieldsValue({
          baseUrl: data?.baseUrl || '',
          token: undefined,
          // Select treats an empty string as a selection and hides its placeholder.
          defaultDomainFqn: data?.defaultDomainFqn || undefined,
          connectTimeoutMs: data?.connectTimeoutMs || 10000,
          readTimeoutMs: data?.readTimeoutMs || 60000,
          rebuildBindings: false,
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
      setTesting(true);
      setTestResult(undefined);
      const response = await testOpenMetadataServer(toPayload(values));
      if (response.code !== 0) throw new Error(responseError(response, 'OpenMetadata 连接测试失败'));
      const testedConfig = response.data;
      if (testedConfig) {
        setDomainInfo(testedConfig);
      }
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

  const goBack = () => returnToTrigger('/operations/metadata-engine');
  const handleSave = () =>
    submission.save(async () => {
      const values = await form.validateFields();
      const response = await saveOpenMetadataServer(toPayload(values), { skipErrorHandler: true });
      if (response?.code !== 0 || !response.data) {
        throw new Error(responseError(response, '保存探查引擎配置失败'));
      }
      setDomainInfo(response.data);
      setConfig(response.data);
      form.setFieldsValue({ token: undefined });
      return '探查引擎配置已保存';
    }, goBack);

  const expectedServer = config?.expectedServerVersion || '2.0.4';
  const expectedIngestion = config?.expectedIngestionPatch || '2.0.4.0';
  const botDomainFqns = domainInfo?.botDomainFqns || [];
  const availableDomainFqns = domainInfo?.availableDomainFqns || [];
  const domainOptions = availableDomainFqns.length > 0 ? availableDomainFqns : botDomainFqns;
  const storedDomain = domainInfo?.defaultDomainFqn;
  const visibleDomainOptions = storedDomain && !domainOptions.includes(storedDomain)
    ? [...domainOptions, storedDomain]
    : domainOptions;
  const domainHelp = !domainInfo?.configured
    ? '连接测试通过后会读取可选 Domain。'
    : domainInfo.domainListAvailable
      ? '选择后，SeaTunnel Web 仅读取和修改该 Domain 的 OpenMetadata 资产；留空表示不限制 Domain。新建数据源服务会自动归入所选 Domain。'
      : 'Domain 列表读取失败。留空表示不限制 Domain；如需选择读写范围，请先恢复 Domain 列表读取。';
  const botDomainLookupLabel = !domainInfo?.configured
    ? '连接测试通过后检查'
    : domainInfo.botDomainLookupAvailable ? '读取成功' : '读取失败';
  const domainListLookupLabel = !domainInfo?.configured
    ? '连接测试通过后检查'
    : domainInfo.domainListAvailable ? '读取成功' : '读取失败';

  return (
    <div className="meta-engine-config-page">
      <header className="meta-engine-config-page-header">
        <Button icon={<ArrowLeftOutlined />} disabled={submission.locked} onClick={goBack}>
          返回探查引擎管理
        </Button>
      </header>

      <div className="meta-engine-config-layout">
        <main className="meta-engine-config-main">
          <div className="meta-engine-config-section-heading">
            <Title level={2}>OpenMetadata 连接参数</Title>
            <Tag color={config?.configured ? 'success' : 'default'}>
              {config?.configured ? '已配置' : '待配置'}
            </Tag>
          </div>

          <Form
            form={form}
            layout="vertical"
            disabled={loading || submission.locked}
            className="meta-engine-config-form"
            initialValues={{
              connectTimeoutMs: 10000,
              readTimeoutMs: 60000,
            }}
          >
            <Form.Item
              name="baseUrl"
              label="Base URL"
              extra="地址必须以 /api 结尾。"
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
              <Input placeholder="http://<om-2.0.4-host>:<port>/api" />
            </Form.Item>
            <Form.Item
              name="token"
              label="Bot Token"
              rules={[{ required: !config?.tokenConfigured, whitespace: true, message: '首次配置必须填写 Bot JWT' }]}
              extra={
                config?.tokenConfigured
                  ? '已配置 Token。凭据不会回显，留空保存将保留现有 Token。'
                  : '首次配置必须填写 Bot JWT。'
              }
            >
              <Input.Password
                placeholder={config?.tokenConfigured ? '已配置，留空保留' : '请输入 Bot JWT'}
                autoComplete="new-password"
              />
            </Form.Item>
            <Form.Item
              name="defaultDomainFqn"
              label="Domain 读写范围"
              extra={(
                <Space direction="vertical" size={2}>
                  <Text type="secondary">
                    Bot Domain 信息（仅供参考）：{botDomainLookupLabel}
                  </Text>
                  <Text type={domainInfo?.configured && !domainInfo.domainListAvailable ? 'danger' : 'secondary'}>
                    可见 Domain 列表：{domainListLookupLabel}
                  </Text>
                  <Text>{domainHelp}</Text>
                </Space>
              )}
            >
              <Select
                allowClear
                placeholder="空表示全域"
                options={visibleDomainOptions.map((domain) => ({ value: domain, label: domain }))}
              />
            </Form.Item>
            <Form.Item name="rebuildBindings" valuePropName="checked" initialValue={false}>
              <Checkbox>
                此地址对应一个新的 OpenMetadata 实例，重建现有数据源绑定
              </Checkbox>
            </Form.Item>
            <Text type="secondary">
              勾选后，保存会在新 OM 中重新创建 ACTIVE 数据源服务和探查管道；删除中的数据源仍按删除状态清理。轮换 Token 时必须勾选：只保存新 Token 不会更新既有 pipeline 内嵌的旧 Token，会导致 Airflow Unauthorized。
            </Text>
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
            <span role="status" aria-atomic="true" data-phase={submission.feedback?.phase}>
              {submission.feedback?.text}
            </span>
            <Space>
              <Button icon={<LinkOutlined />} loading={testing} disabled={submission.locked || loading} onClick={() => void handleTest()}>
                连接测试
              </Button>
              <Button
                type="primary"
                icon={<SafetyCertificateOutlined />}
                loading={submission.saving}
                disabled={testing || loading || submission.locked}
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
