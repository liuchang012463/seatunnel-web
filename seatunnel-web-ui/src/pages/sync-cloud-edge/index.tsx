import { PageContainer } from '@ant-design/pro-components';
import {
  ApiOutlined,
  CloudSyncOutlined,
  DisconnectOutlined,
  HistoryOutlined,
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
  Progress,
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
  dispatchCloudEdgeTask,
  fetchCloudEdgeTasks,
  fetchNetworkEvents,
  toggleNetworkState,
} from './service';
import type {
  CloudEdgeNetworkEvent,
  CloudEdgeStatus,
  CloudEdgeTaskRecord,
  CloudEdgeTransport,
} from './types';

const statusColor: Record<CloudEdgeStatus, string> = {
  DISPATCHED: 'blue',
  RUNNING: 'green',
  OFFLINE: 'gold',
  STAGED: 'cyan',
  COMPLETED: 'default',
};

const statusLabel: Record<CloudEdgeStatus, string> = {
  DISPATCHED: '已下发',
  RUNNING: '运行中',
  OFFLINE: '已断网',
  STAGED: '暂存中',
  COMPLETED: '已完成',
};

const transportLabel: Record<CloudEdgeTransport, string> = {
  FULL_MIRROR: '全量镜像',
  INCREMENTAL: '差异同步',
  EVENT_FEEDBACK: '状态回传',
};

const formatBytes = (bytes: number) => {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(2)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(2)} KB`;
  return `${bytes} B`;
};

const CloudEdgeTasksPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<CloudEdgeTaskRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<CloudEdgeStatus>();
  const [transport, setTransport] = useState<CloudEdgeTransport>();
  const [selected, setSelected] = useState<CloudEdgeTaskRecord>();
  const [events, setEvents] = useState<CloudEdgeNetworkEvent[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();

  const reload = async () => {
    setLoading(true);
    try {
      const response = await fetchCloudEdgeTasks({
        keyword,
        status,
        transport,
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
      dispatched: records.filter((r) => r.status === 'DISPATCHED').length,
      offline: records.filter((r) => r.status === 'OFFLINE').length,
      running: records.filter((r) => r.status === 'RUNNING').length,
    }),
    [records],
  );

  const handleView = async (record: CloudEdgeTaskRecord) => {
    setSelected(record);
    const response = await fetchNetworkEvents(record.id);
    if (response.code === 0) {
      setEvents(response.data || []);
    } else {
      setEvents([]);
    }
  };

  const handleToggleNetwork = async (record: CloudEdgeTaskRecord, online: boolean) => {
    const response = await toggleNetworkState(record.id, online);
    if (response.code === 0) {
      message.success(response.data.message);
      reload();
      if (selected?.id === record.id) {
        handleView({ ...record, status: online ? 'RUNNING' : 'OFFLINE' });
      }
    }
  };

  const handleDispatch = async () => {
    const values = await form.validateFields();
    const response = await dispatchCloudEdgeTask({
      name: values.name,
      transport: values.transport,
      sourceCluster: values.sourceCluster,
      edgeNode: values.edgeNode,
      bytesPlanned: Number(values.bytesPlanned),
      description: values.description || '新下发的云边任务',
    });
    if (response.code === 0) {
      message.success('任务已下发到边缘节点');
      setModalOpen(false);
      form.resetFields();
      reload();
    }
  };

  const columns: ColumnsType<CloudEdgeTaskRecord> = [
    {
      title: '任务名称',
      dataIndex: 'name',
      render: (value, record) => (
        <Button type="link" onClick={() => handleView(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '传输方式',
      dataIndex: 'transport',
      width: 110,
      render: (value: CloudEdgeTransport) => <Tag color="blue">{transportLabel[value]}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: CloudEdgeStatus) => <Tag color={statusColor[value]}>{statusLabel[value]}</Tag>,
    },
    {
      title: '云端 → 边缘',
      dataIndex: 'edgeNode',
      render: (_, record) => (
        <Space>
          <Tag>{record.sourceCluster}</Tag>
          <CloudSyncOutlined />
          <Tag color="cyan">{record.edgeNode}</Tag>
        </Space>
      ),
    },
    {
      title: '传输进度',
      dataIndex: 'bytesTransferred',
      width: 200,
      render: (_, record) => {
        const percent = Math.min(
          100,
          Math.round((record.bytesTransferred / record.bytesPlanned) * 100),
        );
        return (
          <Space direction="vertical" size={0} style={{ width: 180 }}>
            <Progress percent={percent} size="small" />
            <small>
              {formatBytes(record.bytesTransferred)} / {formatBytes(record.bytesPlanned)}
            </small>
          </Space>
        );
      },
    },
    {
      title: '暂存分片',
      dataIndex: 'queuedChunks',
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
      width: 240,
      render: (_, record) => (
        <Space>
          <Button size="small" onClick={() => handleView(record)}>
            详情
          </Button>
          {record.status === 'OFFLINE' ? (
            <Button
              size="small"
              type="primary"
              ghost
              icon={<ApiOutlined />}
              onClick={() => handleToggleNetwork(record, true)}
            >
              恢复网络
            </Button>
          ) : (
            <Button
              size="small"
              icon={<DisconnectOutlined />}
              onClick={() => handleToggleNetwork(record, false)}
            >
              模拟断网
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="云边协同任务管理"
      subTitle="云端下发、边缘执行、断网暂存和恢复续传"
      extra={[
        <Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>,
        <Button
          key="dispatch"
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setModalOpen(true)}
        >
          下发任务
        </Button>,
      ]}
    >
      <Alert
        type="warning"
        showIcon
        message="本页面为有限实现（PoC）"
        description="真实云边协同、断网暂存与恢复续传依赖具体边缘运行环境，当前演示数据为受控模拟。"
        style={{ marginBottom: 16 }}
      />
      <Row gutter={[16, 16]} className="sync-cloud-edge-stats">
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="任务总数" value={total} />
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="已下发" value={stats.dispatched} valueStyle={{ color: '#2563eb' }} />
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="运行中" value={stats.running} valueStyle={{ color: '#16a34a' }} />
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="断网暂存" value={stats.offline} valueStyle={{ color: '#d97706' }} />
          </Card>
        </Col>
      </Row>
      <Card title="云边任务清单">
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search
            placeholder="按名称筛选"
            allowClear
            style={{ width: 240 }}
            onSearch={setKeyword}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 120 }}
            value={status}
            onChange={setStatus}
            options={[
              { value: 'DISPATCHED', label: '已下发' },
              { value: 'RUNNING', label: '运行中' },
              { value: 'OFFLINE', label: '已断网' },
              { value: 'STAGED', label: '暂存中' },
              { value: 'COMPLETED', label: '已完成' },
            ]}
          />
          <Select
            allowClear
            placeholder="传输方式"
            style={{ width: 140 }}
            value={transport}
            onChange={setTransport}
            options={[
              { value: 'FULL_MIRROR', label: '全量镜像' },
              { value: 'INCREMENTAL', label: '差异同步' },
              { value: 'EVENT_FEEDBACK', label: '状态回传' },
            ]}
          />
        </Space>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={records}
          columns={columns}
          pagination={{ pageSize: 10, showTotal: (sum) => `共 ${sum} 个任务` }}
        />
      </Card>
      <Drawer
        title="云边任务详情"
        width={720}
        open={Boolean(selected)}
        onClose={() => {
          setSelected(undefined);
          setEvents([]);
        }}
      >
        {selected ? (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="名称" span={2}>{selected.name}</Descriptions.Item>
              <Descriptions.Item label="传输方式">
                <Tag color="blue">{transportLabel[selected.transport]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor[selected.status]}>{statusLabel[selected.status]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="云端">{selected.sourceCluster}</Descriptions.Item>
              <Descriptions.Item label="边缘节点">{selected.edgeNode}</Descriptions.Item>
              <Descriptions.Item label="计划数据量" span={2}>
                {formatBytes(selected.bytesPlanned)}
              </Descriptions.Item>
              <Descriptions.Item label="已传输">
                {formatBytes(selected.bytesTransferred)}
              </Descriptions.Item>
              <Descriptions.Item label="暂存分片">{selected.queuedChunks}</Descriptions.Item>
              <Descriptions.Item label="说明" span={2}>{selected.description}</Descriptions.Item>
            </Descriptions>
            <Card
              size="small"
              title={
                <span>
                  <HistoryOutlined /> 网络事件
                </span>
              }
              style={{ marginTop: 12 }}
            >
              {events.length === 0 ? (
                <span>暂无网络事件。</span>
              ) : (
                events.map((event) => (
                  <div key={`${event.taskId}-${event.timestamp}`} className="sync-cloud-edge-event">
                    <span>{event.timestamp}</span>
                    <Tag color={event.online ? 'green' : 'gold'}>
                      {event.online ? '在线' : '离线'}
                    </Tag>
                    <span>待传分片：{event.pendingChunks}</span>
                    <span>{event.message}</span>
                  </div>
                ))
              )}
            </Card>
          </>
        ) : null}
      </Drawer>
      <Modal
        title="下发云边任务"
        open={modalOpen}
        onOk={handleDispatch}
        onCancel={() => setModalOpen(false)}
        okText="下发"
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ transport: 'INCREMENTAL' }}
        >
          <Form.Item name="name" label="任务名称" rules={[{ required: true, message: '请输入任务名称' }]}>
            <Input placeholder="例如：华北节点镜像同步" />
          </Form.Item>
          <Form.Item name="transport" label="传输方式">
            <Select
              options={[
                { value: 'FULL_MIRROR', label: '全量镜像' },
                { value: 'INCREMENTAL', label: '差异同步' },
                { value: 'EVENT_FEEDBACK', label: '状态回传' },
              ]}
            />
          </Form.Item>
          <Form.Item name="sourceCluster" label="云端集群" rules={[{ required: true, message: '请输入云端集群' }]}>
            <Input placeholder="例如 cn-north-1" />
          </Form.Item>
          <Form.Item name="edgeNode" label="边缘节点" rules={[{ required: true, message: '请输入边缘节点' }]}>
            <Input placeholder="例如 edge-huabei-01" />
          </Form.Item>
          <Form.Item name="bytesPlanned" label="计划数据量 (Bytes)" rules={[{ required: true, message: '请输入计划数据量' }]}>
            <InputNumber min={1024} style={{ width: '100%' }} placeholder="例如 5368709120" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="用于审计与回看的任务说明" />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default CloudEdgeTasksPage;
