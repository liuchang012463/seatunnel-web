import { PageContainer } from '@ant-design/pro-components';
import {
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
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
  createLogicalMapping,
  fetchLogicalMappings,
  previewLogicalMapping,
} from './service';
import type {
  LogicalAccessPattern,
  LogicalMappingRecord,
  LogicalMappingStatus,
  LogicalPreviewResult,
} from './types';

const patternLabel: Record<LogicalAccessPattern, string> = {
  UNION: 'UNION',
  JOIN: 'JOIN',
  VIEW: 'VIEW',
  PASSTHROUGH: '直通',
};

const patternColor: Record<LogicalAccessPattern, string> = {
  UNION: 'blue',
  JOIN: 'purple',
  VIEW: 'cyan',
  PASSTHROUGH: 'gold',
};

const statusColor: Record<LogicalMappingStatus, string> = {
  DRAFT: 'default',
  ACTIVE: 'green',
  PAUSED: 'gold',
};

const statusLabel: Record<LogicalMappingStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '已激活',
  PAUSED: '已暂停',
};

const LogicalAccessPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<LogicalMappingRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [pattern, setPattern] = useState<LogicalAccessPattern>();
  const [status, setStatus] = useState<LogicalMappingStatus>();
  const [selected, setSelected] = useState<LogicalMappingRecord>();
  const [preview, setPreview] = useState<LogicalPreviewResult>();
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();

  const reload = async () => {
    setLoading(true);
    try {
      const response = await fetchLogicalMappings({
        keyword,
        pattern,
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

  const handlePreview = async (record: LogicalMappingRecord) => {
    setSelected(record);
    const response = await previewLogicalMapping(record.id);
    if (response.code === 0) {
      setPreview(response.data);
      message.success(response.data?.message || '查询预览完成');
    } else {
      setPreview(undefined);
    }
  };

  const handleCreate = async () => {
    const values = await form.validateFields();
    const response = await createLogicalMapping({
      name: values.name,
      pattern: values.pattern,
      sources: values.sources.split(/[\n,]/).map((item: string) => item.trim()).filter(Boolean),
      target: values.target,
      owner: '当前 SSO 用户',
      description: values.description || '新建立的逻辑映射',
    });
    if (response.code === 0) {
      message.success('逻辑映射已建立');
      setModalOpen(false);
      form.resetFields();
      reload();
    }
  };

  const columns: ColumnsType<LogicalMappingRecord> = [
    {
      title: '映射名称',
      dataIndex: 'name',
      render: (value, record) => (
        <Button type="link" icon={<SearchOutlined />} onClick={() => handlePreview(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '模式',
      dataIndex: 'pattern',
      width: 110,
      render: (value: LogicalAccessPattern) => (
        <Tag color={patternColor[value]}>{patternLabel[value]}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: LogicalMappingStatus) => (
        <Tag color={statusColor[value]}>{statusLabel[value]}</Tag>
      ),
    },
    {
      title: '目标',
      dataIndex: 'target',
      width: 200,
    },
    {
      title: '最近预览',
      dataIndex: 'lastPreviewedAt',
      width: 170,
    },
    {
      title: '预览行数',
      dataIndex: 'previewRowCount',
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
      width: 160,
      render: (_, record) => (
        <Space>
          <Button size="small" onClick={() => handlePreview(record)}>
            预览
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="逻辑入湖管理"
      subTitle="跨源元数据映射与逻辑访问入口"
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
          新建映射
        </Button>,
      ]}
    >
      <Alert
        type="warning"
        showIcon
        message="本页面为有限实现"
        description="数据虚拟化与联邦查询由独立组件承担，本页仅演示元数据映射、查询入口和受控模拟。"
        style={{ marginBottom: 16 }}
      />
      <Row gutter={[16, 16]} className="lake-logical-access-stats">
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="映射总数" value={total} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="已激活" value={stats.active} valueStyle={{ color: '#16a34a' }} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="已暂停" value={stats.paused} valueStyle={{ color: '#d97706' }} />
          </Card>
        </Col>
      </Row>
      <Card title="逻辑映射清单">
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search
            placeholder="按名称筛选"
            allowClear
            style={{ width: 240 }}
            onSearch={setKeyword}
          />
          <Select
            allowClear
            placeholder="模式"
            style={{ width: 120 }}
            value={pattern}
            onChange={setPattern}
            options={[
              { value: 'UNION', label: 'UNION' },
              { value: 'JOIN', label: 'JOIN' },
              { value: 'VIEW', label: 'VIEW' },
              { value: 'PASSTHROUGH', label: '直通' },
            ]}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 120 }}
            value={status}
            onChange={setStatus}
            options={[
              { value: 'DRAFT', label: '草稿' },
              { value: 'ACTIVE', label: '已激活' },
              { value: 'PAUSED', label: '已暂停' },
            ]}
          />
        </Space>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={records}
          columns={columns}
          pagination={{ pageSize: 10, showTotal: (sum) => `共 ${sum} 个映射` }}
        />
      </Card>
      <Drawer
        title="逻辑映射详情"
        width={720}
        open={Boolean(selected)}
        onClose={() => {
          setSelected(undefined);
          setPreview(undefined);
        }}
      >
        {selected ? (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="名称" span={2}>{selected.name}</Descriptions.Item>
              <Descriptions.Item label="模式">
                <Tag color={patternColor[selected.pattern]}>{patternLabel[selected.pattern]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor[selected.status]}>{statusLabel[selected.status]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="目标" span={2}>
                <code>{selected.target}</code>
              </Descriptions.Item>
              <Descriptions.Item label="数据源" span={2}>
                <Space wrap>
                  {selected.sources.map((source) => (
                    <Tag key={source}>{source}</Tag>
                  ))}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="说明" span={2}>{selected.description}</Descriptions.Item>
            </Descriptions>
            {preview ? (
              <Card size="small" title="查询预览" style={{ marginTop: 12 }}>
                <p>{preview.message}</p>
                <table className="lake-logical-access-preview-table">
                  <thead>
                    <tr>
                      {preview.columns.map((column) => (
                        <th key={column}>{column}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {preview.rows.map((row, index) => (
                      <tr key={`${preview.id}-row-${index}`}>
                        {row.map((cell, cellIndex) => (
                          <td key={`${preview.id}-row-${index}-cell-${cellIndex}`}>{cell}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Card>
            ) : (
              <Alert
                type="info"
                showIcon
                message="暂无预览结果"
                description="请在右上角点击“预览”查看受控模拟结果。"
                style={{ marginTop: 12 }}
              />
            )}
          </>
        ) : null}
      </Drawer>
      <Modal
        title="新建逻辑映射"
        open={modalOpen}
        onOk={handleCreate}
        onCancel={() => setModalOpen(false)}
        okText="建立"
      >
        <Form form={form} layout="vertical" initialValues={{ pattern: 'VIEW' }}>
          <Form.Item name="name" label="映射名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：装备主数据逻辑视图" />
          </Form.Item>
          <Form.Item name="pattern" label="模式">
            <Select
              options={[
                { value: 'UNION', label: 'UNION' },
                { value: 'JOIN', label: 'JOIN' },
                { value: 'VIEW', label: 'VIEW' },
                { value: 'PASSTHROUGH', label: '直通' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="sources"
            label="数据源（每行一个或逗号分隔）"
            rules={[{ required: true, message: '请至少输入一个数据源' }]}
          >
            <Input.TextArea
              rows={3}
              placeholder="例如：装备主库.装备表, 装备主库.单位表"
            />
          </Form.Item>
          <Form.Item name="target" label="目标地址" rules={[{ required: true, message: '请输入目标地址' }]}>
            <Input placeholder="logical://equipment_view" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="用于审计和回看的映射说明" />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default LogicalAccessPage;
