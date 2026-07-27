import { PageContainer } from '@ant-design/pro-components';
import {
  ApiOutlined,
  HistoryOutlined,
  ReloadOutlined,
  SafetyOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Input,
  List,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Timeline,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useMemo, useState } from 'react';
import './index.less';
import {
  fetchFaultEvidence,
  fetchFaults,
  retryFault,
} from './service';
import type {
  FaultEvidence,
  FaultRecord,
  FaultSeverity,
  FaultStatus,
} from './types';

const severityColor: Record<FaultSeverity, string> = {
  INFO: 'blue',
  WARNING: 'gold',
  ERROR: 'red',
  CRITICAL: 'magenta',
};

const severityLabel: Record<FaultSeverity, string> = {
  INFO: '信息',
  WARNING: '告警',
  ERROR: '错误',
  CRITICAL: '严重',
};

const statusColor: Record<FaultStatus, string> = {
  OPEN: 'red',
  INVESTIGATING: 'gold',
  RECOVERED: 'green',
  MITIGATED: 'cyan',
};

const statusLabel: Record<FaultStatus, string> = {
  OPEN: '未处理',
  INVESTIGATING: '排查中',
  RECOVERED: '已恢复',
  MITIGATED: '已缓解',
};

const DiagnosticsPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<FaultRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [severity, setSeverity] = useState<FaultSeverity>();
  const [status, setStatus] = useState<FaultStatus>();
  const [selected, setSelected] = useState<FaultRecord>();
  const [evidence, setEvidence] = useState<FaultEvidence>();

  const reload = async () => {
    setLoading(true);
    try {
      const response = await fetchFaults({
        keyword,
        severity,
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
      open: records.filter((r) => r.status === 'OPEN').length,
      critical: records.filter((r) => r.severity === 'CRITICAL').length,
      investigating: records.filter((r) => r.status === 'INVESTIGATING').length,
    }),
    [records],
  );

  const handleView = async (record: FaultRecord) => {
    setSelected(record);
    const response = await fetchFaultEvidence(record.id);
    if (response.code === 0) {
      setEvidence(response.data);
    } else {
      setEvidence(undefined);
    }
  };

  const handleRetry = async (record: FaultRecord) => {
    const response = await retryFault(record.id);
    if (response.code === 0) {
      message.success('已发起安全重试，记录状态已更新');
      reload();
      handleView({ ...record, status: 'INVESTIGATING' });
    }
  };

  const columns: ColumnsType<FaultRecord> = [
    {
      title: '故障',
      dataIndex: 'title',
      render: (value, record) => (
        <Button type="link" onClick={() => handleView(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '严重度',
      dataIndex: 'severity',
      width: 100,
      render: (value: FaultSeverity) => (
        <Tag color={severityColor[value]}>{severityLabel[value]}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: FaultStatus) => <Tag color={statusColor[value]}>{statusLabel[value]}</Tag>,
    },
    {
      title: '分类',
      dataIndex: 'classification',
      width: 160,
    },
    {
      title: '关联任务',
      dataIndex: 'relatedTask',
      width: 200,
    },
    {
      title: '首次出现',
      dataIndex: 'firstSeen',
      width: 170,
    },
    {
      title: '最近出现',
      dataIndex: 'lastSeen',
      width: 170,
    },
    {
      title: '操作',
      width: 200,
      render: (_, record) => (
        <Space>
          <Button size="small" onClick={() => handleView(record)}>
            诊断
          </Button>
          <Button
            size="small"
            type="primary"
            ghost
            icon={<SafetyOutlined />}
            onClick={() => handleRetry(record)}
            disabled={record.status === 'RECOVERED'}
          >
            安全重试
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="故障辅助"
      subTitle="故障时间线、证据、建议和安全重试"
      extra={[
        <Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>,
      ]}
    >
      <Alert
        type="warning"
        showIcon
        message="本页面为有限实现（PoC）"
        description="故障诊断规则与自动修复为固定策略，未覆盖通用 AI 自动根因定位与自动修复。"
        style={{ marginBottom: 16 }}
      />
      <Row gutter={[16, 16]} className="operations-diagnostics-stats">
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="故障总数" value={total} prefix={<ApiOutlined />} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="未处理" value={stats.open} valueStyle={{ color: '#dc2626' }} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="严重 / 排查中" value={`${stats.critical} / ${stats.investigating}`} />
          </Card>
        </Col>
      </Row>
      <Card title="故障清单">
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search
            placeholder="按故障标题筛选"
            allowClear
            style={{ width: 240 }}
            onSearch={setKeyword}
          />
          <Select
            allowClear
            placeholder="严重度"
            style={{ width: 120 }}
            value={severity}
            onChange={setSeverity}
            options={[
              { value: 'INFO', label: '信息' },
              { value: 'WARNING', label: '告警' },
              { value: 'ERROR', label: '错误' },
              { value: 'CRITICAL', label: '严重' },
            ]}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 120 }}
            value={status}
            onChange={setStatus}
            options={[
              { value: 'OPEN', label: '未处理' },
              { value: 'INVESTIGATING', label: '排查中' },
              { value: 'RECOVERED', label: '已恢复' },
              { value: 'MITIGATED', label: '已缓解' },
            ]}
          />
        </Space>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={records}
          columns={columns}
          pagination={{ pageSize: 10, showTotal: (sum) => `共 ${sum} 个故障` }}
        />
      </Card>
      <Drawer
        title="故障诊断证据"
        width={720}
        open={Boolean(selected)}
        onClose={() => {
          setSelected(undefined);
          setEvidence(undefined);
        }}
      >
        {selected ? (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="故障" span={2}>{selected.title}</Descriptions.Item>
              <Descriptions.Item label="严重度">
                <Tag color={severityColor[selected.severity]}>
                  {severityLabel[selected.severity]}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor[selected.status]}>{statusLabel[selected.status]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="分类">{selected.classification}</Descriptions.Item>
              <Descriptions.Item label="关联任务">{selected.relatedTask}</Descriptions.Item>
              <Descriptions.Item label="首次出现">{selected.firstSeen}</Descriptions.Item>
              <Descriptions.Item label="最近出现">{selected.lastSeen}</Descriptions.Item>
              <Descriptions.Item label="说明" span={2}>{selected.description}</Descriptions.Item>
            </Descriptions>
            {evidence ? (
              <>
                <Card
                  size="small"
                  title={
                    <span>
                      <HistoryOutlined /> 时间线
                    </span>
                  }
                  style={{ marginTop: 12 }}
                >
                  <Timeline
                    items={evidence.timeline.map((item) => ({
                      color:
                        item.severity === 'CRITICAL'
                          ? 'red'
                          : item.severity === 'ERROR'
                          ? 'volcano'
                          : item.severity === 'WARNING'
                          ? 'gold'
                          : 'blue',
                      children: (
                        <Space direction="vertical" size={0}>
                          <strong>{item.time}</strong>
                          <span>{item.event}</span>
                        </Space>
                      ),
                    }))}
                  />
                </Card>
                <Card size="small" title="建议" style={{ marginTop: 12 }}>
                  <p>{evidence.recommendation}</p>
                </Card>
                <Card
                  size="small"
                  title="安全重试清单"
                  style={{ marginTop: 12 }}
                >
                  <List
                    size="small"
                    dataSource={evidence.retryPlan}
                    renderItem={(item) => <List.Item>· {item}</List.Item>}
                  />
                </Card>
              </>
            ) : null}
          </>
        ) : null}
      </Drawer>
    </PageContainer>
  );
};

export default DiagnosticsPage;
