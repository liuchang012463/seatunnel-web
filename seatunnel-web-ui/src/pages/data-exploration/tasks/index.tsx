import {
  ApartmentOutlined,
  ClockCircleOutlined,
  ExperimentOutlined,
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
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import TaskListPageHeader from '@/components/TaskListPageHeader';
import { DATA_SOURCE_STATUS_OPTIONS } from '@/pages/data-source/constants';
import {
  fetchBusinessSystemOptions,
  fetchDataSourceMetadataDatabases,
  fetchDataSourceMetadataSchemas,
  fetchDataSourceMetadataStatus,
  fetchDataSourcePage,
  fetchDataSourceUnitOptions,
  normalizeDataSourcePageResult,
  triggerDataSourceExploration,
  triggerDataSourceSampleCollection,
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
import { withTimeout } from '@/utils/withTimeout';
import {
  DEFAULT_EXPLORATION_TASK_CATEGORY,
  EXPLORATION_TASK_CATEGORIES,
  type ExplorationTaskCategoryKey,
  displayOwner,
  getExplorationTaskCategory,
  metadataStatus,
  profilerStatus,
  sampleStatus,
} from '../shared';
import { assetExplorationStatus, isAssetExplorationDbType } from '@/utils/assetExplorationStatus';
import AssetExplorationSettingsModal from '../components/AssetExplorationSettingsModal';
import RunRecordLogModal from '../components/RunRecordLogModal';
import '../index.less';

const DEFAULT_PAGINATION: PaginationInfo = { pageNo: 1, pageSize: 10, total: 0 };

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
  if (!code) return '指标统计任务执行失败，请查看运行记录。';
  const labels: Record<string, string> = {
    OM_SERVICE_SYNC_ERROR: 'OpenMetadata 指标统计管道同步失败（常见于网络超时）',
    OM_PIPELINE_TRIGGER_ERROR: 'OpenMetadata 指标统计管道触发失败',
    OM_PIPELINE_STATUS_ERROR: '无法同步 OpenMetadata 指标统计状态',
    PIPELINE_EXECUTION_ERROR: '指标统计管道执行失败',
  };
  return labels[code] || `指标统计任务执行失败（${code}）`;
}

function schemaPlaceholder() {
  return '请选择 Schema';
}

function isScanRunning(record: DataSourceRecord) {
  return record.scanStatus === 'QUEUED' || record.scanStatus === 'RUNNING';
}

function metadataUnavailableReason(record: DataSourceRecord) {
  if (record.metadataSyncStatus === 'ERROR') return '元数据同步异常，请先重试同步。';
  if (record.metadataSyncStatus !== 'READY') return '元数据同步完成后即可使用此操作。';
  if (isScanRunning(record)) return '元数据扫描正在运行，请稍候。';
  if (record.scanStatus === 'FAILED') return '元数据扫描异常，请先重新扫描。';
  // The scan button stays enabled for a data source whose first scan never ran, so the operator
  // can start it; sample/profiler need the catalog a successful scan produces.
  if (record.scanStatus !== 'SUCCESS') return '请先完成元数据扫描后再使用此操作。';
  return undefined;
}

function scanActionState(record: DataSourceRecord) {
  if (record.metadataSyncStatus === 'ERROR') {
    return { disabled: false, label: '重试同步', title: '重新发起元数据同步' };
  }
  if (record.metadataSyncStatus !== 'READY') {
    const syncStatus = record.metadataSyncStatus;
    return {
      disabled: true,
      label: syncStatus === 'UNSUPPORTED'
        ? '不支持'
        : ['PENDING', 'SYNCING', 'WAITING'].includes(syncStatus || '')
          ? '同步中'
          : syncStatus === 'DELETING' ? '删除中' : '等待同步',
      title: syncStatus === 'UNSUPPORTED'
        ? '该数据源类型不支持元数据扫描'
        : '元数据同步完成后才可重新扫描',
    };
  }
  if (isScanRunning(record)) {
    return { disabled: true, label: '扫描中', title: '元数据扫描正在运行' };
  }
  if (record.scanStatus === 'SUCCESS' || record.scanStatus === 'FAILED'
      || (record.scanStatus === 'UNKNOWN' && Boolean(record.scanLastRunTime))) {
    return { disabled: false, label: '重新扫描', title: '重新运行元数据扫描' };
  }
  // READY without a finished scan: the automatic first scan may never have been triggered, so
  // the operator must be able to start it manually instead of waiting forever.
  return { disabled: false, label: '立即扫描', title: '立即运行首次元数据扫描' };
}

const DataExplorationTasksPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [listError, setListError] = useState<string>();
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
  const [schemas, setSchemas] = useState<Array<{ value: string; label: string }>>([]);
  const [schemaFqn, setSchemaFqn] = useState<string>();
  const [exploreOpen, setExploreOpen] = useState(false);
  const [settingsRecord, setSettingsRecord] = useState<DataSourceRecord>();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [exploreLoading, setExploreLoading] = useState(false);
  const [exploreError, setExploreError] = useState<string>();
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [runRecordOpen, setRunRecordOpen] = useState(false);
  const [runRecordName, setRunRecordName] = useState('');
  const [runRecordId, setRunRecordId] = useState<string>();
  const [trackingExploration, setTrackingExploration] = useState<ExplorationTracking>();
  const [explorationFeedback, setExplorationFeedback] = useState<ExplorationFeedback>();
  // 正在提交或等待扫描结束的数据源，避免重复触发扫描。
  const [scanningIds, setScanningIds] = useState<Record<string, number>>({});
  const scanningIdsRef = useRef<Record<string, number>>({});
  const exploreRequestRef = useRef(0);
  const [category, setCategory] = useState<ExplorationTaskCategoryKey>(DEFAULT_EXPLORATION_TASK_CATEGORY);
  const selectedCategory = getExplorationTaskCategory(category);

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
    setBusinessSystemOptions([]);
    try {
      const response = await fetchBusinessSystemOptions(nextUnitId);
      if (response.code === 0) {
        setBusinessSystemOptions(unwrapMasterDataList(response));
      } else {
        setBusinessSystemOptions([]);
      }
    } catch (_) {
      setBusinessSystemOptions([]);
    }
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setListError(undefined);
    const params: DataSourcePageParams = {
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      name: keyword.trim() || undefined,
      unitId: unitId || undefined,
      businessSystemId: businessSystemId || undefined,
      status,
      dbTypes: category === 'ALL' ? undefined : [...selectedCategory.dbTypes],
      excludeSystemManaged: true,
    };
    try {
      const response = await withTimeout(
        fetchDataSourcePage(params),
        10000,
        '探查任务列表请求超时，请稍后重试',
      );
      if (response.code !== 0) {
        throw new Error(response.message || '探查任务列表暂不可用');
      }
      const page = normalizeDataSourcePageResult(response.data);
      setRecords(page.bizData);
      setPagination(page.pagination);
    } catch (error: any) {
      setRecords([]);
      setPagination((current) => ({ ...current, total: 0 }));
      setListError(errorMessage(error, '探查任务列表暂不可用，请稍后重试'));
    } finally {
      setLoading(false);
    }
  }, [businessSystemId, category, keyword, pagination.pageNo, pagination.pageSize, selectedCategory.dbTypes, status, unitId]);

  useEffect(() => {
    void loadUnits();
  }, [loadUnits]);

  useEffect(() => {
    void loadBusinessSystems(unitId);
  }, [loadBusinessSystems, unitId]);

  useEffect(() => {
    if (!exploreOpen || !exploreRecord?.id || !databaseFqn) {
      setSchemas([]);
      setSchemaFqn(undefined);
      setSchemaLoading(false);
      return;
    }

    let disposed = false;
    setSchemas([]);
    setSchemaFqn(undefined);
    setSchemaLoading(true);
    setExploreError(undefined);
    withTimeout(
      fetchDataSourceMetadataSchemas(exploreRecord.id, databaseFqn),
      10000,
      'Schema 请求超时，请稍后重试',
    )
      .then((response) => {
        if (disposed) return;
        if (response.code !== 0) {
          setExploreError(response.message || '无法读取可探查的 Schema');
          setSchemas([]);
          return;
        }
        const nextSchemas = response.data || [];
        setSchemas(nextSchemas);
        setSchemaFqn(nextSchemas.length === 1 ? nextSchemas[0].value : undefined);
        setExploreError(undefined);
      })
      .catch((error: any) => {
        if (!disposed) {
          setExploreError(errorMessage(error, '无法读取可探查的 Schema'));
          setSchemas([]);
        }
      })
      .finally(() => {
        if (!disposed) setSchemaLoading(false);
      });

    return () => {
      disposed = true;
    };
  }, [databaseFqn, exploreOpen, exploreRecord?.id]);

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
              message: `${trackingExploration.name} 指标统计失败`,
              description: explorationErrorText(status),
            });
            setTrackingExploration(undefined);
            void load();
            return;
          }

          if (status.status === 'UNKNOWN') {
            setExplorationFeedback({
              type: 'error',
              message: `${trackingExploration.name} 指标统计状态未知`,
              description: explorationErrorText(status),
            });
            setTrackingExploration(undefined);
            void load();
            return;
          }

          if (status.status === 'SUCCESS') {
            setExplorationFeedback({
              type: 'success',
              message: `${trackingExploration.name} 指标统计已完成`,
            description: '指标统计结果已写入 OpenMetadata，可前往结果页查看。',
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
          message: `${trackingExploration.name} 指标统计已提交`,
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

  const markScanPending = useCallback((id: string, submittedAt: number) => {
    scanningIdsRef.current = { ...scanningIdsRef.current, [id]: submittedAt };
    setScanningIds(scanningIdsRef.current);
  }, []);

  const clearScanPending = useCallback((id: string) => {
    if (!(id in scanningIdsRef.current)) return;

    const next = { ...scanningIdsRef.current };
    delete next[id];
    scanningIdsRef.current = next;
    setScanningIds(next);
  }, []);

  // 手动扫描提交后，后端同步写入 QUEUED，页面自己轮询扫描状态，
  // 扫描结束后刷新列表，样例采集与指标统计随即可用。
  useEffect(() => {
    const pendingIds = Object.keys(scanningIds);
    if (pendingIds.length === 0) return;

    let disposed = false;
    let timer: number | undefined;
    let attempts = 0;

    const poll = async () => {
      if (disposed) return;
      attempts += 1;

      try {
        const responses = await Promise.all(
          pendingIds.map((id) => fetchDataSourceMetadataStatus(id).catch(() => undefined)),
        );
        if (disposed) return;

        const finished = pendingIds.filter((id, index) => {
          const scan = responses[index]?.data?.scan;
          if (!scan?.status) return false;
          if (scan.status === 'QUEUED' || scan.status === 'RUNNING') return false;
          return isCurrentRun(scan, scanningIds[id]);
        });

        if (finished.length > 0) {
          finished.forEach((id) => clearScanPending(id));
          void load();
        }
      } catch (_) {
        // Keep polling. A transient status-read failure must not hide the submitted scan.
      }

      if (attempts >= 60) {
        pendingIds.forEach((id) => clearScanPending(id));
        void load();
        return;
      }

      if (!disposed) timer = window.setTimeout(() => void poll(), 2_000);
    };

    void poll();
    return () => {
      disposed = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [clearScanPending, load, scanningIds]);

  const resetPage = () => setPagination((current) => ({ ...current, pageNo: 1 }));

  const openExplore = async (record: DataSourceRecord) => {
    if (!record.id) return;
    const unavailableReason = metadataUnavailableReason(record);
    if (unavailableReason) {
      setExplorationFeedback({ type: 'info', message: '暂不可开始指标统计', description: unavailableReason });
      return;
    }
    if (record.profileStatus === 'QUEUED' || record.profileStatus === 'RUNNING') {
      setExplorationFeedback({
        type: 'info',
        message: `${record.name || '数据源'} 指标统计正在执行`,
        description: '请等待当前任务完成后再提交新的统计任务。',
      });
      return;
    }
    const requestId = exploreRequestRef.current + 1;
    exploreRequestRef.current = requestId;
    setExploreRecord(record);
    setExploreOpen(true);
    setExploreError(undefined);
    setExploreLoading(true);
    setDatabases([]);
    setDatabaseFqn(undefined);
    setSchemas([]);
    setSchemaFqn(undefined);
    try {
      const response = await withTimeout(
        fetchDataSourceMetadataDatabases(record.id),
        10000,
        'Database 请求超时，请稍后重试',
      );
      if (exploreRequestRef.current !== requestId) return;
      if (response.code !== 0) {
        throw new Error(response.message || '无法读取可进行指标统计的 Database');
      }
      const nextDatabases = response.data || [];
      setDatabases(nextDatabases);
      setDatabaseFqn(nextDatabases.length === 1 ? nextDatabases[0].value : undefined);
      setExploreError(undefined);
    } catch (error: any) {
      if (exploreRequestRef.current === requestId) {
        setExploreError(errorMessage(error, '无法读取可进行指标统计的 Database'));
        setDatabases([]);
        setDatabaseFqn(undefined);
      }
    } finally {
      if (exploreRequestRef.current === requestId) setExploreLoading(false);
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
    const requestId = exploreRequestRef.current + 1;
    exploreRequestRef.current = requestId;
    setExploreLoading(true);
    setExploreError(undefined);
    try {
      const response = await withTimeout(
        triggerDataSourceExploration(dataSourceId, databaseFqn, schemaFqn),
        10000,
        '指标统计提交请求超时，请稍后重试',
      );
      if (exploreRequestRef.current !== requestId) return;
      if (response.code !== 0) {
        message.error(response.message || '数据源指标统计暂不可触发');
        return;
      }
      setRecords((current) => current.map((record) => (
        String(record.id) === String(dataSourceId)
          ? { ...record, profileStatus: 'QUEUED' }
          : record
      )));
      setExplorationFeedback({
        type: 'info',
        message: `${dataSourceName} 指标统计已开始`,
        description: '任务已提交到后台，页面会自动同步执行状态。',
      });
      setTrackingExploration({ id: dataSourceId, name: dataSourceName, submittedAt });
      setExploreOpen(false);
      void load();
    } catch (error: any) {
      if (exploreRequestRef.current === requestId) {
        message.error(errorMessage(error, '数据源指标统计暂不可触发'));
      }
    } finally {
      if (exploreRequestRef.current === requestId) setExploreLoading(false);
    }
  };

  const closeExplore = () => {
    exploreRequestRef.current += 1;
    setExploreOpen(false);
    setExploreLoading(false);
  };

  const showRuns = async (record: DataSourceRecord) => {
    if (!record.id) return;
    setRunRecordName(record.name || '数据源');
    setRunRecordId(String(record.id));
    setRunRecordOpen(true);
  };

  const triggerSampleCollection = async (record: DataSourceRecord) => {
    if (!record.id) return;
    const unavailableReason = metadataUnavailableReason(record);
    if (unavailableReason) {
      setExplorationFeedback({ type: 'info', message: '暂不可采集样例', description: unavailableReason });
      return;
    }
    if (record.sampleStatus === 'QUEUED' || record.sampleStatus === 'RUNNING') return;
    if (isAssetExplorationDbType(record.dbType)) {
      // Non-database sources need the operator's sample-collection opt-in first.
      setSettingsRecord(record);
      setSettingsOpen(true);
      return;
    }
    try {
      const response = await triggerDataSourceSampleCollection(record.id);
      if (response.code !== 0) {
        message.error(response.message || '样例采集暂不可触发');
        return;
      }
      setExplorationFeedback({
        type: 'info',
        message: `${record.name || '数据源'} 样例采集已提交`,
        description: '任务已提交到后台，完成后可在运行记录中查看 AutoClassification 执行结果。',
      });
      void load();
    } catch (error: any) {
      message.error(errorMessage(error, '样例采集暂不可触发'));
    }
  };

  const triggerScan = async (record: DataSourceRecord) => {
    if (!record.id) return;
    const id = String(record.id);
    // 扫描请求还在进行中时不再重复提交，否则后端会以"元数据扫描暂不可触发"拒绝第二次请求。
    if (scanningIdsRef.current[id] !== undefined) return;
    const actionState = scanActionState(record);
    if (actionState.disabled) {
      setExplorationFeedback({ type: 'info', message: actionState.label, description: actionState.title });
      return;
    }
    const recoveringSync = record.metadataSyncStatus === 'ERROR';
    markScanPending(id, Date.now());
    try {
      const response = await triggerDataSourceScan(record.id);
      if (response.code !== 0) {
        message.error(response.message || '元数据扫描暂不可触发');
        clearScanPending(id);
        return;
      }
      message.success(
        recoveringSync
          ? '已重新发起元数据同步，就绪后将自动扫描'
          : '已提交元数据扫描',
      );
      void load();
    } catch (error: any) {
      message.error(errorMessage(error, '元数据扫描暂不可触发'));
      clearScanPending(id);
    }
  };

  const columns = useMemo<TableColumnsType<DataSourceRecord>>(() => [
    {
      title: '数据源',
      key: 'name',
      width: 200,
      render: (_, record) => (
        <div className="min-w-0">
          <div className="truncate font-medium" title={record.name}>{record.name || '-'}</div>
          <div className="truncate text-xs text-[var(--st-color-text-muted)]">{record.dbType || '-'}</div>
        </div>
      ),
    },
    { title: '单位', key: 'unit', width: 120, render: (_, record) => displayOwner(record).unit },
    { title: '业务系统', key: 'system', width: 140, render: (_, record) => displayOwner(record).system },
    {
      title: '元数据状态',
      dataIndex: 'metadataSyncStatus',
      key: 'metadataSyncStatus',
      width: 110,
      render: (value: string, record) => {
        const item = isAssetExplorationDbType(record.dbType)
          ? assetExplorationStatus(record)
          : metadataStatus(value, record.scanStatus);
        return (
          <Tag color={item.color} title={record.scanLastError || undefined}>
            {item.label}
          </Tag>
        );
      },
    },
    {
      title: '指标统计',
      dataIndex: 'profileStatus',
      key: 'profileStatus',
      width: 100,
      render: (_value: string, record) => {
        const item = profilerStatus(record);
        return (
          <Tag color={item.color} title={record.profileLastError || undefined}>
            {item.label}
          </Tag>
        );
      },
    },
    {
      title: '样例状态',
      dataIndex: 'sampleStatus',
      key: 'sampleStatus',
      width: 110,
      render: (_value: string, record) => {
        const item = sampleStatus(record);
        return (
          <Tag color={item.color} title={record.sampleLastError || undefined}>
            {item.label}
          </Tag>
        );
      },
    },
    {
      title: '最近运行',
      key: 'profileLastRunTime',
      width: 160,
      responsive: ['xxl'],
      render: (_, record) => (record.profilerSupported
        ? record.profileLastSuccessTime || record.profileLastRunTime
        : record.scanLastSuccessTime) || '-',
    },
    {
      title: '操作',
      key: 'actions',
      width: 520,
      render: (_, record) => {
        const scanAction = scanActionState(record);
        const unavailableReason = metadataUnavailableReason(record);
        const profileRunning = record.profileStatus === 'QUEUED' || record.profileStatus === 'RUNNING';
        const sampleRunning = record.sampleStatus === 'QUEUED' || record.sampleStatus === 'RUNNING';
        const scanPending = scanningIds[String(record.id)] !== undefined;
        return (
          <Space size={4} wrap={false} className="exploration-task-actions">
            <Button
              type="link"
              icon={<SyncOutlined />}
              disabled={scanAction.disabled || scanPending}
              title={scanPending ? '元数据扫描正在执行' : scanAction.title}
              onClick={() => void triggerScan(record)}
            >
              {scanPending ? '扫描中' : scanAction.label}
            </Button>
            {record.sampleDataSupported && (
              <Button
                type="link"
                icon={<ExperimentOutlined />}
                disabled={Boolean(unavailableReason) || sampleRunning}
                title={sampleRunning ? '样例采集正在运行' : unavailableReason}
                onClick={() => void triggerSampleCollection(record)}
              >
                {sampleRunning ? '采集中' : '样例采集'}
              </Button>
            )}
            {record.profilerSupported && (
              <Button
                type="link"
                icon={<PlayCircleOutlined />}
                disabled={Boolean(unavailableReason) || profileRunning}
                title={profileRunning ? '指标统计正在运行' : unavailableReason}
                onClick={() => void openExplore(record)}
              >
                {profileRunning ? '统计中' : unavailableReason ? '暂不可用' : '开始指标统计'}
              </Button>
            )}
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
        );
      },
    },
  ], [scanningIds]);

  return (
    <div className="data-exploration-page data-exploration-tasks flex h-full min-h-full flex-col px-6 py-5">
      <TaskListPageHeader
        icon={<PlayCircleOutlined />}
        title="探查任务配置"
        actions={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>}
      />

      <Alert
        className="exploration-task-schedule-hint mt-3"
        type="info"
        showIcon
        message="Metadata、Profiler 和 AutoClassification 三个 agent 由系统自动调度。新建数据源会自动进行首次扫描；扫描完成后可按需统计指标或采集样例。"
      />

      <Card className="exploration-panel exploration-filter-panel mt-3" size="small">
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
            className="w-[190px]"
            value={category}
            options={EXPLORATION_TASK_CATEGORIES.map((item) => ({
              label: item.label,
              value: item.key,
            }))}
            onChange={(value) => {
              setCategory(value as ExplorationTaskCategoryKey);
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

      {listError ? (
        <Alert
          className="exploration-task-feedback mt-3"
          type="error"
          showIcon
          message="探查任务列表加载失败"
          description={listError}
          action={<Button type="link" onClick={() => void load()}>重试</Button>}
        />
      ) : null}

      <Card className="exploration-panel exploration-table-panel mt-3" styles={{ body: { padding: 0 } }}>
        <Spin spinning={loading} wrapperClassName="h-full">
          <Table<DataSourceRecord>
            rowKey={(record) => String(record.id || record.name)}
            columns={columns}
            dataSource={records}
            pagination={false}
            scroll={{ x: '100%' }}
            tableLayout="fixed"
            locale={{ emptyText: listError ? '请重试加载列表' : '暂无符合条件的数据源' }}
          />
        </Spin>
        {pagination.total > 0 && (
          <div className="exploration-table-panel__pagination flex justify-end p-4">
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
        title={<span className="exploration-modal-title">开始指标统计{exploreRecord?.name ? ` · ${exploreRecord.name}` : ''}</span>}
        open={exploreOpen}
        centered
        width="min(640px, calc(100vw - 32px))"
        okText="开始统计"
        cancelText="取消"
        confirmLoading={exploreLoading}
        onCancel={closeExplore}
        onOk={() => void submitExplore()}
      >
        <div className="py-3">
          {exploreError ? (
            <Alert
              className="mb-3"
              type="error"
              showIcon
              message="读取指标统计所需元数据失败"
              description={exploreError}
              action={exploreRecord ? <Button type="link" onClick={() => void openExplore(exploreRecord)}>重试</Button> : null}
            />
          ) : null}
          <div className="mb-2 text-sm text-[var(--st-color-text-muted)]">
            选择一个 Database，可进一步限定 Schema。手动提交会立即运行一次指标统计，系统也会按计划自动调度。
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
            notFoundContent="暂无可用于指标统计的 Database，请先完成元数据扫描"
          />
          <Select
            className="mt-3 w-full"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={schemaPlaceholder()}
            loading={schemaLoading}
            disabled={!databaseFqn}
            value={schemaFqn}
            options={schemas}
            onChange={setSchemaFqn}
            notFoundContent={databaseFqn ? '暂无可用于指标统计的 Schema，请先完成元数据扫描' : '请先选择 Database'}
          />
        </div>
      </Modal>

      <AssetExplorationSettingsModal
        open={settingsOpen}
        dataSourceId={settingsRecord?.id}
        dataSourceName={settingsRecord?.name}
        dbType={settingsRecord?.dbType}
        onClose={() => setSettingsOpen(false)}
        onSaved={() => void load()}
      />

      <RunRecordLogModal
        open={runRecordOpen}
        dataSourceId={runRecordId}
        dataSourceName={runRecordName}
        onClose={() => setRunRecordOpen(false)}
      />
    </div>
  );
};

export default DataExplorationTasksPage;
