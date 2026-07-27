import { PageContainer } from '@ant-design/pro-components';
import {
  CloudDownloadOutlined,
  EyeOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
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
import { fetchReports, generateReport, previewReport } from './service';
import type {
  CollectionReportRecord,
  ReportFormat,
  ReportPreview,
  ReportSource,
  ReportStatus,
} from './types';

const statusColor: Record<ReportStatus, string> = {
  DRAFT: 'default',
  GENERATING: 'gold',
  READY: 'green',
  FAILED: 'red',
};

const statusLabel: Record<ReportStatus, string> = {
  DRAFT: '草稿',
  GENERATING: '生成中',
  READY: '已就绪',
  FAILED: '失败',
};

const formatColor: Record<ReportFormat, string> = {
  PDF: 'red',
  WORD: 'blue',
  EXCEL: 'green',
  CSV: 'cyan',
};

const sourceLabel: Record<ReportSource, string> = {
  FORM_RESPONSE: '采报表单',
  BATCH_TASK: '离线任务',
  STREAM_TASK: '实时任务',
  MANUAL: '人工汇总',
};

const CollectionReportsPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<CollectionReportRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [format, setFormat] = useState<ReportFormat>();
  const [status, setStatus] = useState<ReportStatus>();
  const [modalOpen, setModalOpen] = useState(false);
  const [previewing, setPreviewing] = useState<CollectionReportRecord>();
  const [preview, setPreview] = useState<ReportPreview>();
  const [form] = Form.useForm();

  const reload = async () => {
    setLoading(true);
    try {
      const response = await fetchReports({ keyword, format, status, pageNo: 1, pageSize: 50 });
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
      ready: records.filter((r) => r.status === 'READY').length,
      generating: records.filter((r) => r.status === 'GENERATING').length,
      failed: records.filter((r) => r.status === 'FAILED').length,
    };
  }, [records]);

  const handlePreview = async (record: CollectionReportRecord) => {
    setPreviewing(record);
    const response = await previewReport(record.id);
    if (response.code === 0) {
      setPreview(response.data);
    } else {
      setPreview(undefined);
    }
  };

  const handleGenerate = async () => {
    const values = await form.validateFields();
    const response = await generateReport({
      name: values.name,
      source: values.source,
      format: values.format,
      relatedForm: values.relatedForm,
      description: values.description || '由前端生成',
    });
    if (response.code === 0) {
      message.success('已提交生成任务，结果稍后回写到列表');
      setModalOpen(false);
      form.resetFields();
      reload();
    }
  };

  const columns: ColumnsType<CollectionReportRecord> = [
    {
      title: '报告名称',
      dataIndex: 'name',
      render: (value, record) => (
        <Button type="link" icon={<FileTextOutlined />} onClick={() => handlePreview(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 110,
      render: (value: ReportSource) => <Tag>{sourceLabel[value]}</Tag>,
    },
    {
      title: '格式',
      dataIndex: 'format',
      width: 90,
      render: (value: ReportFormat) => <Tag color={formatColor[value]}>{value}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: ReportStatus) => <Tag color={statusColor[value]}>{statusLabel[value]}</Tag>,
    },
    {
      title: '记录数',
      dataIndex: 'rowCount',
      width: 100,
    },
    {
      title: '责任人',
      dataIndex: 'owner',
      width: 100,
    },
    {
      title: '生成时间',
      dataIndex: 'generatedAt',
      width: 170,
    },
    {
      title: '操作',
      width: 200,
      render: (_, record) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => handlePreview(record)}>
            预览
          </Button>
          <Button
            size="small"
            type="primary"
            ghost
            icon={<CloudDownloadOutlined />}
            disabled={record.status !== 'READY'}
          >
            导出
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="采集报告管理"
      subTitle="报告生成、预览、导出和分发记录"
      extra={[
        <Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>,
        <Button
          key="generate"
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setModalOpen(true)}
        >
          新建报告
        </Button>,
      ]}
    >
      <Row gutter={[16, 16]} className="reporting-reports-stats">
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="报告总数" value={total} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="已就绪" value={stats.ready} valueStyle={{ color: '#16a34a' }} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card>
            <Statistic title="生成中 / 失败" value={stats.generating + stats.failed} valueStyle={{ color: '#d97706' }} />
          </Card>
        </Col>
      </Row>
      <Card title="报告清单">
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
            style={{ width: 120 }}
            value={format}
            onChange={setFormat}
            options={[
              { value: 'PDF', label: 'PDF' },
              { value: 'WORD', label: 'Word' },
              { value: 'EXCEL', label: 'Excel' },
              { value: 'CSV', label: 'CSV' },
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
              { value: 'GENERATING', label: '生成中' },
              { value: 'READY', label: '已就绪' },
              { value: 'FAILED', label: '失败' },
            ]}
          />
        </Space>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={records}
          columns={columns}
          pagination={{ pageSize: 10, showTotal: (sum) => `共 ${sum} 条报告` }}
        />
      </Card>
      <Modal
        title="新建报告"
        open={modalOpen}
        onOk={handleGenerate}
        onCancel={() => setModalOpen(false)}
        okText="生成"
      >
        <Form form={form} layout="vertical" initialValues={{ source: 'FORM_RESPONSE', format: 'PDF' }}>
          <Form.Item name="name" label="报告名称" rules={[{ required: true, message: '请输入报告名称' }]}>
            <Input placeholder="例如：装备台账月报" />
          </Form.Item>
          <Form.Item name="source" label="数据来源">
            <Select
              options={[
                { value: 'FORM_RESPONSE', label: '采报表单' },
                { value: 'BATCH_TASK', label: '离线任务' },
                { value: 'STREAM_TASK', label: '实时任务' },
                { value: 'MANUAL', label: '人工汇总' },
              ]}
            />
          </Form.Item>
          <Form.Item name="format" label="输出格式">
            <Select
              options={[
                { value: 'PDF', label: 'PDF' },
                { value: 'WORD', label: 'Word' },
                { value: 'EXCEL', label: 'Excel' },
                { value: 'CSV', label: 'CSV' },
              ]}
            />
          </Form.Item>
          <Form.Item name="relatedForm" label="关联采报表">
            <Input placeholder="可选，例如：装备台账采集表" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="用于审计和回看的报告说明" />
          </Form.Item>
        </Form>
      </Modal>
      <Drawer
        title="报告预览"
        width={720}
        open={Boolean(previewing)}
        onClose={() => {
          setPreviewing(undefined);
          setPreview(undefined);
        }}
      >
        {previewing ? (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="名称" span={2}>
                {previewing.name}
              </Descriptions.Item>
              <Descriptions.Item label="来源">{sourceLabel[previewing.source]}</Descriptions.Item>
              <Descriptions.Item label="格式">
                <Tag color={formatColor[previewing.format]}>{previewing.format}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor[previewing.status]}>{statusLabel[previewing.status]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="生成时间">{previewing.generatedAt}</Descriptions.Item>
              <Descriptions.Item label="说明" span={2}>
                {previewing.description}
              </Descriptions.Item>
            </Descriptions>
            {preview ? (
              preview.sections.map((section) => (
                <Card key={section.title} title={section.title} size="small" style={{ marginTop: 12 }}>
                  <table className="reporting-reports-preview-table">
                    <tbody>
                      {section.rows.map((row, index) => (
                        <tr key={`${section.title}-${index}`}>
                          <th>{row[0]}</th>
                          <td>{row[1]}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </Card>
              ))
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无预览内容" style={{ marginTop: 24 }} />
            )}
          </>
        ) : null}
      </Drawer>
    </PageContainer>
  );
};

export default CollectionReportsPage;
