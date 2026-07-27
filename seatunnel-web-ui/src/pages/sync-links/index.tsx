﻿﻿import { PageContainer } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ApiOutlined,
  ArrowRightOutlined,
  DatabaseOutlined,
  EyeOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import React, { useEffect, useMemo, useState } from 'react';
import './index.less';
import {
  fetchSyncLinkDetail,
  fetchSyncLinks,
  toggleSyncLinkStatus,
} from './service';
import type {
  LinkHealth,
  LinkType,
  LinkStatus,
  SyncLinkHealthDetail,
  SyncLinkRecord,
} from './types';

const healthColor: Record<LinkHealth, string> = {
  HEALTHY: 'green',
  WARNING: 'gold',
  FAILED: 'red',
  UNKNOWN: 'default',
};

const healthLabel: Record<LinkHealth, string> = {
  HEALTHY: '健康',
  WARNING: '告警',
  FAILED: '异常',
  UNKNOWN: '未知',
};

const statusColor: Record<LinkStatus, string> = {
  ONLINE: 'green',
  OFFLINE: 'red',
  PAUSED: 'gold',
  DRAFT: 'default',
};

const statusLabel: Record<LinkStatus, string> = {
  ONLINE: '运行中',
  OFFLINE: '已下线',
  PAUSED: '已暂停',
  DRAFT: '草稿',
};

const SyncLinksPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<SyncLinkRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [linkType, setLinkType] = useState<LinkType | string>();
  const [health, setHealth] = useState<LinkHealth | string>();
  const [selected, setSelected] = useState<SyncLinkRecord>();
  const [detail, setDetail] = useState<SyncLinkHealthDetail>();

  const reload = async () => {
    setLoading(true);
    try {
      const response = await fetchSyncLinks({
        keyword,
        linkType: linkType as LinkType,
        health: health as LinkHealth,
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
      total: records.length,
      healthy: records.filter((r) => r.health === 'HEALTHY').length,
      warning: records.filter((r) => r.health === 'WARNING').length,
      failed: records.filter((r) => r.health === 'FAILED').length,
    };
  }, [records]);

  const handleView = async (record: SyncLinkRecord) => {
    setSelected(record);
    const response = await fetchSyncLinkDetail(record.id);
    if (response.code === 0) {
      setDetail(response.data);
    }
  };

  const handleToggle = async (record: SyncLinkRecord, next: 'ONLINE' | 'PAUSED' | 'OFFLINE') => {
    const response = await toggleSyncLinkStatus(record.id, next);
    if (response.code === 0) {
      message.success(`链路已切换为${statusLabel[next]}`);
      reload();
    }
  };

  const columns: ColumnsType<SyncLinkRecord> = [
    {
      title: '链路名称',
      dataIndex: 'name',
      render: (value, record) => (
        <Button type="link" onClick={() => handleView(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '类型',
      dataIndex: 'linkType',
      width: 100,
      render: (value: SyncLinkRecord['linkType']) => (
        <Tag color={value === 'BATCH' ? 'blue' : value === 'STREAM' ? 'purple' : 'cyan'}>
          {value === 'BATCH' ? '离线' : value === 'STREAM' ? '实时' : '文件'}
        </Tag>
      ),
    },
    {
      title: '源 → 目标',
      dataIndex: 'source',
      render: (_, record) => (
        <Space>
          <span>{record.source}</span>
          <ArrowRightOutlined />
          <span>{record.target}</span>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: LinkStatus) => <Tag color={statusColor[value]}>{statusLabel[value]}</Tag>,
    },
    {
      title: '健康度',
      dataIndex: 'health',
      width: 220,
      render: (_, record) => (
        <Space direction="vertical" size={0} style={{ width: 200 }}>
          <Tag color={healthColor[record.health]}>{healthLabel[record.health]}</Tag>
          <Progress
            percent={record.healthScore}
            size="small"
            status={
              record.health === 'FAILED'
                ? 'exception'
                : record.health === 'WARNING'
                ? 'active'
                : 'success'
            }
          />
        </Space>
      ),
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
      width: 220,
      render: (_, record) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => handleView(record)}>
            详情
          </Button>
          {record.status === 'ONLINE' ? (
            <Button
              size="small"
              icon={<PauseCircleOutlined />}
              onClick={() => handleToggle(record, 'PAUSED')}
            >
              暂停
            </Button>
          ) : (
            <Tooltip title="切换到运行中">
              <Button
                size="small"
                type="primary"
                icon={<PlayCircleOutlined />}
                onClick={() => handleToggle(record, 'ONLINE')}
              >
                启动
              </Button>
            </Tooltip>
          )}
          <Button size="small" onClick={() => history.push(record.jobRef)}>
            任务
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="引接链路管理"
      subTitle="统一展示来源、目标、任务、路由和健康状态"
      extra={[
        <Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>,
      ]}
    >
      <Row gutter={[16, 16]} className="sync-links-stats">
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="链路总数" value={total} prefix={<DatabaseOutlined />} />
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="健康" value={stats.healthy} prefix={<ApiOutlined />} valueStyle={{ color: '#16a34a' }} />
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="告警" value={stats.warning} valueStyle={{ color: '#d97706' }} />
          </Card>
        </Col>
        <Col xs={24} md={6}>
          <Card>
            <Statistic title="异常" value={stats.failed} valueStyle={{ color: '#dc2626' }} />
          </Card>
        </Col>
      </Row>
      <Card className="sync-links-list" title="引接链路清单">
        <Space wrap style={{ marginBottom: 16 }}>
          <Select
            allowClear
            placeholder="类型"
            style={{ width: 120 }}
            value={linkType}
            onChange={setLinkType}
            options={[
              { value: 'BATCH', label: '离线' },
              { value: 'STREAM', label: '实时' },
              { value: 'FILE', label: '文件' },
            ]}
          />
          <Select
            allowClear
            placeholder="健康度"
            style={{ width: 120 }}
            value={health}
            onChange={setHealth}
            options={[
              { value: 'HEALTHY', label: '健康' },
              { value: 'WARNING', label: '告警' },
              { value: 'FAILED', label: '异常' },
            ]}
          />
          <Select
            allowClear
            showSearch
            placeholder="按名称筛选"
            style={{ width: 240 }}
            value={keyword || undefined}
            onChange={setKeyword}
            suffixIcon={<SearchOutlined />}
            options={records.map((record) => ({ value: record.name, label: record.name }))}
          />
        </Space>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={records}
          columns={columns}
          pagination={{ pageSize: 10, showTotal: (sum) => `共 ${sum} 条链路` }}
        />
      </Card>
      <Drawer
        title="链路健康详情"
        width={640}
        open={Boolean(selected)}
        onClose={() => {
          setSelected(undefined);
          setDetail(undefined);
        }}
      >
        {selected ? (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="名称" span={2}>
                {selected.name}
              </Descriptions.Item>
              <Descriptions.Item label="链路类型">{selected.linkType}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor[selected.status]}>{statusLabel[selected.status]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="源">{selected.source}</Descriptions.Item>
              <Descriptions.Item label="目标">{selected.target}</Descriptions.Item>
              <Descriptions.Item label="带宽配额" span={2}>
                {selected.bandwidthQuota} MB/s
              </Descriptions.Item>
              <Descriptions.Item label="优先级">{selected.priority}</Descriptions.Item>
              <Descriptions.Item label="健康分">{selected.healthScore}</Descriptions.Item>
              <Descriptions.Item label="责任人">{selected.owner}</Descriptions.Item>
              <Descriptions.Item label="更新时间">{selected.updatedAt}</Descriptions.Item>
              <Descriptions.Item label="说明" span={2}>
                {selected.description}
              </Descriptions.Item>
              {selected.lastIssue ? (
                <Descriptions.Item label="最近问题" span={2}>
                  <Tag color="red">{selected.lastIssue}</Tag>
                </Descriptions.Item>
              ) : null}
            </Descriptions>
            {detail ? (
              <>
                <Card title="实时指标" size="small" style={{ marginTop: 16 }}>
                  <Row gutter={16}>
                    <Col span={6}>
                      <Statistic title="吞吐 MB/s" value={detail.metrics.throughput} />
                    </Col>
                    <Col span={6}>
                      <Statistic title="时延 ms" value={detail.metrics.latencyMs} />
                    </Col>
                    <Col span={6}>
                      <Statistic title="成功率" value={detail.metrics.successRate} suffix="%" />
                    </Col>
                    <Col span={6}>
                      <Statistic title="积压" value={detail.metrics.backlog} />
                    </Col>
                  </Row>
                </Card>
                <Card title="事件时间线" size="small" style={{ marginTop: 16 }}>
                  {detail.timeline.map((item) => (
                    <div key={`${item.time}-${item.event}`} className="sync-links-timeline-row">
                      <span className="sync-links-timeline-time">{item.time}</span>
                      <Tag
                        color={
                          item.severity === 'ERROR'
                            ? 'red'
                            : item.severity === 'WARN'
                            ? 'gold'
                            : 'blue'
                        }
                      >
                        {item.severity}
                      </Tag>
                      <span>{item.event}</span>
                    </div>
                  ))}
                </Card>
                <Card title="建议" size="small" style={{ marginTop: 16 }}>
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={detail.recommendation}
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

export default SyncLinksPage;
