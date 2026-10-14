import { ReloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Card, Col, Empty, Row, Select, Space, Spin, Statistic } from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { fetchChartData, fetchSummaryData } from '@/pages/metrics/api';
import LineChart from '@/pages/metrics/LineChart';
import type { ChartData, SummaryData, TaskType, TimeRange } from '@/pages/metrics/types';
import { calculateSuccessRate, taskTypeOptions, transformChartData } from '@/pages/metrics/utils';
import './index.less';

const timeRangeOptions: { label: string; value: TimeRange }[] = [
  { label: '最近 24 小时', value: 'H24' },
  { label: '最近 7 天', value: 'D7' },
  { label: '最近 30 天', value: 'D30' },
];

type MetricCard = {
  title: string;
  value: number | string;
  suffix?: string;
  note: string;
  tone: 'blue' | 'cyan' | 'green' | 'orange';
};

const IngestionDashboardPage: React.FC = () => {
  const [taskType, setTaskType] = useState<TaskType>('BATCH');
  const [timeRange, setTimeRange] = useState<TimeRange>('D30');
  const [summaryData, setSummaryData] = useState<SummaryData>();
  const [chartData, setChartData] = useState<ChartData>();
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string>();
  const [updatedAt, setUpdatedAt] = useState<string>();
  const [loadedQueryKey, setLoadedQueryKey] = useState<string>();
  const requestGeneration = useRef(0);

  const refreshData = useCallback(async () => {
    const generation = requestGeneration.current + 1;
    requestGeneration.current = generation;
    setLoading(true);
    setErrorMessage(undefined);

    try {
      const [summary, apiData] = await Promise.all([
        fetchSummaryData(timeRange, taskType),
        fetchChartData(timeRange, taskType),
      ]);
      const nextCharts: ChartData = {
        recordsTrend: transformChartData(apiData.recordsTrend as any, timeRange),
        bytesTrend: transformChartData(apiData.bytesTrend as any, timeRange),
        recordsSpeedTrend: transformChartData(apiData.recordsSpeedTrend as any, timeRange),
        bytesSpeedTrend: transformChartData(apiData.bytesSpeedTrend as any, timeRange),
      };

      if (requestGeneration.current !== generation) {
        return;
      }

      setSummaryData(summary);
      setChartData(nextCharts);
      setUpdatedAt(new Date().toLocaleTimeString('zh-CN', { hour12: false }));
      setLoadedQueryKey(`${timeRange}:${taskType}`);
    } catch (error) {
      if (requestGeneration.current === generation) {
        setErrorMessage(error instanceof Error ? error.message : '获取任务概览数据失败，请重试');
      }
    } finally {
      if (requestGeneration.current === generation) {
        setLoading(false);
      }
    }
  }, [taskType, timeRange]);

  useEffect(() => {
    refreshData();
    return () => {
      requestGeneration.current += 1;
    };
  }, [refreshData]);

  const isStreaming = taskType === 'STREAM';
  const isFileTransfer = taskType === 'FILE_TRANSFER';
  const hasData = Boolean(
    summaryData && chartData && loadedQueryKey === `${timeRange}:${taskType}`,
  );
  const completedTasks = summaryData
    ? summaryData.successTasks + summaryData.failedTasks + summaryData.stoppedTasks
    : 0;
  const successRate = summaryData
    ? calculateSuccessRate(summaryData.successTasks, completedTasks)
    : null;

  const metricCards = useMemo<MetricCard[]>(() => {
    if (!summaryData) {
      return [];
    }

    if (isStreaming) {
      return [
        {
          title: '写入记录量',
          value: summaryData.totalRecords || 0,
          suffix: summaryData.totalRecordsUnit || '条',
          note: '统计范围内新增写入',
          tone: 'blue',
        },
        {
          title: '写入数据量',
          value: summaryData.totalBytes || 0,
          suffix: summaryData.totalBytesUnit || 'B',
          note: '统计范围内新增写入',
          tone: 'cyan',
        },
        {
          title: '运行中任务',
          value: summaryData.runningTasks || 0,
          suffix: '个',
          note: `采集到 ${summaryData.totalTasks || 0} 个任务实例指标`,
          tone: 'green',
        },
        {
          title: '平均记录延迟',
          value: summaryData.totalTasks ? summaryData.avgRecordDelay || 0 : '—',
          suffix: summaryData.totalTasks ? 'ms' : undefined,
          note: '来自实时任务执行指标',
          tone: 'orange',
        },
      ];
    }

    if (isFileTransfer) {
      return [
        {
          title: '传输数据量',
          value: summaryData.totalBytes || 0,
          suffix: summaryData.totalBytesUnit || 'B',
          note: '统计范围内完成传输',
          tone: 'cyan',
        },
        {
          title: '执行任务数',
          value: summaryData.totalTasks || 0,
          suffix: '个',
          note: '有指标记录或仍在运行的实例',
          tone: 'blue',
        },
        {
          title: '完成任务数',
          value: summaryData.successTasks || 0,
          suffix: '个',
          note: '引擎状态为已完成',
          tone: 'green',
        },
        {
          title: '失败任务数',
          value: summaryData.failedTasks || 0,
          suffix: '个',
          note: '统计范围内的失败实例',
          tone: 'orange',
        },
      ];
    }

    return [
      {
        title: taskType === 'FILE_INGEST' ? '导入记录量' : '处理记录量',
        value: summaryData.totalRecords || 0,
        suffix: summaryData.totalRecordsUnit || '条',
        note: '统计范围内处理记录',
        tone: 'blue',
      },
      {
        title: taskType === 'FILE_INGEST' ? '导入数据量' : '处理数据量',
        value: summaryData.totalBytes || 0,
        suffix: summaryData.totalBytesUnit || 'B',
        note: '统计范围内处理数据',
        tone: 'cyan',
      },
      {
        title: '执行任务数',
        value: summaryData.totalTasks || 0,
        suffix: '个',
        note: '统计范围内的任务执行实例',
        tone: 'green',
      },
      {
        title: '已结束任务成功率',
        value: successRate === null ? '—' : successRate,
        suffix: successRate === null ? undefined : '%',
        note: completedTasks > 0 ? `已结束 ${completedTasks} 个任务实例` : '当前没有已结束任务',
        tone: 'orange',
      },
    ];
  }, [completedTasks, isFileTransfer, isStreaming, successRate, summaryData, taskType]);

  const statusItems = summaryData
    ? [
        { key: 'success', label: '成功', count: summaryData.successTasks || 0 },
        { key: 'failed', label: '失败', count: summaryData.failedTasks || 0 },
        { key: 'running', label: '运行中', count: summaryData.runningTasks || 0 },
        { key: 'stopped', label: '停止 / 保存点结束', count: summaryData.stoppedTasks || 0 },
      ]
    : [];
  const statusTotal = statusItems.reduce((total, item) => total + item.count, 0);

  const recordsTitle = isStreaming
    ? '写入记录趋势'
    : taskType === 'FILE_INGEST'
      ? '导入记录趋势'
      : '处理记录趋势';
  const bytesTitle = isFileTransfer ? '文件传输趋势' : '数据量趋势';

  return (
    <PageContainer className="ingestion-dashboard-page" title={false}>
      <div className="ingestion-dashboard-page__body">
        <header className="ingestion-dashboard-page__header">
          <div>
            <h1>引接大屏</h1>
            <p>查看任务执行、数据处理规模和近期变化</p>
          </div>
          <Space className="ingestion-dashboard-page__controls" size={10} wrap>
            <Select<TaskType>
              aria-label="任务类型"
              value={taskType}
              options={taskTypeOptions}
              onChange={setTaskType}
              style={{ width: 156 }}
            />
            <Select<TimeRange>
              aria-label="统计周期"
              value={timeRange}
              options={timeRangeOptions}
              onChange={setTimeRange}
              style={{ width: 144 }}
            />
            <Button icon={<ReloadOutlined />} loading={loading} onClick={refreshData}>
              刷新数据
            </Button>
          </Space>
        </header>

        {!hasData && loading && (
          <div className="ingestion-dashboard-page__loading">
            <Spin size="large" />
            <span>正在加载引接任务数据</span>
          </div>
        )}

        {!hasData && !loading && (
          <Alert
            type="error"
            showIcon
            message="任务概览数据暂不可用"
            description={errorMessage || '请检查服务连接后重试'}
            action={
              <Button size="small" onClick={refreshData}>
                重试
              </Button>
            }
          />
        )}

        {hasData && errorMessage && (
          <Alert
            className="ingestion-dashboard-page__error"
            type="warning"
            showIcon
            message="本次刷新失败，当前展示的是上次成功获取的数据"
            description={errorMessage}
          />
        )}

        {hasData && (
          <>
            <Row gutter={[12, 12]} className="ingestion-dashboard-page__metrics">
              {metricCards.map((item) => (
                <Col key={item.title} xs={24} sm={12} xl={6}>
                  <Card
                    className={`ingestion-dashboard-metric ingestion-dashboard-metric--${item.tone}`}
                  >
                    <Statistic title={item.title} value={item.value} suffix={item.suffix} />
                    <div className="ingestion-dashboard-metric__note">{item.note}</div>
                  </Card>
                </Col>
              ))}
            </Row>

            <Card
              className="ingestion-dashboard-page__status"
              title="任务状态"
              extra={
                <span className="ingestion-dashboard-page__updated">
                  {updatedAt ? `最近更新 ${updatedAt}` : '等待更新'}
                </span>
              }
            >
              <div className="ingestion-dashboard-status-grid">
                {statusItems.map((item) => {
                  const percent = statusTotal === 0 ? 0 : (item.count / statusTotal) * 100;
                  return (
                    <div
                      className={`ingestion-dashboard-status ingestion-dashboard-status--${item.key}`}
                      key={item.key}
                    >
                      <div className="ingestion-dashboard-status__label">
                        <span>{item.label}</span>
                        <strong>{item.count.toLocaleString()}</strong>
                      </div>
                      <div
                        className="ingestion-dashboard-status__track"
                        role="progressbar"
                        aria-label={`${item.label}任务占比`}
                        aria-valuenow={Math.round(percent)}
                        aria-valuemin={0}
                        aria-valuemax={100}
                      >
                        <div
                          className="ingestion-dashboard-status__fill"
                          style={{ width: `${percent}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            </Card>

            <Row gutter={[12, 12]} className="ingestion-dashboard-page__charts">
              {!isFileTransfer && (
                <Col xs={24} xl={12}>
                  <Card className="ingestion-dashboard-chart-card" title={recordsTitle}>
                    {chartData!.recordsTrend.data.length > 0 ? (
                      <LineChart
                        data={chartData!.recordsTrend.data as number[]}
                        xAxisData={chartData!.recordsTrend.xAxis}
                        title={recordsTitle}
                        unit={summaryData!.totalRecordsUnit || '条'}
                        loading={loading}
                      />
                    ) : (
                      <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description="统计范围内暂无记录数据"
                      />
                    )}
                  </Card>
                </Col>
              )}
              <Col xs={24} xl={isFileTransfer ? 24 : 12}>
                <Card className="ingestion-dashboard-chart-card" title={bytesTitle}>
                  {chartData!.bytesTrend.data.length > 0 ? (
                    <LineChart
                      data={chartData!.bytesTrend.data as number[]}
                      xAxisData={chartData!.bytesTrend.xAxis}
                      title={bytesTitle}
                      unit={summaryData!.totalBytesUnit || 'B'}
                      loading={loading}
                    />
                  ) : (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="统计范围内暂无数据量变化"
                    />
                  )}
                </Card>
              </Col>
            </Row>
          </>
        )}
      </div>
    </PageContainer>
  );
};

export default IngestionDashboardPage;
