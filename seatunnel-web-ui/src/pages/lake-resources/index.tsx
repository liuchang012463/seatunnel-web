import { PageContainer } from '@ant-design/pro-components';
import {
  CheckCircleOutlined,
  CloudServerOutlined,
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
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
  fetchLakeResources,
  registerLakeResource,
  testLakeConnection,
} from './service';
import type {
  LakeAccessMode,
  LakeFormat,
  LakeResourceRecord,
  LakeResourceTestResult,
  LakeStatus,
} from './types';

const statusColor: Record<LakeStatus, string> = {
  REGISTERED: 'green',
  PENDING: 'gold',
  FAILED: 'red',
};

const statusLabel: Record<LakeStatus, string> = {
  REGISTERED: '已登记',
  PENDING: '待启用',
  FAILED: '失败',
};

const formatColor: Record<LakeFormat, string> = {
  PAIMON: 'blue',
  ICEBERG: 'purple',
  HUDI: 'cyan',
  DELTA: 'magenta',
  OSS: 'orange',
  HDFS: 'geekblue',
};

const accessModeLabel: Record<LakeAccessMode, string> = {
  READ_WRITE: '读写',
  READ_ONLY: '只读',
  APPEND_ONLY: '仅追加',
};

const LakeResourcesPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<LakeResourceRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [format, setFormat] = useState<LakeFormat>();
  const [status, setStatus] = useState<LakeStatus>();
  const [selected, setSelected] = useState<LakeResourceRecord>();
  const [testResult, setTestResult] = useState<LakeResourceTestResult>();
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();

  const reload = async () => {
    setLoading(true);
    try {
      const response = await fetchLakeResources({
        keyword,
        format,
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

  const stats = useMemo(() => {
    return {
      registered: records.filter((r) => r.status === 'REGISTERED').length,
      referenced: records.reduce((sum, r) => sum + r.referencedTasks, 0),
    };
  }, [records]);

  const handleTest = async (record: LakeResourceRecord) => {
    setSelected(record);
    const response = await testLakeConnection(record.id);
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
    const response = await registerLakeResource({
      name: values.name,
      format: values.format,
      accessMode: values.accessMode,
      endpoint: values.endpoint,
      database: values.database,
      partitionSpec: values.partitionSpec,
      storageQuotaGB: values.storageQuotaGB,
      owner: '当前 SSO 用户',
      description: values.description || '新登记的入湖资源',
    });
    if (response.code === 0) {
      message.success('入湖资源已登记');
      setModalOpen(false);
      form.resetFields();
      reload();
    }
  };

  const columns: ColumnsType<LakeResourceRecord> = [
    {
      title: '资源名称',
      dataIndex: 'name',
      render: (value, record) => (
        <Button type="link" onClick={() => setSelected(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '格式',
      dataIndex: 'format',
      width: 100,
      render: (value: LakeFormat) => <Tag color={formatColor[value]}>{value}</Tag>,
    },
    {
      title: '访问模式',
      dataIndex: 'accessMode',
      width: 110,
      render: (value: LakeAccessMode) => <Tag>{accessModeLabel[value]}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: LakeStatus) => <Tag color={statusColor[value]}>{statusLabel[value]}</Tag>,
    },
    {
      title: '数据库',
      dataIndex: 'database',
      width: 130,
    },
    {
      title: '关联任务',
      dataIndex: 'referencedTasks',
      width: 100,
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
            测试连接
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="入湖资源管理"
      subTitle="湖目标、格式、分区、测试和任务引用"
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
          登记资源
        </Button>,
      ]}
    >
      <Row gutter={[16, 16]} className="lake-resources-stats">
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="资源总数" value={total} prefix={<CloudServerOutlined />} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="已登记" value={stats.registered} valueStyle={{ color: '#16a34a' }} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="任务引用总数" value={stats.referenced} />
          </Card>
        </Col>
      </Row>
      <Card title="入湖资源清单">
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search
            placeholder="按名称筛选"
            allowClear
            style={{ width: 240 }}
            onSearch={setKeyword}
          />
          <Select
            allowClear
            placeholder="格式"
            style={{ width: 140 }}
            value={format}
            onChange={setFormat}
            options={[
              { value: 'PAIMON', label: 'Paimon' },
              { value: 'ICEBERG', label: 'Iceberg' },
              { value: 'HUDI', label: 'Hudi' },
              { value: 'DELTA', label: 'Delta' },
              { value: 'OSS', label: 'OSS' },
              { value: 'HDFS', label: 'HDFS' },
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
              { value: 'FAILED', label: '失败' },
            ]}
          />
        </Space>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={records}
          columns={columns}
          pagination={{ pageSize: 10, showTotal: (sum) => `共 ${sum} 个资源` }}
        />
      </Card>
      <Drawer
        title="资源详情与最近测试"
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
              <Descriptions.Item label="名称" span={2}>
                {selected.name}
              </Descriptions.Item>
              <Descriptions.Item label="格式">
                <Tag color={formatColor[selected.format]}>{selected.format}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="访问模式">{accessModeLabel[selected.accessMode]}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor[selected.status]}>{statusLabel[selected.status]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="关联任务">{selected.referencedTasks}</Descriptions.Item>
              <Descriptions.Item label="Endpoint" span={2}>
                <code>{selected.endpoint}</code>
              </Descriptions.Item>
              <Descriptions.Item label="数据库">{selected.database}</Descriptions.Item>
              <Descriptions.Item label="分区">{selected.partitionSpec || '—'}</Descriptions.Item>
              <Descriptions.Item label="存储配额">
                {selected.storageQuotaGB ? `${selected.storageQuotaGB} GB` : '未设定'}
              </Descriptions.Item>
              <Descriptions.Item label="责任人">{selected.owner}</Descriptions.Item>
              <Descriptions.Item label="说明" span={2}>
                {selected.description}
              </Descriptions.Item>
            </Descriptions>
            <Card size="small" title="最近一次连通性测试" style={{ marginTop: 16 }}>
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
                  {testResult.sampleRows ? <span>预览：{testResult.sampleRows} 行</span> : null}
                </Space>
              ) : (
                <span>尚未执行测试，请点击右上角“测试连接”。</span>
              )}
            </Card>
          </>
        ) : null}
      </Drawer>
      <Modal
        title="登记入湖资源"
        open={modalOpen}
        onOk={handleRegister}
        onCancel={() => setModalOpen(false)}
        okText="登记"
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ format: 'PAIMON', accessMode: 'READ_WRITE' }}
        >
          <Form.Item name="name" label="资源名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：基础数据湖" />
          </Form.Item>
          <Form.Item name="format" label="存储格式">
            <Select
              options={[
                { value: 'PAIMON', label: 'Paimon' },
                { value: 'ICEBERG', label: 'Iceberg' },
                { value: 'HUDI', label: 'Hudi' },
                { value: 'DELTA', label: 'Delta' },
                { value: 'OSS', label: 'OSS' },
                { value: 'HDFS', label: 'HDFS' },
              ]}
            />
          </Form.Item>
          <Form.Item name="accessMode" label="访问模式">
            <Select
              options={[
                { value: 'READ_WRITE', label: '读写' },
                { value: 'READ_ONLY', label: '只读' },
                { value: 'APPEND_ONLY', label: '仅追加' },
              ]}
            />
          </Form.Item>
          <Form.Item name="endpoint" label="Endpoint" rules={[{ required: true, message: '请输入 endpoint' }]}>
            <Input placeholder="paimon://host:port/db" />
          </Form.Item>
          <Form.Item name="database" label="数据库" rules={[{ required: true, message: '请输入数据库' }]}>
            <Input placeholder="库名" />
          </Form.Item>
          <Form.Item name="partitionSpec" label="分区规则">
            <Input placeholder="可选，例如 dt=YYYY-MM-dd" />
          </Form.Item>
          <Form.Item name="storageQuotaGB" label="存储配额 (GB)">
            <InputNumber min={1} style={{ width: '100%' }} placeholder="可选" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="用于审计和巡检的资源说明" />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default LakeResourcesPage;
