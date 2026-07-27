import { PageContainer } from '@ant-design/pro-components';
import {
  ClockCircleOutlined,
  HistoryOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
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
  createLifecyclePolicy,
  executeLifecycleNow,
  fetchLifecycleExecutions,
  fetchLifecyclePolicies,
  toggleLifecycleStatus,
} from './service';
import type {
  LifecycleAction,
  LifecycleExecutionRecord,
  LifecyclePolicyRecord,
  LifecycleStatus,
  LifecycleTarget,
} from './types';

const statusColor: Record<LifecycleStatus, string> = {
  ACTIVE: 'green',
  PAUSED: 'gold',
  DRAFT: 'default',
};

const statusLabel: Record<LifecycleStatus, string> = {
  ACTIVE: '运行中',
  PAUSED: '已暂停',
  DRAFT: '草稿',
};

const targetLabel: Record<LifecycleTarget, string> = {
  HOT: '热存储',
  WARM: '温存储',
  COLD: '冷存储',
  ARCHIVE: '归档',
};

const actionLabel: Record<LifecycleAction, string> = {
  TTL: 'TTL',
  ARCHIVE: '归档',
  PURGE: '清理',
  COMPRESS: '压缩',
};

const resultColor: Record<LifecycleExecutionRecord['result'], string> = {
  SUCCESS: 'green',
  FAILED: 'red',
  PARTIAL: 'gold',
};

const LifecyclePoliciesPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<LifecyclePolicyRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [target, setTarget] = useState<LifecycleTarget>();
  const [status, setStatus] = useState<LifecycleStatus>();
  const [selected, setSelected] = useState<LifecyclePolicyRecord>();
  const [executions, setExecutions] = useState<LifecycleExecutionRecord[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();

  const reload = async () => {
    setLoading(true);
    try {
      const response = await fetchLifecyclePolicies({
        keyword,
        target,
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
      active: records.filter((r) => r.status === 'ACTIVE').length,
      paused: records.filter((r) => r.status === 'PAUSED').length,
    }),
    [records],
  );

  const handleView = async (record: LifecyclePolicyRecord) => {
    setSelected(record);
    const response = await fetchLifecycleExecutions(record.id);
    if (response.code === 0) {
      setExecutions(response.data || []);
    } else {
      setExecutions([]);
    }
  };

  const handleToggle = async (record: LifecyclePolicyRecord, next: LifecycleStatus) => {
    const response = await toggleLifecycleStatus(record.id, next);
    if (response.code === 0) {
      message.success(`策略已切换为${statusLabel[next]}`);
      reload();
    }
  };

  const handleExecute = async (record: LifecyclePolicyRecord) => {
    const response = await executeLifecycleNow(record.id);
    if (response.code === 0) {
      message.success('立即执行成功，记录已写入执行历史');
      if (selected?.id === record.id) {
        handleView(record);
      }
    }
  };

  const handleCreate = async () => {
    const values = await form.validateFields();
    const response = await createLifecyclePolicy({
      name: values.name,
      target: values.target,
      action: values.action,
      retentionDays: values.retentionDays,
      scope: values.scope,
      owner: '当前 SSO 用户',
      description: values.description || '新建立的策略',
    });
    if (response.code === 0) {
      message.success('策略已建立，等待调度或手动触发');
      setModalOpen(false);
      form.resetFields();
      reload();
    }
  };

  const columns: ColumnsType<LifecyclePolicyRecord> = [
    {
      title: '策略名称',
      dataIndex: 'name',
      render: (value, record) => (
        <Button type="link" onClick={() => handleView(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '目标层级',
      dataIndex: 'target',
      width: 110,
      render: (value: LifecycleTarget) => <Tag>{targetLabel[value]}</Tag>,
    },
    {
      title: '动作',
      dataIndex: 'action',
      width: 100,
      render: (value: LifecycleAction) => <Tag color="blue">{actionLabel[value]}</Tag>,
    },
    {
      title: '保留天数',
      dataIndex: 'retentionDays',
      width: 100,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: LifecycleStatus) => <Tag color={statusColor[value]}>{statusLabel[value]}</Tag>,
    },
    {
      title: '下次执行',
      dataIndex: 'nextRunAt',
      width: 170,
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
          {record.status === 'ACTIVE' ? (
            <Button
              size="small"
              icon={<PauseCircleOutlined />}
              onClick={() => handleToggle(record, 'PAUSED')}
            >
              暂停
            </Button>
          ) : (
            <Button
              size="small"
              type="primary"
              ghost
              icon={<PlayCircleOutlined />}
              onClick={() => handleToggle(record, 'ACTIVE')}
            >
              启用
            </Button>
          )}
          <Button
            size="small"
            icon={<ClockCircleOutlined />}
            onClick={() => handleExecute(record)}
          >
            立即执行
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="数据生命周期管理"
      subTitle="有效期、TTL、归档、清理和执行记录"
      extra={[
        <Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>,
        <Button
          key="create"
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setModalOpen(true)}
        >
          新建策略
        </Button>,
      ]}
    >
      <Row gutter={[16, 16]} className="lake-lifecycle-stats">
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="策略总数" value={total} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="运行中" value={stats.active} valueStyle={{ color: '#16a34a' }} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="已暂停" value={stats.paused} valueStyle={{ color: '#d97706' }} />
          </Card>
        </Col>
      </Row>
      <Card title="策略清单">
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search
            placeholder="按名称筛选"
            allowClear
            style={{ width: 240 }}
            onSearch={setKeyword}
          />
          <Select
            allowClear
            placeholder="目标层级"
            style={{ width: 140 }}
            value={target}
            onChange={setTarget}
            options={[
              { value: 'HOT', label: '热存储' },
              { value: 'WARM', label: '温存储' },
              { value: 'COLD', label: '冷存储' },
              { value: 'ARCHIVE', label: '归档' },
            ]}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 120 }}
            value={status}
            onChange={setStatus}
            options={[
              { value: 'ACTIVE', label: '运行中' },
              { value: 'PAUSED', label: '已暂停' },
              { value: 'DRAFT', label: '草稿' },
            ]}
          />
        </Space>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={records}
          columns={columns}
          pagination={{ pageSize: 10, showTotal: (sum) => `共 ${sum} 个策略` }}
        />
      </Card>
      <Drawer
        title="策略详情与执行记录"
        width={720}
        open={Boolean(selected)}
        onClose={() => {
          setSelected(undefined);
          setExecutions([]);
        }}
      >
        {selected ? (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="名称" span={2}>{selected.name}</Descriptions.Item>
              <Descriptions.Item label="目标层级">{targetLabel[selected.target]}</Descriptions.Item>
              <Descriptions.Item label="动作">{actionLabel[selected.action]}</Descriptions.Item>
              <Descriptions.Item label="保留天数">{selected.retentionDays} 天</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor[selected.status]}>{statusLabel[selected.status]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="作用范围" span={2}>
                <code>{selected.scope}</code>
              </Descriptions.Item>
              <Descriptions.Item label="上次执行">{selected.lastRunAt}</Descriptions.Item>
              <Descriptions.Item label="下次执行">{selected.nextRunAt}</Descriptions.Item>
              <Descriptions.Item label="说明" span={2}>{selected.description}</Descriptions.Item>
            </Descriptions>
            <Card size="small" title="累计执行" style={{ marginTop: 12 }}>
              <Space size={32}>
                <Statistic title="执行次数" value={selected.executionStats.executed} />
                <Statistic title="清理行数" value={selected.executionStats.purged} />
                <Statistic title="归档行数" value={selected.executionStats.archived} />
              </Space>
            </Card>
            <Card
              size="small"
              title={
                <span>
                  <HistoryOutlined /> 最近执行记录
                </span>
              }
              style={{ marginTop: 12 }}
            >
              {executions.length === 0 ? (
                <span>暂无执行记录。</span>
              ) : (
                <Table
                  size="small"
                  rowKey="id"
                  pagination={false}
                  dataSource={executions}
                  columns={[
                    { title: '开始', dataIndex: 'startAt', width: 170 },
                    { title: '结束', dataIndex: 'endAt', width: 170 },
                    {
                      title: '结果',
                      dataIndex: 'result',
                      width: 90,
                      render: (value: LifecycleExecutionRecord['result']) => (
                        <Tag color={resultColor[value]}>{value}</Tag>
                      ),
                    },
                    { title: '处理行数', dataIndex: 'processedRows', width: 100 },
                    { title: '说明', dataIndex: 'message' },
                  ]}
                />
              )}
            </Card>
          </>
        ) : null}
      </Drawer>
      <Modal
        title="新建生命周期策略"
        open={modalOpen}
        onOk={handleCreate}
        onCancel={() => setModalOpen(false)}
        okText="建立"
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ target: 'COLD', action: 'PURGE', retentionDays: 7 }}
        >
          <Form.Item name="name" label="策略名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：暂存数据 7 天清理" />
          </Form.Item>
          <Form.Item name="target" label="目标层级">
            <Select
              options={[
                { value: 'HOT', label: '热存储' },
                { value: 'WARM', label: '温存储' },
                { value: 'COLD', label: '冷存储' },
                { value: 'ARCHIVE', label: '归档' },
              ]}
            />
          </Form.Item>
          <Form.Item name="action" label="执行动作">
            <Select
              options={[
                { value: 'TTL', label: 'TTL' },
                { value: 'ARCHIVE', label: '归档' },
                { value: 'PURGE', label: '清理' },
                { value: 'COMPRESS', label: '压缩' },
              ]}
            />
          </Form.Item>
          <Form.Item name="retentionDays" label="保留天数">
            <InputNumber min={1} max={3650} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="scope" label="作用范围" rules={[{ required: true, message: '请输入作用范围' }]}>
            <Input placeholder="lake://base_data/staging/**" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="用于审计与回看的策略说明" />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default LifecyclePoliciesPage;
