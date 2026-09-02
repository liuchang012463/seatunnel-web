import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloudUploadOutlined,
  DatabaseOutlined,
  LinkOutlined,
  SafetyCertificateOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { Button, Card, Form, Input, Space, Table, Tag, Typography, Upload, message } from 'antd';
import type { UploadProps } from 'antd';
import { history } from '@umijs/max';
import React, { useEffect, useMemo, useState } from 'react';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import DynamicDataSourceForm from '@/pages/data-source/components/DynamicDataSourceForm';
import type { DataSourceFormValues, DataSourceOperateType } from '@/pages/data-source/types';
import {
  fetchLakeJdbcDrivers,
  fetchLakeWarehouse,
  registerLakeJdbcDriver,
  saveLakeWarehouse,
  testLakeWarehouse,
} from '@/services/lake';
import type { LakeApiResponse, LakeJdbcDriver, LakeWarehouseConfig } from '@/services/lake';
import HttpUtils from '@/utils/HttpUtils';
import './index.less';

const { Paragraph, Text, Title } = Typography;
const DEFAULT_DRIVER_CLASS = 'com.mysql.cj.jdbc.Driver';

interface DorisConnectionValues extends Record<string, unknown> {
  fenodes?: string;
  queryPort?: string | number;
  database?: string;
  user?: string;
  password?: string;
  driverLocation?: string;
  other?: Array<{ key?: string; value?: string }>;
}

interface LakeBaseFormValues extends Pick<DataSourceFormValues, 'name'> {
  name: string;
}

const responseError = (response: LakeApiResponse<unknown>, fallback: string) =>
  response.msg || response.message || fallback;

const getHostFromFenodes = (fenodes?: string) => {
  const firstNode = String(fenodes || '')
    .split(',')[0]
    .trim()
    .replace(/^https?:\/\//i, '')
    .replace(/\/.*$/, '');
  return firstNode.replace(/:\d+$/, '') || '127.0.0.1';
};

const parseExistingConnection = (config?: LakeWarehouseConfig): DorisConnectionValues | undefined => {
  if (!config) return undefined;

  const match = config.jdbcUrl?.match(/^jdbc:[^:]+:\/\/([^/]+)\/([^?]*)?(?:\?(.*))?$/i);
  const authority = match?.[1] || '';
  const host = authority.replace(/:\d+$/, '') || '127.0.0.1';
  const queryPort = authority.match(/:(\d+)$/)?.[1] || '9030';
  const other: Array<{ key: string; value: string }> = [];
  if (match?.[3]) {
    new URLSearchParams(match[3]).forEach((value, key) => other.push({ key, value }));
  }

  return {
    fenodes: `${host}:8030`,
    queryPort: Number(queryPort),
    database: match?.[2] ? decodeURIComponent(match[2]) : '',
    user: config.username,
    driverLocation: config.driverLocation,
    other,
  };
};

const buildJdbcUrl = (values: DorisConnectionValues) => {
  const host = getHostFromFenodes(values.fenodes);
  const queryPort = String(values.queryPort || '9030').trim();
  const database = String(values.database || '').trim();
  const params = new URLSearchParams();

  if (Array.isArray(values.other)) {
    values.other.forEach((item) => {
      const key = String(item?.key || '').trim();
      if (key) params.set(key, String(item?.value || '').trim());
    });
  }

  const query = params.toString();
  return `jdbc:mysql://${host}:${queryPort}/${database}${query ? `?${query}` : ''}`;
};

const toWarehousePayload = (
  base: LakeBaseFormValues,
  connection: DorisConnectionValues,
  currentConfig?: LakeWarehouseConfig,
) => ({
  name: base.name?.trim() || 'Doris 数据湖',
  jdbcUrl: buildJdbcUrl(connection),
  username: String(connection.user || '').trim(),
  password: String(connection.password || '').trim() || undefined,
  driverClass: currentConfig?.driverClass || DEFAULT_DRIVER_CLASS,
  driverLocation: String(connection.driverLocation || '').trim(),
  driverSha256: currentConfig?.driverSha256,
});

const DorisConfigPage: React.FC = () => {
  const [baseForm] = Form.useForm<DataSourceFormValues>();
  const [connectionForm] = Form.useForm();
  const [config, setConfig] = useState<LakeWarehouseConfig>();
  const [drivers, setDrivers] = useState<LakeJdbcDriver[]>([]);
  const [loading, setLoading] = useState(true);
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [registering, setRegistering] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [testMessage, setTestMessage] = useState<string>();

  useEffect(() => {
    let active = true;
    const load = async () => {
      setLoading(true);
      try {
        const [configResponse, driverResponse] = await Promise.all([
          fetchLakeWarehouse(),
          fetchLakeJdbcDrivers(),
        ]);
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

        if (driverResponse.code === 0) {
          setDrivers(Array.isArray(driverResponse.data) ? driverResponse.data : []);
        } else {
          message.error(responseError(driverResponse, '读取 JDBC 驱动列表失败'));
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

  const refreshDrivers = async () => {
    const response = await fetchLakeJdbcDrivers();
    if (response.code === 0) setDrivers(Array.isArray(response.data) ? response.data : []);
  };

  const readForms = async () => {
    const base = await baseForm.validateFields();
    const connection = await connectionForm.validateFields() as DorisConnectionValues;
    return { base, connection };
  };

  const handleTest = async () => {
    try {
      const { base, connection } = await readForms();
      setTesting(true);
      setTestMessage(undefined);
      const response = await testLakeWarehouse(toWarehousePayload(base, connection, config));
      if (response.code !== 0) throw new Error(responseError(response, 'Doris 连接测试失败'));

      if (response.data?.connStatus === 'CONNECTED_SUCCESS') {
        setTestMessage('连接成功：Doris FE 已接受 JDBC 连接。');
        message.success('Doris 连接测试成功');
      } else {
        setTestMessage(response.data?.lastError || '连接失败，请检查 FE 地址、查询端口、账号和驱动。');
        message.error('Doris 连接测试失败');
      }
    } catch (error) {
      if ((error as { errorFields?: unknown })?.errorFields) return;
      message.error(error instanceof Error ? error.message : 'Doris 连接测试失败');
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async () => {
    try {
      const { base, connection } = await readForms();
      setSaving(true);
      const response = await saveLakeWarehouse(toWarehousePayload(base, connection, config));
      if (response.code !== 0 || !response.data) {
        throw new Error(responseError(response, '保存数据湖配置失败'));
      }
      message.success('Doris 数据湖配置已保存');
      history.push('/lake/warehouse');
    } catch (error) {
      if ((error as { errorFields?: unknown })?.errorFields) return;
      message.error(error instanceof Error ? error.message : '保存数据湖配置失败');
    } finally {
      setSaving(false);
    }
  };

  const registerCurrentDriver = async () => {
    const driverLocation = String(connectionForm.getFieldValue('driverLocation') || '').trim();
    if (!driverLocation) {
      message.warning('请先填写或上传 Doris JDBC 驱动');
      return;
    }

    setRegistering(true);
    try {
      const response = await registerLakeJdbcDriver({
        adapter: 'MYSQL',
        fileName: driverLocation.split('/').pop(),
        driverLocation,
        driverClass: config?.driverClass || DEFAULT_DRIVER_CLASS,
      });
      if (response.code !== 0 || !response.data) {
        throw new Error(responseError(response, '驱动注册失败'));
      }
      message.success('Doris JDBC 驱动已校验');
      await refreshDrivers();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '驱动注册失败');
    } finally {
      setRegistering(false);
    }
  };

  const uploadProps: UploadProps = {
    accept: '.jar',
    showUploadList: false,
    beforeUpload: (file) => {
      if (!file.name.toLowerCase().endsWith('.jar')) {
        message.error('只允许上传 .jar 驱动文件');
        return Upload.LIST_IGNORE;
      }
      return true;
    },
    customRequest: async ({ file, onSuccess, onError }) => {
      const formData = new FormData();
      formData.append('file', file as File);
      formData.append('adapter', 'MYSQL');
      setUploading(true);
      try {
        const response = await HttpUtils.postForm<LakeJdbcDriver>('/api/v1/lake/warehouse/drivers/upload', formData);
        if (response.code !== 0) throw new Error(responseError(response, '驱动上传失败'));
        if (response.data?.driverLocation) {
          connectionForm.setFieldValue('driverLocation', response.data.driverLocation);
        }
        message.success('Doris JDBC 驱动上传并校验成功');
        onSuccess?.(response.data);
        await refreshDrivers();
      } catch (error) {
        onError?.(error as Error);
        message.error(error instanceof Error ? error.message : '驱动上传失败');
      } finally {
        setUploading(false);
      }
    },
  };

  return (
    <div className="lake-warehouse-config-page">
      <header className="lake-config-page-header">
        <Button icon={<ArrowLeftOutlined />} onClick={() => history.push('/lake/warehouse')}>
          返回数据湖管理
        </Button>
        <div className="lake-config-page-heading">
          <div className="lake-config-page-icon">
            <DatabaseIcons dbType="DORIS" width="24" height="24" />
          </div>
          <div>
            <Title level={1}>Doris 配置</Title>
            <Paragraph>配置数据湖唯一的 Doris ODS 连接，保存后自动同步湖侧只读投影。</Paragraph>
          </div>
        </div>
      </header>

      <div className="lake-config-layout">
        <main className="lake-config-main">
          <div className="lake-config-section-heading">
            <div>
              <Text className="lake-warehouse-kicker">DORIS CONNECTION</Text>
              <Title level={2}>连接参数</Title>
              <Paragraph>表单字段与数据源管理中的 Doris 连接器保持一致，保存后会同步为湖侧只读数据源。</Paragraph>
            </div>
            <Tag color={config?.configured ? 'success' : 'default'}>
              {config?.configured ? '已配置' : '待配置'}
            </Tag>
          </div>

          <Form form={baseForm} layout="vertical" className="lake-config-name-form">
            <Form.Item
              name="name"
              label="连接名称"
              rules={[{ required: true, message: '请输入连接名称' }]}
            >
              <Input placeholder="例如：生产 Doris 数据湖" maxLength={100} />
            </Form.Item>
          </Form>

          <div className="lake-doris-form-shell" aria-busy={loading}>
            <DynamicDataSourceForm
              key={`doris-${config?.configVersion || 'new'}`}
              dbType="DORIS"
              form={baseForm}
              configForm={connectionForm}
              operateType={'CREATE' as DataSourceOperateType}
              hideBaseFields
              allowExistingPassword={Boolean(config?.passwordConfigured)}
              initialConfig={initialConnection}
            />
          </div>

          {testMessage ? (
            <div className="lake-config-test-result">
              <CheckCircleOutlined />
              <span>{testMessage}</span>
            </div>
          ) : null}

          <div className="lake-config-action-bar">
            <Text type="secondary">密码仅用于服务端连接测试和加密保存，不会回显。</Text>
            <Space>
              <Button icon={<LinkOutlined />} loading={testing} onClick={() => void handleTest()}>
                连接测试
              </Button>
              <Button type="primary" icon={<SafetyCertificateOutlined />} loading={saving} onClick={() => void handleSave()}>
                测试并保存
              </Button>
            </Space>
          </div>
        </main>

        <aside className="lake-config-aside">
          <Card className="lake-config-info-card" variant="borderless">
            <div className="lake-config-aside-title">
              <span className="lake-config-aside-icon"><DatabaseOutlined /></span>
              <div>
                <Title level={3}>配置范围</Title>
                <Text type="secondary">湖侧连接的唯一入口</Text>
              </div>
            </div>
            <ul className="lake-config-check-list">
              <li><span className="lake-config-check-dot" />FE 节点地址用于识别 Doris 集群</li>
              <li><span className="lake-config-check-dot" />查询端口使用 MySQL 协议，默认 9030</li>
              <li><span className="lake-config-check-dot" />保存后自动生成湖侧只读投影</li>
            </ul>
          </Card>

          <Card
            className="lake-config-driver-card"
            variant="borderless"
            title={<span className="lake-config-card-title"><CloudUploadOutlined /> JDBC 驱动</span>}
            extra={
              <Upload {...uploadProps}>
                <Button size="small" icon={<UploadOutlined />} loading={uploading}>上传</Button>
              </Upload>
            }
          >
            <Paragraph type="secondary">只读取本机 `jdbc-drivers` 目录中的已校验 jar。</Paragraph>
            <Table<LakeJdbcDriver>
              rowKey={(record) => String(record.id || record.driverLocation)}
              size="small"
              pagination={false}
              dataSource={drivers}
              locale={{ emptyText: '暂无已校验驱动' }}
              columns={[
                { title: '文件', dataIndex: 'fileName', ellipsis: true },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 82,
                  render: (value: string, record) => (
                    <Tag color={record.verified ? 'success' : 'warning'}>{record.verified ? '已校验' : value || '待校验'}</Tag>
                  ),
                },
              ]}
            />
            <Button block className="lake-register-driver-button" loading={registering} onClick={() => void registerCurrentDriver()}>
              注册当前表单中的驱动
            </Button>
          </Card>
        </aside>
      </div>
    </div>
  );
};

export default DorisConfigPage;
