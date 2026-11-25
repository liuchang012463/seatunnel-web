import { useFormSave } from '@/pages/common/hooks/useFormSave';
import { returnToTrigger } from '@/pages/common/utils/returnFocus';
import { textLengthRule } from '@/pages/common/utils/textLengthRule';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  LinkOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Button, ConfigProvider, Form, Input, Space, Tag, Typography, message } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import DynamicDataSourceForm from '@/pages/data-source/components/DynamicDataSourceForm';
import type { DataSourceFormValues, DataSourceOperateType } from '@/pages/data-source/types';
import { fetchLakeWarehouse, saveLakeWarehouse, testLakeWarehouse } from '@/services/lake';
import type { LakeApiResponse, LakeWarehouseConfig } from '@/services/lake';
import { buildWarehouseJdbcUrl, INITIAL_DORIS_PASSWORD_MESSAGE, isInitialDorisPasswordMissing } from './configUtils';
import type { DorisConnectionValues } from './configUtils';
import './index.less';

const { Text, Title } = Typography;
const DEFAULT_DRIVER_CLASS = 'com.mysql.cj.jdbc.Driver';
const EXCLUDED_CONNECTION_FIELDS = ['database'];
const NAME_MAX_LENGTH = 100;

interface LakeBaseFormValues extends Pick<DataSourceFormValues, 'name'> {
  name: string;
}

const responseError = (response: LakeApiResponse<unknown> | undefined, fallback: string) =>
  response?.msg || response?.message || fallback;

const parseExistingConnection = (config?: LakeWarehouseConfig): DorisConnectionValues | undefined => {
  if (!config) return undefined;

  const match = config.jdbcUrl?.match(/^jdbc:[^:]+:\/\/([^/?]+)(?:\/[^?]*)?(?:\?(.*))?$/i);
  const authority = match?.[1] || '';
  const host = authority.replace(/:\d+$/, '') || '127.0.0.1';
  const queryPort = authority.match(/:(\d+)$/)?.[1] || '9030';
  const other: Array<{ key: string; value: string }> = [];
  if (match?.[2]) {
    new URLSearchParams(match[2]).forEach((value, key) => other.push({ key, value }));
  }

  return {
    fenodes: `${host}:8030`,
    queryPort: Number(queryPort),
    user: config.username,
    driverLocation: config.driverLocation,
    other,
  };
};

const toWarehousePayload = (
  base: LakeBaseFormValues,
  connection: DorisConnectionValues,
  currentConfig?: LakeWarehouseConfig,
) => ({
  name: base.name?.trim() || 'Doris 数据湖',
  jdbcUrl: buildWarehouseJdbcUrl(connection),
  username: String(connection.user || '').trim(),
  password: String(connection.password || '').trim() || undefined,
  driverClass: currentConfig?.driverClass || DEFAULT_DRIVER_CLASS,
  driverLocation: String(connection.driverLocation || '').trim(),
  driverSha256: currentConfig?.driverSha256,
});

class InitialDorisPasswordError extends Error {}

type TestResult = {
  status: 'success' | 'error';
  message: string;
};

const DorisConfigPage: React.FC = () => {
  const [baseForm] = Form.useForm<DataSourceFormValues>();
  const [connectionForm] = Form.useForm();
  const [config, setConfig] = useState<LakeWarehouseConfig>();
  const [loading, setLoading] = useState(true);
  const [testing, setTesting] = useState(false);
  const submission = useFormSave('Doris 数据湖配置');
  const [testResult, setTestResult] = useState<TestResult>();

  useEffect(() => {
    let active = true;
    const load = async () => {
      setLoading(true);
      try {
        const configResponse = await fetchLakeWarehouse();
        if (!active) return;

        if (configResponse.code === 0) {
          setConfig(configResponse.data || undefined);
          if (configResponse.data) {
            baseForm.setFieldsValue({ name: configResponse.data.name || 'Doris 数据湖' });
          } else {
            baseForm.setFieldsValue({ name: 'Doris 数据湖' });
          }
        } else {
          message.error(responseError(configResponse, '读取数据湖配置失败'));
        }
      } catch (error) {
        if (active) message.error(error instanceof Error ? error.message : '读取数据湖配置失败');
      } finally {
        if (active) setLoading(false);
      }
    };

    void load();
    return () => {
      active = false;
    };
  }, [baseForm]);

  const initialConnection = useMemo(() => parseExistingConnection(config), [config]);

  const readForms = async () => {
    const base = await baseForm.validateFields();
    const rawConnection = connectionForm.getFieldsValue(true) as DorisConnectionValues;
    if (isInitialDorisPasswordMissing(config, rawConnection.password)) {
      connectionForm.setFields([{ name: 'password', errors: [INITIAL_DORIS_PASSWORD_MESSAGE] }]);
      throw new InitialDorisPasswordError(INITIAL_DORIS_PASSWORD_MESSAGE);
    }
    await connectionForm.validateFields();
    // Read the store after validation.  This keeps the submitted payload in
    // sync with the field value captured by the dynamic form, including a
    // value synchronized from a browser-managed password input on blur.
    const connection = connectionForm.getFieldsValue(true) as DorisConnectionValues;
    return { base, connection };
  };

  const handleTest = async () => {
    try {
      const { base, connection } = await readForms();
      setTesting(true);
      setTestResult(undefined);
      const response = await testLakeWarehouse(toWarehousePayload(base, connection, config));
      if (response.code !== 0) throw new Error(responseError(response, 'Doris 连接测试失败'));

      if (response.data?.connStatus === 'CONNECTED_SUCCESS') {
        setTestResult({ status: 'success', message: '连接成功：Doris FE 已接受 JDBC 连接。' });
        message.success('Doris 连接测试成功');
      } else {
        setTestResult({
          status: 'error',
          message: response.data?.lastError || '连接失败，请检查 FE 地址、查询端口、账号和驱动。',
        });
        message.error('Doris 连接测试失败');
      }
    } catch (error) {
      if (error instanceof InitialDorisPasswordError || (error as { errorFields?: unknown })?.errorFields) return;
      const errorMessage = error instanceof Error ? error.message : 'Doris 连接测试失败';
      setTestResult({ status: 'error', message: errorMessage });
      message.error(errorMessage);
    } finally {
      setTesting(false);
    }
  };

  const goBack = () => returnToTrigger('/lake/warehouse');
  const handleSave = () =>
    submission.save(async () => {
      const { base, connection } = await readForms();
      const response = await saveLakeWarehouse(toWarehousePayload(base, connection, config), { skipErrorHandler: true });
      if (response?.code !== 0 || !response.data) {
        throw new Error(responseError(response, '保存数据湖配置失败'));
      }
      return 'Doris 数据湖配置已保存';
    }, goBack);

  return (
    <div className="lake-warehouse-config-page">
      <header className="lake-config-page-header">
        <Button icon={<ArrowLeftOutlined />} disabled={submission.locked} onClick={goBack}>
          返回数据湖管理
        </Button>
      </header>

      <div className="lake-config-layout">
        <main className="lake-config-main">
          <div className="lake-config-section-heading">
            <Title level={2}>Doris 连接参数</Title>
            <Tag color={config?.configured ? 'success' : 'default'}>
              {config?.configured ? '已配置' : '待配置'}
            </Tag>
          </div>

          <Form name="lake-warehouse-config" form={baseForm} layout="vertical" disabled={loading || submission.locked} className="lake-config-name-form">
            <Form.Item
              name="name"
              label="连接名称"
              rules={[{ required: true, message: '请输入连接名称' }, textLengthRule(NAME_MAX_LENGTH)]}
            >
              <Input placeholder="例如：生产 Doris 数据湖" maxLength={NAME_MAX_LENGTH} showCount />
            </Form.Item>
          </Form>

          <div className="lake-doris-form-shell" aria-busy={loading}>
            <ConfigProvider componentDisabled={loading || submission.locked}>
              <DynamicDataSourceForm
                key={`doris-${config?.configVersion || 'new'}`}
                dbType="DORIS"
                form={baseForm}
                configForm={connectionForm}
                operateType={'CREATE' as DataSourceOperateType}
                hideBaseFields
                excludedConfigFields={EXCLUDED_CONNECTION_FIELDS}
                allowExistingPassword={Boolean(config?.passwordConfigured)}
                initialConfig={initialConnection}
              />
            </ConfigProvider>
            <Text type="secondary">
              密码仅用于连接测试和加密保存，不会回显。连接无需绑定默认数据库，业务库表在物理入湖后自动纳管。
            </Text>
          </div>

          {testResult ? (
            <div
              className={`lake-config-test-result lake-config-test-result--${testResult.status}`}
              role={testResult.status === 'error' ? 'alert' : 'status'}
            >
              {testResult.status === 'success' ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
              <span>{testResult.message}</span>
            </div>
          ) : null}

          <div className="lake-config-action-bar">
            <span role="status" aria-atomic="true" data-phase={submission.feedback?.phase}>
              {submission.feedback?.text}
            </span>
            <Space>
              <Button icon={<LinkOutlined />} loading={testing} disabled={loading || submission.locked} onClick={() => void handleTest()}>
                连接测试
              </Button>
              <Button type="primary" icon={<SafetyCertificateOutlined />} loading={submission.saving} disabled={loading || testing || submission.locked} onClick={() => void handleSave()}>
                保存
              </Button>
            </Space>
          </div>
        </main>
      </div>
    </div>
  );
};

export default DorisConfigPage;
