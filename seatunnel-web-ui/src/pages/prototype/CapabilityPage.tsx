import {
  ApiOutlined,
  CloudSyncOutlined,
  DatabaseOutlined,
  EyeOutlined,
  FileSearchOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useLocation } from '@umijs/max';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useState } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MarkerType,
  MiniMap,
  type Edge,
  type Node,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { findPageMeta } from '@/prototype/registry';
import {
  readPrototypeRecords,
  writePrototypeRecords,
} from '@/prototype/store';
import type { PrototypeRecord } from '@/prototype/types';
import './index.less';

const actionLabels: Record<string, string> = {
  forms: '创建采报模板',
  reports: '生成采集报告',
  discovery: '重新扫描资产',
  'cloud-edge': '下发云边任务',
  'edge-access': '新增边缘接入',
  links: '登记引接链路',
  topology: '刷新拓扑',
  diagnostics: '安全重试',
  'lake-resources': '登记入湖资源',
  lifecycle: '新建生命周期策略',
  'logical-access': '新建逻辑映射',
  reused: '新增演示记录',
};

const secondaryLabels: Record<string, string> = {
  forms: '模拟填报并提交',
  reports: '预览与导出',
  discovery: '查看字段画像',
  'cloud-edge': '模拟断网 / 恢复',
  'edge-access': '连通测试',
  links: '查看健康详情',
  topology: '高亮影响链路',
  diagnostics: '查看诊断证据',
  'lake-resources': '测试湖连接',
  lifecycle: '立即执行策略',
  'logical-access': '查询预览',
  reused: '执行并切换状态',
};

const statusColors: Record<string, string> = {
  运行中: 'green',
  已完成: 'blue',
  待发布: 'gold',
  已发布: 'cyan',
  已中断: 'red',
};

const topologyNodes: Node[] = [
  {
    id: 'source',
    position: { x: 40, y: 80 },
    data: { label: '装备主数据库' },
    style: { borderColor: '#2563eb', borderRadius: 10 },
  },
  {
    id: 'job',
    position: { x: 280, y: 80 },
    data: { label: 'Aircas 离线任务' },
    style: { borderColor: '#9333ea', borderRadius: 10 },
  },
  {
    id: 'lake',
    position: { x: 540, y: 80 },
    data: { label: '基础数据湖' },
    style: { borderColor: '#16a34a', borderRadius: 10 },
  },
];

const topologyEdges: Edge[] = [
  {
    id: 'source-job',
    source: 'source',
    target: 'job',
    animated: true,
    markerEnd: { type: MarkerType.ArrowClosed },
  },
  {
    id: 'job-lake',
    source: 'job',
    target: 'lake',
    animated: true,
    markerEnd: { type: MarkerType.ArrowClosed },
  },
];

const CapabilityPage: React.FC = () => {
  const location = useLocation();
  const meta = findPageMeta(location.pathname);
  const [records, setRecords] = useState<PrototypeRecord[]>(() =>
    meta ? readPrototypeRecords(meta) : [],
  );
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string>();
  const [selected, setSelected] = useState<PrototypeRecord>();
  const [modalOpen, setModalOpen] = useState(false);
  const [networkOffline, setNetworkOffline] = useState(false);
  const [activeNode, setActiveNode] = useState<string>();
  const [form] = Form.useForm();

  const persist = (next: PrototypeRecord[]) => {
    if (!meta) return;
    setRecords(next);
    writePrototypeRecords(meta, next);
  };

  const filtered = records.filter(
    (record) =>
      (!keyword || record.name.toLowerCase().includes(keyword.toLowerCase())) &&
      (!status || record.status === status),
  );

  if (!meta) {
    return <Empty description="未找到原型页面注册信息" />;
  }

  const handlePrimary = () => {
    if (['discovery', 'topology', 'diagnostics'].includes(meta.prototypeKind)) {
      persist(
        records.map((record, index) => ({
          ...record,
          status: index === 0 ? '运行中' : record.status,
          updatedAt: '2026-07-27 16:30',
        })),
      );
      message.success(`${actionLabels[meta.prototypeKind]}已执行`);
      return;
    }
    setModalOpen(true);
  };

  const handleSecondary = () => {
    if (meta.prototypeKind === 'cloud-edge') {
      const nextOffline = !networkOffline;
      setNetworkOffline(nextOffline);
      persist(
        records.map((record, index) =>
          index === 0
            ? { ...record, status: nextOffline ? '已中断' : '运行中' }
            : record,
        ),
      );
      message.success(nextOffline ? '已模拟断网，数据进入暂存区' : '网络已恢复，续传任务已启动');
      return;
    }
    if (meta.prototypeKind === 'topology') {
      setActiveNode(activeNode ? undefined : 'job');
      message.success(activeNode ? '已取消高亮' : '已高亮上下游影响链路');
      return;
    }
    if (meta.prototypeKind === 'logical-access') {
      message.success('查询预览完成：返回 20 条受控模拟结果');
      return;
    }
    if (meta.prototypeKind === 'reports') {
      message.success('报告预览已打开，导出任务已加入下载队列');
      setSelected(records[0]);
      return;
    }
    if (meta.prototypeKind === 'diagnostics') {
      setSelected(records[0]);
      message.success('已加载固定诊断规则和运行证据');
      return;
    }
    persist(
      records.map((record, index) =>
        index === 0
          ? {
              ...record,
              status: record.status === '运行中' ? '已完成' : '运行中',
              updatedAt: '2026-07-27 16:35',
            }
          : record,
      ),
    );
    message.success(`${secondaryLabels[meta.prototypeKind]}成功`);
  };

  const handleCreate = async () => {
    const values = await form.validateFields();
    const record: PrototypeRecord = {
      id: `${meta.id}-${Date.now()}`,
      name: values.name,
      type: values.type || meta.secondMenu.replace('管理', ''),
      status: meta.prototypeKind === 'forms' ? '已发布' : '运行中',
      owner: '当前 SSO 用户',
      updatedAt: '2026-07-27 16:40',
      description: values.description || meta.description,
      progress: 10,
    };
    persist([record, ...records]);
    setModalOpen(false);
    form.resetFields();
    message.success(
      meta.prototypeKind === 'forms'
        ? '模板已创建并发布，可继续模拟填报'
        : '原型记录已创建并进入状态闭环',
    );
  };

  const columns: ColumnsType<PrototypeRecord> = [
    {
      title: '名称',
      dataIndex: 'name',
      render: (value, record) => (
        <Button type="link" onClick={() => setSelected(record)}>
          {value}
        </Button>
      ),
    },
    { title: '类型 / 协议', dataIndex: 'type', width: 150 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value) => <Tag color={statusColors[value] || 'default'}>{value}</Tag>,
    },
    {
      title: '进度',
      dataIndex: 'progress',
      width: 150,
      render: (value) => <Progress percent={value} size="small" />,
    },
    { title: '责任人', dataIndex: 'owner', width: 120 },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170 },
    {
      title: '操作',
      width: 180,
      render: (_, record) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => setSelected(record)}>
            详情
          </Button>
          <Button
            size="small"
            type="primary"
            ghost
            onClick={() => {
              persist(
                records.map((item) =>
                  item.id === record.id
                    ? {
                        ...item,
                        status: item.status === '运行中' ? '已完成' : '运行中',
                        progress: item.status === '运行中' ? 100 : 65,
                      }
                    : item,
                ),
              );
              message.success('状态已更新');
            }}
          >
            状态切换
          </Button>
        </Space>
      ),
    },
  ];

  const isTopology = meta.prototypeKind === 'topology';
  const isDiscovery = meta.prototypeKind === 'discovery';

  return (
    <PageContainer
      title={meta.secondMenu}
      subTitle={meta.description}
      className="prototype-capability-page"
      extra={[
        <Button key="secondary" onClick={handleSecondary}>
          {secondaryLabels[meta.prototypeKind]}
        </Button>,
        <Button
          key="primary"
          type="primary"
          icon={
            ['discovery', 'topology'].includes(meta.prototypeKind) ? (
              <ReloadOutlined />
            ) : (
              <PlusOutlined />
            )
          }
          onClick={handlePrimary}
        >
          {actionLabels[meta.prototypeKind]}
        </Button>,
      ]}
    >
      <Row gutter={[16, 16]} className="prototype-stat-row">
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="记录总数" value={records.length} prefix={<DatabaseOutlined />} />
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card>
            <Statistic
              title="运行 / 发布"
              value={records.filter((item) => ['运行中', '已发布'].includes(item.status)).length}
              prefix={<PlayCircleOutlined />}
            />
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="关联指标" value={meta.requirementIds.length} prefix={<FileSearchOutlined />} />
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="技术模块" value={meta.technicalModules.length} prefix={<ApiOutlined />} />
          </Card>
        </Col>
      </Row>

      {isDiscovery ? (
        <Card className="prototype-special-card" title="OpenMetadata 数据探查视图">
          <Tabs
            items={['字段结构', '数据画像', '质量结果'].map((label, index) => ({
              key: label,
              label,
              children: (
                <Descriptions bordered size="small" column={3}>
                  <Descriptions.Item label="当前资产">装备主数据</Descriptions.Item>
                  <Descriptions.Item label="扫描状态">已完成</Descriptions.Item>
                  <Descriptions.Item label="字段 / 规则">{24 + index * 7}</Descriptions.Item>
                  <Descriptions.Item label="说明" span={3}>
                    点击页签切换字段、画像和质量上下文；数据为 OpenMetadata 集成边界模拟。
                  </Descriptions.Item>
                </Descriptions>
              ),
            }))}
          />
        </Card>
      ) : null}

      {isTopology ? (
        <Card className="prototype-special-card" title="Aircas DAG + OpenMetadata 血缘">
          <div style={{ height: 260 }}>
            <ReactFlow
              nodes={topologyNodes.map((node) => ({
                ...node,
                style: {
                  ...node.style,
                  background: activeNode === node.id ? '#fef3c7' : '#fff',
                  boxShadow: activeNode === node.id ? '0 0 0 4px #facc15' : undefined,
                },
              }))}
              edges={topologyEdges.map((edge) => ({
                ...edge,
                style: { stroke: activeNode ? '#f59e0b' : '#64748b', strokeWidth: 2 },
              }))}
              fitView
              onNodeClick={(_, node) => {
                setActiveNode(node.id);
                if (node.id === 'job') history.push('/sync/batch-link-up');
              }}
            >
              <Background />
              <MiniMap />
              <Controls />
            </ReactFlow>
          </div>
        </Card>
      ) : null}

      <Card
        title="可交互业务清单"
        extra={
          <Space wrap>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="按名称筛选"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
            />
            <Select
              allowClear
              placeholder="状态筛选"
              style={{ width: 140 }}
              value={status}
              onChange={setStatus}
              options={['运行中', '待发布', '已完成', '已发布', '已中断'].map((value) => ({
                value,
                label: value,
              }))}
            />
          </Space>
        }
      >
        <Table rowKey="id" dataSource={filtered} columns={columns} pagination={false} />
      </Card>

      <Drawer
        title="详情与执行结果"
        width={560}
        open={Boolean(selected)}
        onClose={() => setSelected(undefined)}
        extra={
          <Button
            type="primary"
            icon={<CloudSyncOutlined />}
            onClick={() => {
              message.success('操作已执行，状态与时间线已更新');
              setSelected((current) =>
                current ? { ...current, status: '已完成', progress: 100 } : current,
              );
            }}
          >
            执行闭环
          </Button>
        }
      >
        {selected ? (
          <>
            <Descriptions bordered column={1}>
              <Descriptions.Item label="名称">{selected.name}</Descriptions.Item>
              <Descriptions.Item label="类型">{selected.type}</Descriptions.Item>
              <Descriptions.Item label="状态">{selected.status}</Descriptions.Item>
              <Descriptions.Item label="责任人">{selected.owner}</Descriptions.Item>
              <Descriptions.Item label="说明">{selected.description}</Descriptions.Item>
            </Descriptions>
            <Card size="small" title="点击触发的状态时间线" style={{ marginTop: 16 }}>
              <p>16:20 创建或加载记录</p>
              <p>16:30 执行规则、扫描或连通检查</p>
              <p>16:35 状态回传并产生页面反馈</p>
            </Card>
          </>
        ) : null}
      </Drawer>

      <Modal
        title={actionLabels[meta.prototypeKind]}
        open={modalOpen}
        onOk={handleCreate}
        onCancel={() => setModalOpen(false)}
        okText="确认并执行"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder={`请输入${meta.secondMenu}名称`} />
          </Form.Item>
          <Form.Item name="type" label="类型 / 协议">
            <Select
              options={['MySQL', 'Kafka', 'S3/OSS', 'MQTT', 'Modbus TCP'].map((value) => ({
                value,
                label: value,
              }))}
            />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default CapabilityPage;
