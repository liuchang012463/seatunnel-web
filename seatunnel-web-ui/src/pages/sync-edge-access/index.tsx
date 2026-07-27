import { PageContainer } from '@ant-design/pro-components';
import {
  ApiOutlined,
  CheckCircleOutlined,
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useMemo, useState } from 'react';
import './index.less';
import {
  fetchEdgeAccess,
  registerEdgeAccess,
  testEdgeConnection,
} from './service';
import type {
  EdgeAccessRecord,
  EdgeAccessStatus,
  EdgeAccessTestResult,
  EdgeProtocol,
  EdgeStatus,
} from './types';

const statusColor: Record<EdgeStatus, string> = {
  REGISTERED: 'blue',
  PENDING: 'gold',
  TESTING: 'cyan',
  FAILED: 'red',
  RUNNING: 'green',
};

const statusLabel: Record<EdgeStatus, string> = {
  REGISTERED: '已登记',
  PENDING: '待启用',
  TESTING: '测试中',
  FAILED: '失败',
  RUNNING: '运行中',
};

const accessColor: Record<EdgeAccessStatus, string> = {
  ONLINE: 'green',
  OFFLINE: 'red',
  DEGRADED: 'gold',
};

const accessLabel: Record<EdgeAccessStatus, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  DEGRADED: '降级',
};

const protocolLabel: Record<EdgeProtocol, string> = {
  MODBUS_TCP: 'Modbus TCP',
  MQTT: 'MQTT',
  OPCUA: 'OPC UA',
  SFTP_FILE: 'SFTP 文件',
  HTTP_HOOK: 'HTTP Hook',
};

const formatBytes = (bytes: number) => {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(2)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(2)} KB`;
  return `${bytes} B`;
};

const EdgeAccessPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<EdgeAccessRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [protocol, setProtocol] = useState<EdgeProtocol>();
  const [status, setStatus] = useState<EdgeStatus>();
  const [selected, setSelected] = useState<EdgeAccessRecord>();
  const [testResult, setTestResult] = useState<EdgeAccessTestResult>();
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();

  const reload = async () => {
    setLoading(true);
    try {
      const response = await fetchEdgeAccess({
        keyword,
        protocol,
        status,
        pageNo: 1,
        pageSize: 50,
      });
      if (response.code === 0) {
        setRecords(response.data?.bizData || []);
        setTotal(response.data?.pagination?.total || 0);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stats = useMemo(
    () => ({
      running: records.filter((r) => r.status === 'RUNNING').length,
      pending: records.filter((r) => r.status === 'PENDING').length,
      devices: records.reduce((sum, r) => sum + r.deviceCount, 0),
    }),
    [records],
  );

  const handleTest = async (record: EdgeAccessRecord) => {
    setSelected(record);
    const response = await testEdgeConnection(record.id);
    if (response.code === 0) {
      setTestResult(response.data);
      if (response.data.success) {
        message.success(response.data.message);
      } else {
        message.warning(response.data.message);
      }
    }
  };

  const handleRegister = async () => {
    const values = await form.validateFields();
    const response = await registerEdgeAccess({
      name: values.name,
      protocol: values.protocol,
      accessStatus: 'OFFLINE',
      endpoint: values.endpoint,
      deviceCount: values.deviceCount,
      owner: '当前 SSO 用户',
      description: values.description || '新登记的边缘接入',
    });
    if (response.code === 0) {
      message.success('边缘接入已登记，请执行连通测试');
      setModalOpen(false);
      form.resetFields();
      reload();
    }
  };

  const columns: ColumnsType<EdgeAccessRecord> = [
    {
      title: '名称',
      dataIndex: 'name',
      render: (value, record) => (
        <Button type="link" onClick={() => setSelected(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '协议',
      dataIndex: 'protocol',
      width: 120,
      render: (value: EdgeProtocol) => <Tag color="blue">{protocolLabel[value]}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: EdgeStatus) => <Tag color={statusColor[value]}>{statusLabel[value]}</Tag>,
    },
    {
      title: '接入',
      dataIndex: 'accessStatus',
      width: 100,
      render: (value: EdgeAccessStatus) => <Tag color={accessColor[value]}>{accessLabel[value]}</Tag>,
    },
    {
      title: '设备数',
      dataIndex: 'deviceCount',
      width: 90,
    },
    {
      title: '已采字节',
      dataIndex: 'bytesIngested',
      width: 120,
      render: (value: number) => formatBytes(value),
    },
    {
      title: '责任人',
      dataIndex: 'owner',
      width: 100,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 170,
    },
    {
      title: '操作',
      width: 200,
      render: (_, record) => (
        <Space>
          <Button size="small" onClick={() => setSelected(record)}>
            详情
          </Button>
          <Button
            size="small"
            type="primary"
            ghost
            icon={<ExperimentOutlined />}
            onClick={() => handleTest(record)}
          >
            连通测试
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="边缘接入任务管理"
      subTitle="协议选择、连通测试、边缘代理和启停"
      extra={[
        <Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>,
        <Button
          key="register"
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setModalOpen(true)}
        >
          新增接入
        </Button>,
      ]}
    >
      <Alert
        type="warning"
        showIcon
        message="本页面为有限实现（PoC）"
        description="真实边缘协议接入需在指定边缘运行环境部署，本页演示数据为受控模拟。"
        style={{ marginBottom: 16 }}
      />
      <Row gutter={[16, 16]} className="sync-edge-access-stats">
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="接入总数" value={total} prefix={<ApiOutlined />} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="运行中" value={stats.running} valueStyle={{ color: '#16a34a' }} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="待启用 / 设备总数" value={`${stats.pending} / ${stats.devices}`} />
          </Card>
        </Col>
      </Row>
      <Card title="边缘接入清单">
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search
            placeholder="按名称筛选"
            allowClear
            style={{ width: 240 }}
            onSearch={setKeyword}
          />
          <Select
            allowClear
            placeholder="协议"
            style={{ width: 140 }}
            value={protocol}
            onChange={setProtocol}
            options={[
              { value: 'MODBUS_TCP', label: 'Modbus TCP' },
              { value: 'MQTT', label: 'MQTT' },
              { value: 'OPCUA', label: 'OPC UA' },
              { value: 'SFTP_FILE', label: 'SFTP 文件' },
              { value: 'HTTP_HOOK', label: 'HTTP Hook' },
            ]}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 120 }}
            value={status}
            onChange={setStatus}
            options={[
              { value: 'REGISTERED', label: '已登记' },
              { value: 'PENDING', label: '待启用' },
              { value: 'TESTING', label: '测试中' },
              { value: 'RUNNING', label: '运行中' },
              { value: 'FAILED', label: '失败' },
            ]}
          />
        </Space>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={records}
          columns={columns}
          pagination={{ pageSize: 10, showTotal: (sum) => `共 ${sum} 个接入` }}
        />
      </Card>
      <Drawer
        title="边缘接入详情"
        width={640}
        open={Boolean(selected)}
        onClose={() => {
          setSelected(undefined);
          setTestResult(undefined);
        }}
      >
        {selected ? (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="名称" span={2}>{selected.name}</Descriptions.Item>
              <Descriptions.Item label="协议">
                <Tag color="blue">{protocolLabel[selected.protocol]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor[selected.status]}>{statusLabel[selected.status]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="接入">
                <Tag color={accessColor[selected.accessStatus]}>
                  {accessLabel[selected.accessStatus]}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="设备数">{selected.deviceCount}</Descriptions.Item>
              <Descriptions.Item label="Endpoint" span={2}>
                <code>{selected.endpoint}</code>
              </Descriptions.Item>
              <Descriptions.Item label="已采字节">
                {formatBytes(selected.bytesIngested)}
              </Descriptions.Item>
              <Descriptions.Item label="时延">{selected.latencyMs} ms</Descriptions.Item>
              <Descriptions.Item label="说明" span={2}>{selected.description}</Descriptions.Item>
            </Descriptions>
            <Card size="small" title="最近一次连通测试" style={{ marginTop: 16 }}>
              {testResult ? (
                <Space direction="vertical">
                  <Tag
                    icon={testResult.success ? <CheckCircleOutlined /> : undefined}
                    color={testResult.success ? 'green' : 'red'}
                  >
                    {testResult.success ? '连通成功' : '连通失败'}
                  </Tag>
                  <span>{testResult.message}</span>
                  <span>耗时：{testResult.latencyMs} ms</span>
                  {testResult.sampleRows ? <span>样例：{testResult.sampleRows} 条</span> : null}
                </Space>
              ) : (
                <span>尚未执行测试，请点击右上角“连通测试”。</span>
              )}
            </Card>
          </>
        ) : null}
      </Drawer>
      <Modal
        title="新增边缘接入"
        open={modalOpen}
        onOk={handleRegister}
        onCancel={() => setModalOpen(false)}
        okText="登记"
      >
        <Form form={form} layout="vertical" initialValues={{ protocol: 'MODBUS_TCP' }}>
          <Form.Item name="name" label="接入名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：Modbus 设备接入" />
          </Form.Item>
          <Form.Item name="protocol" label="协议">
            <Select
              options={[
                { value: 'MODBUS_TCP', label: 'Modbus TCP' },
                { value: 'MQTT', label: 'MQTT' },
                { value: 'OPCUA', label: 'OPC UA' },
                { value: 'SFTP_FILE', label: 'SFTP 文件' },
                { value: 'HTTP_HOOK', label: 'HTTP Hook' },
              ]}
            />
          </Form.Item>
          <Form.Item name="endpoint" label="Endpoint" rules={[{ required: true, message: '请输入 endpoint' }]}>
            <Input placeholder="modbus://edge-01:502" />
          </Form.Item>
          <Form.Item name="deviceCount" label="设备数">
            <InputNumber min={0} style={{ width: '100%' }} placeholder="可选" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="用于审计和巡检的接入说明" />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default EdgeAccessPage;
