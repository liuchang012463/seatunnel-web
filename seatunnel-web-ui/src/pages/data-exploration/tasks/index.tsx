import {
  ApartmentOutlined,
  ClockCircleOutlined,
  EyeOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SearchOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Input,
  Modal,
  Pagination,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import TaskListPageHeader from '@/components/TaskListPageHeader';
import { DATA_SOURCE_STATUS_OPTIONS } from '@/pages/data-source/constants';
import {
  fetchBusinessSystemOptions,
  fetchDataSourceMetadataDatabases,
  fetchDataSourceMetadataRuns,
  fetchDataSourceMetadataStatus,
  fetchDataSourcePage,
  fetchDataSourceUnitOptions,
  normalizeDataSourcePageResult,
  triggerDataSourceExploration,
  triggerDataSourceScan,
  unwrapMasterDataList,
} from '@/pages/data-source/service';
import type {
  BusinessSystemOption,
  DataSourceLifecycleStatus,
  DataSourcePageParams,
  DataSourceRecord,
  DataSourceUnitOption,
  DataSourceMetadataStatus,
  PaginationInfo,
} from '@/pages/data-source/types';
import { displayOwner, explorationStatus, metadataStatus } from '../shared';
import '../index.less';

const DEFAULT_PAGINATION: PaginationInfo = { pageNo: 1, pageSize: 10, total: 0 };

type RunRecord = { runId: string; status: string; startTime?: string; endTime?: string };
type ExplorationTracking = { id: string; name: string; submittedAt: number };
type ExplorationFeedback = { type: 'info' | 'success' | 'error'; message: string; description?: string };

function errorMessage(error: any, fallback: string) {
  return error?.response?.message
    || error?.response?.msg
    || error?.response?.data?.message
    || error?.response?.data?.msg
    || error?.message
    || fallback;
}

function parseServerTime(value?: string) {
  if (!value) return undefined;
  const timestamp = Date.parse(value.replace(' ', 'T').replace(/\//g, '-'));
  return Number.isNaN(timestamp) ? undefined : timestamp;
}

function isCurrentRun(status: DataSourceMetadataStatus['exploration'], submittedAt: number) {
  const lastRunTime = parseServerTime(status?.lastRunTime);
  return lastRunTime === undefined || lastRunTime >= submittedAt - 5_000;
}

function explorationErrorText(status?: DataSourceMetadataStatus['exploration']) {
  const code = status?.lastError;
  if (!code) return '探查任务执行失败，请查看运行记录。';
  const labels: Record<string, string> = {
    OM_PIPELINE_TRIGGER_ERROR: 'OpenMetadata 探查管道触发失败',
    OM_PIPELINE_STATUS_ERROR: '无法同步 OpenMetadata 探查状态',
    PIPELINE_EXECUTION_ERROR: '探查管道执行失败',
  };
  return labels[code] || `探查任务执行失败（${code}）`;
}

const DataExplorationTasksPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [records, setRecords] = useState<DataSourceRecord[]>([]);
  const [pagination, setPagination] = useState<PaginationInfo>(DEFAULT_PAGINATION);
  const [keyword, setKeyword] = useState('');
  const [unitId, setUnitId] = useState<string>();
  const [businessSystemId, setBusinessSystemId] = useState<string>();
  const [status, setStatus] = useState<DataSourceLifecycleStatus>();
  const [unitOptions, setUnitOptions] = useState<DataSourceUnitOption[]>([]);
  const [businessSystemOptions, setBusinessSystemOptions] = useState<BusinessSystemOption[]>([]);
  const [exploreRecord, setExploreRecord] = useState<DataSourceRecord>();
  const [databases, setDatabases] = useState<Array<{ value: string; label: string }>>([]);
  const [databaseFqn, setDatabaseFqn] = useState<string>();
  const [exploreOpen, setExploreOpen] = useState(false);
  const [exploreLoading, setExploreLoading] = useState(false);
  const [runRecordOpen, setRunRecordOpen] = useState(false);
  const [runRecords, setRunRecords] = useState<RunRecord[]>([]);
  const [runRecordName, setRunRecordName] = useState('');
  const [trackingExploration, setTrackingExploration] = useState<ExplorationTracking>();
  const [explorationFeedback, setExplorationFeedback] = useState<ExplorationFeedback>();

  const loadUnits = useCallback(async () => {
    try {
      const response = await fetchDataSourceUnitOptions();
      if (response.code === 0) setUnitOptions(unwrapMasterDataList(response));
    } catch (_) {
      setUnitOptions([]);
    }
  }, []);

  const loadBusinessSystems = useCallback(async (nextUnitId?: string) => {
    if (!nextUnitId) {
      setBusinessSystemOptions([]);
      return;
    }
    try {
      const response = await fetchBusinessSystemOptions(nextUnitId);
      if (response.code === 0) setBusinessSystemOptions(unwrapMasterDataList(response));
    } catch (_) {
      setBusinessSystemOptions([]);
    }
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    const params: DataSourcePageParams = {
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      name: keyword.trim() || undefined,
      unitId: unitId || undefined,
      businessSystemId: businessSystemId || undefined,
      status,
    };
    try {
      const response = await fetchDataSourcePage(params);
      if (response.code !== 0) {
        message.warning(response.message || '探查任务列表暂不可用');
        return;
      }
      const page = normalizeDataSourcePageResult(response.data);
      setRecords(page.bizData);
      setPagination(page.pagination);
    } catch (_) {
      message.warning('探查任务列表暂不可用，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [businessSystemId, keyword, pagination.pageNo, pagination.pageSize, status, unitId]);

  useEffect(() => {
    void loadUnits();
  }, [loadUnits]);

  useEffect(() => {
    void loadBusinessSystems(unitId);
  }, [loadBusinessSystems, unitId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!trackingExploration) return;

    let disposed = false;
    let timer: number | undefined;
    let attempts = 0;

    const poll = async () => {
      if (disposed) return;
      attempts += 1;

      try {
        const response = await fetchDataSourceMetadataStatus(trackingExploration.id);
        if (disposed) return;

        const status = response.data?.exploration;
        if (response.code === 0 && status && isCurrentRun(status, trackingExploration.submittedAt)) {
          if (status.status === 'FAILED') {
            setExplorationFeedback({
              type: 'error',
              message: `${trackingExploration.name} 探查失败`,
              description: explorationErrorText(status),
            });
            setTrackingExploration(undefined);
            void load();
            return;
          }

          if (status.status === 'UNKNOWN') {
            setExplorationFeedback({
              type: 'error',
              message: `${trackingExploration.name} 探查状态未知`,
              description: explorationErrorText(status),
            });
            setTrackingExploration(undefined);
            void load();
            return;
          }

          if (status.status === 'SUCCESS') {
            setExplorationFeedback({
              type: 'success',
              message: `${trackingExploration.name} 探查已完成`,
              description: '探查结果已写入 OpenMetadata，可前往结果页查看。',
            });
            setTrackingExploration(undefined);
            void load();
            return;
          }
        }
      } catch (_) {
        // Keep polling. A transient status-read failure must not hide the accepted task.
      }

      if (attempts >= 60) {
        setExplorationFeedback({
          type: 'info',
          message: `${trackingExploration.name} 探查已提交`,
          description: '状态同步超时，请稍后刷新或查看运行记录。',
        });
        setTrackingExploration(undefined);
        return;
      }

      if (!disposed) timer = window.setTimeout(() => void poll(), 2_000);
    };

    void poll();
    return () => {
      disposed = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [load, trackingExploration]);

  const resetPage = () => setPagination((current) => ({ ...current, pageNo: 1 }));

  const openExplore = async (record: DataSourceRecord) => {
    if (!record.id) return;
    if (record.profileStatus === 'QUEUED' || record.profileStatus === 'RUNNING') {
      setExplorationFeedback({
        type: 'info',
        message: `${record.name || '数据源'} 探查正在执行`,
        description: '请等待当前任务完成后再提交新的探查。',
      });
      return;
    }
    setExploreRecord(record);
    setExploreOpen(true);
    setExploreLoading(true);
    try {
      const response = await fetchDataSourceMetadataDatabases(record.id);
      if (response.code !== 0) {
        message.error(response.message || '无法读取可探查的 Database');
        setDatabases([]);
        return;
      }
      const nextDatabases = response.data || [];
      setDatabases(nextDatabases);
      setDatabaseFqn(nextDatabases.length === 1 ? nextDatabases[0].value : undefined);
    } catch (error: any) {
      message.error(errorMessage(error, '无法读取可探查的 Database'));
      setDatabases([]);
    } finally {
      setExploreLoading(false);
    }
  };

  const submitExplore = async () => {
    if (!exploreRecord?.id || !databaseFqn) {
      message.error('请选择 Database');
      return;
    }
    if (exploreLoading) return;

    const dataSourceId = exploreRecord.id;
    const dataSourceName = exploreRecord.name || '数据源';
    const submittedAt = Date.now();
    setExploreLoading(true);
    try {
      const response = await triggerDataSourceExploration(dataSourceId, databaseFqn);
      if (response.code !== 0) {
        message.error(response.message || '数据源探查暂不可触发');
        return;
      }
      setRecords((current) => current.map((record) => (
        String(record.id) === String(dataSourceId)
          ? { ...record, profileStatus: 'QUEUED' }
          : record
      )));
      setExplorationFeedback({
        type: 'info',
        message: `${dataSourceName} 探查已开始`,
        description: '任务已提交到后台，页面会自动同步执行状态。',
      });
      setTrackingExploration({ id: dataSourceId, name: dataSourceName, submittedAt });
      setExploreOpen(false);
      void load();
    } catch (error: any) {
      message.error(errorMessage(error, '数据源探查暂不可触发'));
    } finally {
      setExploreLoading(false);
    }
  };

  const showRuns = async (record: DataSourceRecord) => {
    if (!record.id) return;
    try {
      const response = await fetchDataSourceMetadataRuns(record.id, 'EXPLORATION');
      if (response.code !== 0) {
        message.error(response.message || '无法读取探查运行记录');
        return;
      }
      setRunRecordName(record.name || '数据源');
      setRunRecords(response.data || []);
      setRunRecordOpen(true);
    } catch (error: any) {
      message.error(errorMessage(error, '无法读取探查运行记录'));
    }
  };

  const triggerScan = async (record: DataSourceRecord) => {
    if (!record.id) return;
    try {
      const response = await triggerDataSourceScan(record.id);
      if (response.code !== 0) {
        message.error(response.message || '元数据扫描暂不可触发');
        return;
      }
      message.success('已提交元数据扫描');
      void load();
    } catch (error: any) {
      message.error(errorMessage(error, '元数据扫描暂不可触发'));
    }
  };

  const columns: TableColumnsType<DataSourceRecord> = [
    {
      title: '数据源',
      key: 'name',
      fixed: 'left',
      width: 220,
      render: (_, record) => (
        <div className="min-w-0">
          <div className="truncate font-medium" title={record.name}>{record.name || '-'}</div>
          <div className="truncate text-xs text-[var(--st-color-text-muted)]">{record.dbType || '-'}</div>
        </div>
      ),
    },
    { title: '单位', key: 'unit', width: 150, render: (_, record) => displayOwner(record).unit },
    { title: '业务系统', key: 'system', width: 170, render: (_, record) => displayOwner(record).system },
    {
      title: '元数据状态',
      dataIndex: 'metadataSyncStatus',
      key: 'metadataSyncStatus',
      width: 120,
      render: (value: string) => {
        const item = metadataStatus(value);
        return <Tag color={item.color}>{item.label}</Tag>;
      },
    },
    {
      title: '探查状态',
      dataIndex: 'profileStatus',
      key: 'profileStatus',
      width: 110,
      render: (value: string) => {
        const item = explorationStatus(value);
        return <Tag color={item.color}>{item.label}</Tag>;
      },
    },
    {
      title: '最近探查',
      key: 'profileLastRunTime',
      width: 170,
      render: (_, record) => record.profileLastSuccessTime || record.profileLastRunTime || '-',
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 340,
      render: (_, record) => (
        <Space size="small">
          <Button type="link" icon={<SyncOutlined />} onClick={() => void triggerScan(record)}>
            重新扫描
          </Button>
          <Button
            type="link"
            icon={<PlayCircleOutlined />}
            disabled={record.profileStatus === 'QUEUED' || record.profileStatus === 'RUNNING'}
            onClick={() => void openExplore(record)}
          >
            {record.profileStatus === 'QUEUED' || record.profileStatus === 'RUNNING' ? '探查中' : '开始探查'}
          </Button>
          <Button type="link" icon={<ClockCircleOutlined />} onClick={() => void showRuns(record)}>
            运行记录
          </Button>
          <Button
            type="link"
            icon={<EyeOutlined />}
            onClick={() => {
              const query = new URLSearchParams({ dataSourceId: String(record.id) });
              if (record.dbType) query.set('dbType', record.dbType);
              history.push(`/data-exploration/results?${query.toString()}`);
            }}
          >
            查看结果
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="data-exploration-page data-exploration-tasks min-h-full px-6 py-5">
      <TaskListPageHeader
        icon={<PlayCircleOutlined />}
        title="探查任务配置"
        subtitle="按数据源配置一次性探查任务；本页不包含定时调度，执行后可在结果展示页查看结构与画像。"
        actions={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>}
      />

      <Card className="exploration-panel exploration-filter-panel mt-5" size="small">
        <div className="flex flex-wrap items-center gap-3">
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索数据源名称"
            value={keyword}
            className="w-[240px]"
            onChange={(event) => {
              setKeyword(event.target.value);
              resetPage();
            }}
          />
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="全部单位"
            className="w-[190px]"
            value={unitId}
            options={unitOptions.map((item) => ({
              label: item.unitName,
              value: String(item.id),
            }))}
            onChange={(value) => {
              setUnitId(value);
              setBusinessSystemId(undefined);
              resetPage();
            }}
          />
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={unitId ? '全部业务系统' : '请先选择单位'}
            disabled={!unitId}
            className="w-[210px]"
            value={businessSystemId}
            options={businessSystemOptions.map((item) => ({
              label: item.systemName,
              value: String(item.id),
            }))}
            onChange={(value) => {
              setBusinessSystemId(value);
              resetPage();
            }}
          />
          <Select
            allowClear
            placeholder="全部生命周期状态"
            className="w-[190px]"
            value={status}
            options={DATA_SOURCE_STATUS_OPTIONS}
            onChange={(value) => {
              setStatus(value as DataSourceLifecycleStatus | undefined);
              resetPage();
            }}
          />
          <span className="ml-auto text-xs text-[var(--st-color-text-muted)]">共 {pagination.total} 个数据源</span>
        </div>
      </Card>

      {explorationFeedback && (
        <Alert
          className="exploration-task-feedback mt-3"
          type={explorationFeedback.type}
          showIcon
          closable
          message={explorationFeedback.message}
          description={explorationFeedback.description}
          onClose={() => setExplorationFeedback(undefined)}
        />
      )}

      <Card className="exploration-panel exploration-table-panel mt-3" bodyStyle={{ padding: 0 }}>
        <Spin spinning={loading}>
          <Table<DataSourceRecord>
            rowKey={(record) => String(record.id || record.name)}
            columns={columns}
            dataSource={records}
            pagination={false}
            scroll={{ x: 1320 }}
            locale={{ emptyText: '暂无符合条件的数据源' }}
          />
        </Spin>
        {pagination.total > 0 && (
          <div className="flex justify-end p-4">
            <Pagination
              current={pagination.pageNo}
              pageSize={pagination.pageSize}
              total={pagination.total}
              showSizeChanger
              showQuickJumper
              pageSizeOptions={[10, 20, 50, 100]}
              showTotal={(total) => `共 ${total} 条`}
              onChange={(pageNo, pageSize) => setPagination((current) => ({ ...current, pageNo, pageSize }))}
            />
          </div>
        )}
      </Card>

      <Modal
        className="exploration-action-modal"
        title={<span className="exploration-modal-title">开始探查{exploreRecord?.name ? ` · ${exploreRecord.name}` : ''}</span>}
        open={exploreOpen}
        centered
        width="min(640px, calc(100vw - 32px))"
        okText="提交探查"
        cancelText="取消"
        confirmLoading={exploreLoading}
        onCancel={() => setExploreOpen(false)}
        onOk={() => void submitExplore()}
      >
        <div className="py-3">
          <div className="mb-2 text-sm text-[var(--st-color-text-muted)]">
            一次探查一个 Database。任务提交后不会在此页面自动调度。
          </div>
          <Select
            className="w-full"
            showSearch
            optionFilterProp="label"
            placeholder="请选择 Database"
            loading={exploreLoading}
            value={databaseFqn}
            options={databases}
            onChange={setDatabaseFqn}
            notFoundContent="暂无可探查的 Database，请先完成元数据扫描"
          />
        </div>
      </Modal>

      <Modal
        className="exploration-action-modal exploration-run-record-modal"
        title={<span className="exploration-modal-title">{runRecordName} · 探查运行记录</span>}
        open={runRecordOpen}
        footer={null}
        centered
        width="min(720px, calc(100vw - 32px))"
        onCancel={() => setRunRecordOpen(false)}
      >
        {runRecords.length === 0 ? (
          <div className="py-8 text-center text-[var(--st-color-text-muted)]">暂无探查运行记录</div>
        ) : (
          <div className="max-h-[420px] overflow-auto py-2">
            {runRecords.map((run) => (
              <div key={run.runId} className="exploration-run-item mb-2 rounded-md px-3 py-2 text-sm">
                <div className="flex items-center justify-between gap-3">
                  <span className="font-medium">{run.status}</span>
                  <span className="text-xs text-[var(--st-color-text-muted)]">{run.runId}</span>
                </div>
                <div className="mt-1 text-xs text-[var(--st-color-text-muted)]">
                  {run.startTime || '-'} → {run.endTime || '-'}
                </div>
              </div>
            ))}
          </div>
        )}
      </Modal>
    </div>
  );
};

export default DataExplorationTasksPage;
