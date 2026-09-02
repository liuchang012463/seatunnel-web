import {
  CheckCircleOutlined,
  CloudServerOutlined,
  LinkOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
  message,
} from 'antd';
import type { UploadProps } from 'antd';
import { history } from '@umijs/max';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  fetchLakeJdbcDrivers,
  fetchLakeWarehouse,
  registerLakeJdbcDriver,
  saveLakeWarehouse,
  testLakeWarehouse,
} from '@/services/lake';
import { fetchDataSourceAll } from '@/pages/data-source/service';
import type { DataSourceRecord } from '@/pages/data-source/types';
import HttpUtils from '@/utils/HttpUtils';
import type { LakeApiResponse, LakeJdbcDriver, LakeWarehouseConfig } from '@/services/lake';
import './index.less';

const { Paragraph, Text, Title } = Typography;

interface WarehouseFormValues {
  name?: string;
  jdbcUrl?: string;
  username?: string;
  password?: string;
  driverClass?: string;
  driverLocation?: string;
  driverSha256?: string;
  adoptDataSourceId?: number;
}

const responseError = (response: LakeApiResponse<unknown>, fallback: string) =>
  response.msg || response.message || fallback;

const statusMeta = (status?: string) => {
  if (status === 'CONNECTED_SUCCESS') return { color: 'success', label: '连接正常' };
  if (status === 'CONNECTED_FAILED') return { color: 'error', label: '连接失败' };
  return { color: 'default', label: '尚未测试' };
};

const WarehousePage: React.FC = () => {
  const [form] = Form.useForm<WarehouseFormValues>();
  const [config, setConfig] = useState<LakeWarehouseConfig>();
  const [drivers, setDrivers] = useState<LakeJdbcDriver[]>([]);
  const [legacyDorisSources, setLegacyDorisSources] = useState<DataSourceRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [registering, setRegistering] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [configResponse, driverResponse, dataSourceResponse] = await Promise.all([
        fetchLakeWarehouse(),
        fetchLakeJdbcDrivers(),
        fetchDataSourceAll(),
      ]);
      if (configResponse.code === 0) {
        setConfig(configResponse.data || undefined);
        if (configResponse.data) {
          form.setFieldsValue({
            name: configResponse.data.name,
            jdbcUrl: configResponse.data.jdbcUrl,
            username: configResponse.data.username,
            driverClass: configResponse.data.driverClass,
            driverLocation: configResponse.data.driverLocation,
            driverSha256: configResponse.data.driverSha256,
          });
        }
      } else {
        message.error(responseError(configResponse, '读取数仓配置失败'));
      }
      if (driverResponse.code === 0) {
        setDrivers(Array.isArray(driverResponse.data) ? driverResponse.data : []);
      } else {
        message.error(responseError(driverResponse, '读取驱动列表失败'));
      }
      if (dataSourceResponse.code === 0) {
        const rows = Array.isArray(dataSourceResponse.data)
          ? dataSourceResponse.data
          : dataSourceResponse.data?.bizData || [];
        setLegacyDorisSources((rows as DataSourceRecord[]).filter((item) =>
          String(item.dbType || '').toUpperCase() === 'DORIS' && !item.systemManaged));
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '读取数仓配置失败');
    } finally {
      setLoading(false);
    }
  }, [form]);

  useEffect(() => {
    void load();
  }, [load]);

  const driverOptions = useMemo(() => drivers
    .filter((driver) => driver.adapter === 'MYSQL' && driver.verified !== false)
    .map((driver) => ({
      value: driver.driverLocation,
      label: `${driver.fileName || driver.driverLocation} · ${driver.driverClass || '未填写驱动类'}`,
    })), [drivers]);

  const payload = (values: WarehouseFormValues) => ({
    ...values,
    name: values.name?.trim() || '湖 ODS 数仓',
    jdbcUrl: values.jdbcUrl?.trim(),
    username: values.username?.trim(),
    driverLocation: values.driverLocation?.trim(),
  });

  const test = async () => {
    try {
      const fields: Array<keyof WarehouseFormValues> = ['jdbcUrl', 'username', 'driverLocation'];
      if (!config?.passwordConfigured) fields.push('password');
      const values = await form.validateFields(fields);
      setTesting(true);
      const response = await testLakeWarehouse(payload(values));
      if (response.code !== 0) throw new Error(responseError(response, 'Doris 连接测试失败'));
      setConfig((current) => ({ ...current, ...(response.data || {}), connStatus: response.data?.connStatus }));
      if (response.data?.connStatus === 'CONNECTED_SUCCESS') {
        message.success('Doris ODS 连接测试成功');
      } else {
        message.error(response.data?.lastError || '连接失败，请检查地址、账号、密码和驱动');
      }
    } catch (error) {
      if ((error as { errorFields?: unknown })?.errorFields) return;
      message.error(error instanceof Error ? error.message : 'Doris 连接测试失败');
    } finally {
      setTesting(false);
    }
  };

  const save = async (values: WarehouseFormValues) => {
    setSaving(true);
    try {
      const response = await saveLakeWarehouse(payload(values));
      if (response.code !== 0 || !response.data) throw new Error(responseError(response, '保存数仓配置失败'));
      setConfig(response.data);
      form.setFieldValue('password', undefined);
      message.success('数仓配置已保存，系统内置 Doris 数据源已同步');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保存数仓配置失败');
    } finally {
      setSaving(false);
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
        message.success('驱动上传并校验成功');
        onSuccess?.(response.data);
        await load();
        if (response.data?.driverLocation) form.setFieldValue('driverLocation', response.data.driverLocation);
        if (response.data?.driverClass) form.setFieldValue('driverClass', response.data.driverClass);
        if (response.data?.sha256) form.setFieldValue('driverSha256', response.data.sha256);
      } catch (error) {
        onError?.(error as Error);
        message.error(error instanceof Error ? error.message : '驱动上传失败');
      } finally {
        setUploading(false);
      }
    },
  };

  const registerPreinstalledDriver = async () => {
    try {
      const values = await form.validateFields(['driverLocation', 'driverClass', 'driverSha256']);
      if (!values.driverLocation) return;
      setRegistering(true);
      const location = values.driverLocation.trim();
      const response = await registerLakeJdbcDriver({
        adapter: 'MYSQL',
        fileName: location.split('/').pop(),
        driverLocation: location,
        driverClass: values.driverClass?.trim(),
        sha256: values.driverSha256?.trim(),
      });
      if (response.code !== 0 || !response.data) {
        throw new Error(responseError(response, '预装驱动注册失败'));
      }
      message.success('预装驱动已注册并校验');
      await load();
    } catch (error) {
      if ((error as { errorFields?: unknown })?.errorFields) return;
      message.error(error instanceof Error ? error.message : '预装驱动注册失败');
    } finally {
      setRegistering(false);
    }
  };

  const currentStatus = statusMeta(config?.connStatus);

  return (
    <PageContainer
      title="数仓配置"
      subTitle="湖能力始终可用；这里配置唯一的 Doris ODS 连接和离线 JDBC 驱动。"
      extra={<Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>刷新</Button>}
    >
      <div className="lake-warehouse-page">
        <Alert
          className="lake-warehouse-banner"
          type={config?.configured ? 'success' : 'info'}
          showIcon
          icon={config?.configured ? <CheckCircleOutlined /> : <CloudServerOutlined />}
          message={config?.configured ? 'Doris ODS 已配置' : '还没有配置 Doris ODS'}
          description={config?.configured
            ? '湖控制面会直接使用此连接；数据源管理中的对应记录是系统内置只读投影。'
            : '完成连接测试并保存后，系统会自动创建一个名为 LAKE_ODS_DORIS 的只读数据源投影。'}
        />

        <div className="lake-warehouse-grid">
          <Card className="lake-warehouse-card" loading={loading}>
            <div className="lake-warehouse-card-heading">
              <div>
                <Text className="lake-warehouse-kicker">ODS CONNECTION</Text>
                <Title level={3}>Doris ODS 连接</Title>
                <Paragraph type="secondary">密码只用于服务端加密保存，保存后不会回显。</Paragraph>
              </div>
              <Tag color={currentStatus.color}>{currentStatus.label}</Tag>
            </div>
            <Form form={form} layout="vertical" onFinish={save} requiredMark={false}>
              <Form.Item name="name" label="配置名称">
                <Input placeholder="例如：生产湖 ODS" />
              </Form.Item>
              <Form.Item name="adoptDataSourceId" label="复用历史 Doris 数据源（可选）" extra="首次配置时可复用已有 Doris 数据源 ID，已有任务无需改动。">
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  placeholder="不选择则自动创建系统内置数据源"
                  options={legacyDorisSources.map((item) => ({
                    // Keep the string form so large database IDs do not lose
                    // precision in a browser before Spring converts them to Long.
                    value: item.id,
                    label: `${item.name || `Doris #${item.id}`} · ${item.jdbcUrl || '历史连接'}`,
                  }))}
                />
              </Form.Item>
              <Form.Item name="jdbcUrl" label="JDBC URL" rules={[{ required: true, message: '请输入 Doris JDBC URL' }]}>
                <Input placeholder="jdbc:mysql://doris-fe:9030/" />
              </Form.Item>
              <div className="lake-warehouse-form-row">
                <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
                  <Input placeholder="Doris 账号" />
                </Form.Item>
                <Form.Item name="password" label={config?.passwordConfigured ? '密码（留空保持不变）' : '密码'} rules={config?.passwordConfigured ? [] : [{ required: true, message: '请输入密码' }]}>
                  <Input.Password placeholder={config?.passwordConfigured ? '已加密保存，留空即可' : '请输入密码'} autoComplete="new-password" />
                </Form.Item>
              </div>
              <div className="lake-warehouse-form-row">
                <Form.Item name="driverLocation" label="本地 JDBC 驱动" rules={[{ required: true, message: '请选择或上传驱动' }]}>
                  <Input list="lake-mysql-driver-options" placeholder="mysql-connector-j.jar（位于 jdbc-drivers）" />
                </Form.Item>
                <Form.Item name="driverClass" label="驱动类" initialValue="com.mysql.cj.jdbc.Driver" rules={[{ required: true, message: '请输入驱动类' }]}>
                  <Input placeholder="com.mysql.cj.jdbc.Driver" />
                </Form.Item>
              </div>
              <datalist id="lake-mysql-driver-options">
                {driverOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </datalist>
              <Form.Item name="driverSha256" label="SHA-256（可选，服务端会重新计算）">
                <Input placeholder="上传或注册后自动填充" />
              </Form.Item>
              <div className="lake-warehouse-actions">
                <Button icon={<LinkOutlined />} loading={testing} onClick={() => void test()}>连接测试</Button>
                <Button type="primary" htmlType="submit" loading={saving} icon={<SafetyCertificateOutlined />}>测试并保存</Button>
              </div>
            </Form>
            {config?.systemDataSourceId ? (
              <Descriptions className="lake-warehouse-summary" size="small" column={1}>
                <Descriptions.Item label="系统投影 ID">{config.systemDataSourceId}</Descriptions.Item>
                <Descriptions.Item label="配置版本">v{config.configVersion || 1}</Descriptions.Item>
              </Descriptions>
            ) : null}
          </Card>

          <Card className="lake-warehouse-card" title="离线驱动" extra={<Space>
            <Button icon={<SafetyCertificateOutlined />} loading={registering} onClick={() => void registerPreinstalledDriver()}>注册预装驱动</Button>
            <Upload {...uploadProps}><Button icon={<UploadOutlined />} loading={uploading}>上传 .jar</Button></Upload>
          </Space>}>
            <Paragraph type="secondary">运行时只读取本机或共享 jdbc-drivers 目录，不访问 Maven Central 或其他外部地址。</Paragraph>
            <Table<LakeJdbcDriver>
              rowKey={(record) => String(record.id || record.driverLocation)}
              size="small"
              pagination={false}
              dataSource={drivers}
              locale={{ emptyText: '暂无已校验驱动，请上传或注册预装驱动' }}
              columns={[
                { title: '适配器', dataIndex: 'adapter', width: 110, render: (value: string) => <Tag color="blue">{value}</Tag> },
                { title: '文件', dataIndex: 'fileName', ellipsis: true },
                { title: '驱动类', dataIndex: 'driverClass', ellipsis: true },
                { title: '版本', dataIndex: 'version', width: 72, render: (value: number) => value ? `v${value}` : '-' },
                { title: '状态', dataIndex: 'status', width: 110, render: (value: string, record) => <Tag color={record.verified ? 'success' : 'warning'}>{record.verified ? '已校验' : value || '待校验'}</Tag> },
              ]}
            />
            <div className="lake-warehouse-next-step">
              <Text strong>下一步</Text>
              <Text type="secondary">保存后可回到数据源管理查看系统内置只读投影。</Text>
              <Button type="link" onClick={() => history.push('/data-source')}>打开数据源管理</Button>
            </div>
          </Card>
        </div>
      </div>
    </PageContainer>
  );
};

export default WarehousePage;
