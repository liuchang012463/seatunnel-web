import {
  ApartmentOutlined,
  BulbOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  EditOutlined,
  EyeOutlined,
  InfoCircleOutlined,
  ProfileOutlined,
  ReloadOutlined,
  RightOutlined,
  SearchOutlined,
  TableOutlined,
} from '@ant-design/icons';
import {
  Button,
  Drawer,
  Empty,
  Input,
  message,
  Modal,
  Pagination,
  Select,
  Spin,
  Table,
  Tabs,
  Tag,
} from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import {
  fetchDataExplorationDatabases,
  fetchDataExplorationMetadataCompletion,
  fetchDataExplorationProfile,
  fetchDataExplorationSchemas,
  fetchDataExplorationTable,
  fetchDataExplorationTables,
  previewDataExplorationTable,
  startDataExplorationMetadataCompletion,
  updateDataExplorationMetadata,
} from '../service';
import type {
  DataExplorationColumn,
  DataExplorationColumnProfile,
  DataExplorationConstraint,
  DataExplorationDatabase,
  DataExplorationMetadataJob,
  DataExplorationPreview,
  DataExplorationProfile,
  DataExplorationSchema,
  DataExplorationTable,
  DataExplorationTableDetail,
  DataExplorationTablePage,
  ExplorationQualityStatus,
} from '../types';
import DataExplorationErDiagram from '@/pages/data-exploration/components/DataExplorationErDiagram';
import GenericDataExplorationDrawer, {
  isGenericExplorationDbType,
} from './GenericDataExplorationDrawer';
import './DataExplorationDrawer.less';

export interface DataExplorationDrawerProps {
  dataSourceId?: string;
  dataSourceName?: string;
  /** Explicit connector type used to route non-JDBC sources to their catalog workspace. */
  dbType?: string;
  open: boolean;
  /** Render the exploration workspace directly in the page instead of an Ant Drawer. */
  inline?: boolean;
  onClose: () => void;
}

const qualityConfig: Record<ExplorationQualityStatus, { color: string; label: string }> = {
  NORMAL: { color: 'success', label: '正常' },
  ABNORMAL: { color: 'error', label: '异常' },
  NO_RULE: { color: 'default', label: '无规则' },
  NO_PROFILE: { color: 'warning', label: '无探查结果' },
};

function formatTime(timestamp?: number) {
  return timestamp ? new Date(timestamp).toLocaleString() : '-';
}

function displayValue(value: unknown) {
  if (value === undefined || value === null || value === '') {
    return '-';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

function qualityTag(status?: ExplorationQualityStatus, reason?: string) {
  const config = qualityConfig[status || 'NO_PROFILE'];
  return <Tag color={config.color} title={reason}>{config.label}</Tag>;
}

function completionTerminal(status?: string) {
  return ['completed', 'failed', 'cancelled', 'unknown'].includes((status || '').toLowerCase());
}

function completionLabel(status?: string) {
  return {
    pending: '排队中',
    running: '生成中',
    completed: '已完成',
    failed: '失败',
    cancelled: '已取消',
  }[(status || '').toLowerCase()] || status || '未知';
}

function completionColor(status?: string) {
  const normalized = (status || '').toLowerCase();
  if (normalized === 'completed') return 'success';
  if (normalized === 'failed' || normalized === 'cancelled') return 'error';
  if (normalized === 'running') return 'processing';
  return 'default';
}

const DatabaseDataExplorationDrawer: React.FC<DataExplorationDrawerProps> = ({
  dataSourceId,
  dataSourceName,
  inline = false,
  open,
  onClose,
}) => {
  const [loading, setLoading] = useState(false);
  const [databases, setDatabases] = useState<DataExplorationDatabase[]>([]);
  const [databaseFqn, setDatabaseFqn] = useState<string>();
  const [schemas, setSchemas] = useState<DataExplorationSchema[]>([]);
  const [schemaFqn, setSchemaFqn] = useState<string>();
  const [tablePage, setTablePage] = useState<DataExplorationTablePage>();
  const [tablePageNo, setTablePageNo] = useState(1);
  const [tableLoading, setTableLoading] = useState(false);
  const [selectedTableId, setSelectedTableId] = useState<string>();
  const [tableDetail, setTableDetail] = useState<DataExplorationTableDetail>();
  const [profile, setProfile] = useState<DataExplorationProfile>();
  const [profileLoading, setProfileLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [preview, setPreview] = useState<DataExplorationPreview>();
  const [previewLoading, setPreviewLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('columns');
  const [tableSearch, setTableSearch] = useState('');
  const [pendingSchemaFqn, setPendingSchemaFqn] = useState<string>();
  const [erOpen, setErOpen] = useState(false);
  const [completionJob, setCompletionJob] = useState<DataExplorationMetadataJob>();
  const [completionLoading, setCompletionLoading] = useState(false);
  const [metadataEditorOpen, setMetadataEditorOpen] = useState(false);
  const [metadataSaving, setMetadataSaving] = useState(false);
  const [metadataDraft, setMetadataDraft] = useState({
    displayName: '',
    description: '',
    tags: '',
    domainId: '',
    retentionPeriod: '',
  });

  useEffect(() => {
    if (!open || !dataSourceId) {
      return;
    }

    let disposed = false;
    setLoading(true);
    setDatabases([]);
    setDatabaseFqn(undefined);
    setSchemas([]);
    setSchemaFqn(undefined);
    setTablePage(undefined);
    setTablePageNo(1);
    setSelectedTableId(undefined);
    setTableDetail(undefined);
    setProfile(undefined);
    setProfileLoading(false);
    setPreview(undefined);
    setTableSearch('');
    setActiveTab('columns');
    setPendingSchemaFqn(undefined);
    setErOpen(false);
    setCompletionJob(undefined);
    setMetadataEditorOpen(false);

    fetchDataExplorationDatabases(dataSourceId)
      .then((response) => {
        if (disposed) {
          return;
        }
        if (response.code !== 0) {
          message.error(response.message || '无法读取扫描结果');
          return;
        }
        const nextDatabases = response.data || [];
        setDatabases(nextDatabases);
        setDatabaseFqn(nextDatabases[0]?.fullyQualifiedName);
      })
      .catch((error: any) => {
        if (!disposed) {
          message.error(error?.response?.data?.message || '无法读取扫描结果');
        }
      })
      .finally(() => {
        if (!disposed) {
          setLoading(false);
        }
      });

    return () => {
      disposed = true;
    };
  }, [open, dataSourceId]);

  useEffect(() => {
    if (!open || !dataSourceId || !databaseFqn) {
      setSchemas([]);
      setSchemaFqn(undefined);
      return;
    }

    let disposed = false;
    setSchemas([]);
    setSchemaFqn(undefined);
    setTablePage(undefined);
    setSelectedTableId(undefined);
    setTableDetail(undefined);
    setProfile(undefined);
    setPreview(undefined);
    setTableSearch('');

    fetchDataExplorationSchemas(dataSourceId, databaseFqn)
      .then((response) => {
        if (disposed) {
          return;
        }
        if (response.code !== 0) {
          message.error(response.message || '无法读取 Schema');
          return;
        }
        const nextSchemas = response.data || [];
        setSchemas(nextSchemas);
        const databaseName = databaseFqn.substring(databaseFqn.lastIndexOf('.') + 1);
        const matchingSchema = nextSchemas.find((item) => item.name === databaseName);
        const conventionalSchema = ['public', 'dbo', 'test', 'oracle_app']
          .map((name) => nextSchemas.find((item) => item.name.toLowerCase() === name))
          .find(Boolean);
        const firstBusinessSchema = nextSchemas.find(
          (item) => !['information_schema', 'mysql', 'performance_schema', 'sys']
            .includes(item.name.toLowerCase()),
        );
        const preferredSchema = pendingSchemaFqn
          && nextSchemas.some((item) => item.fullyQualifiedName === pendingSchemaFqn)
          ? pendingSchemaFqn
          : (matchingSchema || conventionalSchema || firstBusinessSchema || nextSchemas[0])
            ?.fullyQualifiedName;
        setSchemaFqn(preferredSchema);
        setPendingSchemaFqn(undefined);
      })
      .catch((error: any) => {
        if (!disposed) {
          message.error(error?.response?.data?.message || '无法读取 Schema');
        }
      });

    return () => {
      disposed = true;
    };
  }, [open, dataSourceId, databaseFqn, pendingSchemaFqn]);

  const loadTables = async (pageNo: number) => {
    if (!dataSourceId || !databaseFqn || !schemaFqn) {
      return;
    }
    setTableLoading(true);
    try {
      const response = await fetchDataExplorationTables(
        dataSourceId,
        databaseFqn,
        schemaFqn,
        pageNo,
        20,
      );
      if (response.code !== 0) {
        message.error(response.message || '无法读取表列表');
        return;
      }
      setTablePage(response.data || { records: [], total: 0, pageNo, pageSize: 20 });
      setTablePageNo(pageNo);
      setSelectedTableId(undefined);
      setTableDetail(undefined);
      setProfile(undefined);
      setPreview(undefined);
    } catch (error: any) {
      message.error(error?.response?.data?.message || '无法读取表列表');
    } finally {
      setTableLoading(false);
    }
  };

  useEffect(() => {
    if (!open || !dataSourceId || !databaseFqn || !schemaFqn
      || !schemas.some((item) => item.fullyQualifiedName === schemaFqn)) {
      setTablePage(undefined);
      return;
    }
    void loadTables(1);
    // loadTables intentionally tracks the selected OM hierarchy only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, dataSourceId, databaseFqn, schemaFqn, schemas]);

  useEffect(() => {
    if (!open || !dataSourceId || !selectedTableId) {
      return;
    }

    let disposed = false;
    setDetailLoading(true);
    setTableDetail(undefined);
    setProfile(undefined);
    setProfileLoading(false);
    setPreview(undefined);
    setActiveTab('columns');
    setCompletionJob(undefined);

    fetchDataExplorationTable(dataSourceId, selectedTableId)
      .then((detailResponse) => {
        if (disposed) {
          return;
        }
        if (detailResponse.code !== 0) {
          message.error(detailResponse.message || '无法读取表结构');
        } else {
          setTableDetail(detailResponse.data);
        }
      })
      .catch((error: any) => {
        if (!disposed) {
          message.error(error?.response?.data?.message || '无法读取表详情');
        }
      })
      .finally(() => {
        if (!disposed) {
          setDetailLoading(false);
        }
      });

    return () => {
      disposed = true;
    };
  }, [open, dataSourceId, selectedTableId]);

  useEffect(() => {
    if (!open || !dataSourceId || !selectedTableId || activeTab !== 'metrics') {
      return;
    }
    let disposed = false;
    setProfileLoading(true);
    fetchDataExplorationProfile(dataSourceId, selectedTableId)
      .then((response) => {
        if (disposed) return;
        if (response.code === 0) {
          setProfile(response.data);
        } else {
          setProfile(undefined);
          message.warning(response.message || '暂无成功探查结果');
        }
      })
      .catch((error: any) => {
        if (!disposed) {
          setProfile(undefined);
          message.warning(error?.response?.data?.message || '探查结果暂不可用');
        }
      })
      .finally(() => {
        if (!disposed) setProfileLoading(false);
      });
    return () => {
      disposed = true;
    };
  }, [activeTab, dataSourceId, open, selectedTableId]);

  useEffect(() => {
    const jobId = completionJob?.jobId;
    if (!open || !dataSourceId || !selectedTableId || !jobId || completionTerminal(completionJob?.status)) {
      return;
    }
    let disposed = false;
    const timer = window.setTimeout(() => {
      fetchDataExplorationMetadataCompletion(dataSourceId, selectedTableId, jobId)
        .then((response) => {
          if (disposed) return;
          if (response.code !== 0 || !response.data) {
            setCompletionJob((current) => ({
              ...current,
              status: 'failed',
              error: response.message || '无法读取补全任务状态',
            }));
            return;
          }
          setCompletionJob(response.data);
          if ((response.data.status || '').toLowerCase() === 'completed') {
            message.success('元数据补全已完成，正在刷新表详情');
            fetchDataExplorationTable(dataSourceId, selectedTableId).then((detailResponse) => {
              if (!disposed && detailResponse.code === 0) setTableDetail(detailResponse.data);
            });
          }
        })
        .catch((error: any) => {
          if (!disposed) {
            setCompletionJob((current) => ({
              ...current,
              status: 'failed',
              error: error?.response?.data?.message || '无法读取补全任务状态',
            }));
          }
        });
    }, (completionJob?.status || '').toLowerCase() === 'pending' ? 1200 : 2500);
    return () => {
      disposed = true;
      window.clearTimeout(timer);
    };
  }, [completionJob, dataSourceId, open, selectedTableId]);

  useEffect(() => {
    if (!tableDetail) return;
    setMetadataDraft({
      displayName: tableDetail.displayName || '',
      description: tableDetail.description || '',
      tags: (tableDetail.tags || []).join(', '),
      domainId: tableDetail.domains?.[0] || '',
      retentionPeriod: tableDetail.retentionPeriod || '',
    });
  }, [tableDetail]);

  const startCompletion = async () => {
    if (!dataSourceId || !selectedTableId) return;
    setCompletionLoading(true);
    try {
      const response = await startDataExplorationMetadataCompletion(dataSourceId, selectedTableId);
      if (response.code !== 0 || !response.data) {
        message.error(response.message || '无法提交元数据补全任务');
        return;
      }
      setCompletionJob(response.data);
      message.info('元数据补全任务已提交，页面会自动跟踪进度');
    } catch (error: any) {
      message.error(error?.response?.data?.message || '元数据补全服务暂不可用');
    } finally {
      setCompletionLoading(false);
    }
  };

  const openMetadataEditor = () => {
    if (!tableDetail) return;
    setMetadataDraft({
      displayName: tableDetail.displayName || '',
      description: tableDetail.description || '',
      tags: (tableDetail.tags || []).join(', '),
      domainId: tableDetail.domains?.[0] || '',
      retentionPeriod: tableDetail.retentionPeriod || '',
    });
    setMetadataEditorOpen(true);
  };

  const saveMetadata = async () => {
    if (!dataSourceId || !selectedTableId) return;
    setMetadataSaving(true);
    try {
      const response = await updateDataExplorationMetadata(dataSourceId, selectedTableId, {
        displayName: metadataDraft.displayName,
        description: metadataDraft.description,
        tags: metadataDraft.tags.split(',').map((item) => item.trim()).filter(Boolean),
        domainId: metadataDraft.domainId,
        retentionPeriod: metadataDraft.retentionPeriod,
      });
      if (response.code !== 0 || !response.data) {
        message.error(response.message || '元数据保存失败');
        return;
      }
      setTableDetail(response.data);
      setMetadataEditorOpen(false);
      message.success('元数据已保存');
    } catch (error: any) {
      message.error(error?.response?.data?.message || '元数据保存失败');
    } finally {
      setMetadataSaving(false);
    }
  };

  const loadPreview = async () => {
    if (!dataSourceId || !selectedTableId) {
      return;
    }
    setPreviewLoading(true);
    try {
      const response = await previewDataExplorationTable(dataSourceId, selectedTableId);
      if (response.code !== 0) {
        message.error(response.message || '无法读取 Top20 预览');
        return;
      }
      setPreview(response.data || { columns: [], data: [], total: 0 });
    } catch (error: any) {
      message.error(error?.response?.data?.message || '无法读取 Top20 预览');
    } finally {
      setPreviewLoading(false);
    }
  };

  const structureColumns: TableColumnsType<DataExplorationColumn> = [
    { title: '字段', dataIndex: 'name', key: 'name', width: 150, fixed: 'left' },
    {
      title: '类型',
      dataIndex: 'dataTypeDisplay',
      key: 'dataTypeDisplay',
      render: (value: string, column) => value || column.dataType || '-',
    },
    { title: '约束', dataIndex: 'constraint', key: 'constraint', width: 130, render: (value) => value || '-' },
    { title: '说明', dataIndex: 'description', key: 'description', ellipsis: true },
  ];

  const profileColumns: TableColumnsType<DataExplorationColumnProfile> = [
    { title: '字段', dataIndex: 'name', key: 'name', width: 130, fixed: 'left' },
    { title: '值数量', dataIndex: 'valuesCount', key: 'valuesCount', render: displayValue },
    { title: '空值', dataIndex: 'nullCount', key: 'nullCount', render: displayValue },
    { title: '去重值', dataIndex: 'distinctCount', key: 'distinctCount', render: displayValue },
    { title: '最小值', dataIndex: 'min', key: 'min', render: displayValue },
    { title: '最大值', dataIndex: 'max', key: 'max', render: displayValue },
    {
      title: '质量判定',
      key: 'qualityStatus',
      width: 120,
      render: (_, item) => qualityTag(item.qualityStatus, item.qualityReason),
    },
  ];

  const previewColumns = useMemo<TableColumnsType<Record<string, unknown>>>(() => {
    return (preview?.columns || [])
      .map((column, index) => {
        const dataIndex = column.dataIndex || column.key || String(index);
        return {
          title: column.title || dataIndex,
          dataIndex,
          key: column.key || dataIndex,
          ellipsis: true,
        };
      });
  }, [preview]);

  const constraints = tableDetail?.tableConstraints || [];
  const tables = tablePage?.records || [];
  const visibleTables = useMemo(() => {
    const keyword = tableSearch.trim().toLowerCase();
    if (!keyword) {
      return tables;
    }
    return tables.filter((table) => (
      (table.displayName || '').toLowerCase().includes(keyword)
      || table.name.toLowerCase().includes(keyword)
      || table.fullyQualifiedName.toLowerCase().includes(keyword)
    ));
  }, [tableSearch, tables]);
  const selectedTableTitle = tableDetail
    ? tableDetail.displayName || tableDetail.name || tableDetail.fullyQualifiedName
    : '';
  const sourcePath = [dataSourceName || '数据源', databaseFqn, schemaFqn]
    .filter(Boolean)
    .join(' / ');

  const explorationTitle = (
    <div className="exploration-drawer__title">
      <div className="exploration-drawer__title-icon"><ApartmentOutlined /></div>
      <div className="exploration-drawer__title-copy">
        <div className="exploration-drawer__title-name">数据探查</div>
        <div className="exploration-drawer__title-path" title={sourcePath}>
          {sourcePath || '正在连接数据源'}
        </div>
      </div>
      <div className="exploration-drawer__title-status">
        <span className="exploration-status-dot" />
        OpenMetadata
      </div>
    </div>
  );

  const closeExploration = () => {
    setErOpen(false);
    onClose();
  };

  const workspace = (
        <Spin spinning={loading} className="exploration-drawer__spin">
          <div className="exploration-drawer__workspace">
            <aside className="exploration-drawer__nav">
              <div className="exploration-drawer__nav-context">
                <div className="exploration-drawer__eyebrow">CATALOG</div>
                <label className="exploration-drawer__field">
                  <span>数据库</span>
                  <Select
                    value={databaseFqn}
                    placeholder="选择数据库"
                    showSearch
                    optionFilterProp="label"
                    options={databases.map((item) => ({
                      label: item.name || item.fullyQualifiedName,
                      value: item.fullyQualifiedName,
                    }))}
                    onChange={setDatabaseFqn}
                    suffixIcon={<DatabaseOutlined />}
                  />
                </label>
                <label className="exploration-drawer__field">
                  <span>Schema</span>
                  <Select
                    value={schemaFqn}
                    placeholder="选择 Schema"
                    disabled={!databaseFqn}
                    showSearch
                    optionFilterProp="label"
                    options={schemas.map((item) => ({
                      label: item.name || item.fullyQualifiedName,
                      value: item.fullyQualifiedName,
                    }))}
                    onChange={setSchemaFqn}
                  />
                </label>
                <button
                  type="button"
                  className="exploration-drawer__er-link"
                  disabled={!databaseFqn}
                  onClick={() => setErOpen(true)}
                >
                  <span><ApartmentOutlined /> ER 图</span>
                  <RightOutlined />
                </button>
              </div>

              <div className="exploration-drawer__nav-divider" />

              <div className="exploration-drawer__tables">
                <div className="exploration-drawer__section-heading">
                  <div className="exploration-drawer__tables-title">
                    <TableOutlined />
                    <strong>表</strong>
                    <span>{tablePage?.total || 0}</span>
                  </div>
                  <Button
                    type="text"
                    size="small"
                    aria-label="刷新表列表"
                    icon={<ReloadOutlined />}
                    loading={tableLoading}
                    onClick={() => void loadTables(tablePageNo)}
                  />
                </div>
                <Input
                  allowClear
                  value={tableSearch}
                  prefix={<SearchOutlined />}
                  placeholder="搜索表名或 FQN"
                  onChange={(event) => setTableSearch(event.target.value)}
                />
                <div className="exploration-drawer__table-list">
                  {tableLoading ? (
                    <div className="exploration-drawer__list-loading"><Spin size="small" /></div>
                  ) : visibleTables.length > 0 ? visibleTables.map((table) => {
                    const title = table.displayName || table.name || table.fullyQualifiedName;
                    return (
                      <button
                        type="button"
                        key={table.id}
                        className={`exploration-drawer__table-item${selectedTableId === table.id ? ' is-selected' : ''}`}
                        onClick={() => setSelectedTableId(table.id)}
                      >
                        <TableOutlined />
                        <span className="exploration-drawer__table-item-copy">
                          <strong title={title}>{title}</strong>
                          <small title={table.fullyQualifiedName}>{table.fullyQualifiedName}</small>
                        </span>
                        <span className="exploration-drawer__table-item-meta">
                          <span>{table.columnCount ?? '-'} 列</span>
                          <i className={table.profileAvailable ? 'is-ready' : ''} title={table.profileAvailable ? '已有数据指标' : '尚未生成数据指标'} />
                        </span>
                      </button>
                    );
                  }) : (
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={tables.length > 0 ? '没有匹配的表' : '该 Schema 暂无表'} />
                  )}
                </div>
                <Pagination
                  size="small"
                  current={tablePage?.pageNo || tablePageNo}
                  pageSize={tablePage?.pageSize || 20}
                  total={tablePage?.total || 0}
                  showSizeChanger={false}
                  showLessItems
                  onChange={(page) => void loadTables(page)}
                />
              </div>
            </aside>

            <main className="exploration-drawer__main">
              {databases.length === 0 ? (
                <div className="exploration-drawer__empty-state">
                  <div className="exploration-drawer__empty-icon"><DatabaseOutlined /></div>
                  <strong>暂无可用的探查结果</strong>
                  <span>请先完成数据源扫描，再从目录中选择数据库。</span>
                </div>
              ) : !schemaFqn ? (
                <div className="exploration-drawer__empty-state">
                  <div className="exploration-drawer__empty-icon"><DatabaseOutlined /></div>
                  <strong>选择一个 Schema</strong>
                  <span>从左侧目录选择 Schema，查看其中的数据表。</span>
                </div>
              ) : !selectedTableId ? (
                <div className="exploration-drawer__empty-state">
                  <div className="exploration-drawer__empty-icon"><TableOutlined /></div>
                  <strong>选择一张表开始查看</strong>
                  <span>表结构、样本数据和数据指标会显示在这里。</span>
                </div>
              ) : detailLoading ? (
                <div className="exploration-drawer__empty-state"><Spin /></div>
              ) : !tableDetail ? (
                <div className="exploration-drawer__empty-state">
                  <InfoCircleOutlined />
                  <strong>表详情暂不可用</strong>
                </div>
              ) : (
                <>
                  <header className="exploration-drawer__asset-header">
                    <div className="exploration-drawer__asset-copy">
                      <div className="exploration-drawer__eyebrow">TABLE RESOURCE</div>
                      <h1 title={tableDetail.fullyQualifiedName}>{selectedTableTitle}</h1>
                      <div className="exploration-drawer__fqn" title={tableDetail.fullyQualifiedName}>
                        <span>FQN</span>
                        <code>{tableDetail.fullyQualifiedName}</code>
                      </div>
                    </div>
                    <div className="exploration-drawer__asset-badges">
                      <Tag color="blue">{tableDetail.tableType || 'TABLE'}</Tag>
                      <span className="exploration-drawer__asset-status">
                        <CheckCircleOutlined /> Metadata 已读取
                      </span>
                    </div>
                  </header>
                  <div className="exploration-drawer__asset-summary">
                    <span><TableOutlined /> {tableDetail.columns?.length || 0} 列</span>
                    <span><DatabaseOutlined /> {tableDetail.schemaFullyQualifiedName || schemaFqn}</span>
                    {tableDetail.profileAvailable && <span className="is-ready"><CheckCircleOutlined /> Profile 已就绪</span>}
                  </div>

                  <Tabs
                    className="exploration-drawer__tabs"
                    activeKey={activeTab}
                    onChange={setActiveTab}
                    items={[
                      {
                        key: 'columns',
                        label: <span><ProfileOutlined /> 列</span>,
                        children: (
                          <div className="exploration-drawer__tab-pane">
                            <div className="exploration-drawer__tab-toolbar">
                              <div>
                                <div className="exploration-drawer__eyebrow">SCHEMA</div>
                                <strong>列定义</strong>
                              </div>
                              <span>{tableDetail.columns?.length || 0} 个字段</span>
                            </div>
                            <Table<DataExplorationColumn>
                              className="exploration-drawer__detail-table"
                              size="small"
                              rowKey={(item) => item.fullyQualifiedName || item.name}
                              columns={structureColumns}
                              dataSource={tableDetail.columns || []}
                              pagination={false}
                              scroll={{ x: 620, y: 390 }}
                              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无字段" /> }}
                            />
                          </div>
                        ),
                      },
                      {
                        key: 'sample',
                        label: <span><EyeOutlined /> 样本数据</span>,
                        children: (
                          <div className="exploration-drawer__tab-pane">
                            <div className="exploration-drawer__tab-toolbar">
                              <div>
                                <div className="exploration-drawer__eyebrow">SAMPLE DATA</div>
                                <strong>前 20 行</strong>
                              </div>
                              <Button
                                type="primary"
                                size="small"
                                icon={<EyeOutlined />}
                                loading={previewLoading}
                                onClick={() => void loadPreview()}
                              >
                                {preview ? '重新读取前 20 行' : '读取前 20 行'}
                              </Button>
                            </div>
                            {preview ? (
                              <Table<Record<string, unknown>>
                                className="exploration-drawer__detail-table"
                                size="small"
                                rowKey={(_, index) => String(index ?? 0)}
                                columns={previewColumns}
                                dataSource={preview.data || []}
                                pagination={false}
                                scroll={{ x: 760, y: 390 }}
                                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无样本数据" /> }}
                              />
                            ) : (
                              <div className="exploration-drawer__lazy-state">
                                <div className="exploration-drawer__lazy-icon"><EyeOutlined /></div>
                                <strong>样本数据按需读取</strong>
                                <span>点击“读取前 20 行”，从数据源获取最新样本。</span>
                              </div>
                            )}
                          </div>
                        ),
                      },
                      {
                        key: 'metrics',
                        label: <span><ProfileOutlined /> 数据指标</span>,
                        children: (
                          <div className="exploration-drawer__tab-pane">
                            <div className="exploration-drawer__tab-toolbar">
                              <div>
                                <div className="exploration-drawer__eyebrow">PROFILE AGENT</div>
                                <strong>表级概况与列级指标</strong>
                              </div>
                              <span>{profile?.profileTime ? `最近更新 ${formatTime(profile.profileTime)}` : '切换到此页后按需读取'}</span>
                            </div>
                            {profileLoading ? (
                              <div className="exploration-drawer__lazy-state"><Spin tip="正在读取 Profile 指标…" /></div>
                            ) : profile ? (
                              <>
                                <div className="exploration-drawer__metric-strip">
                                  <div><span>数据行数</span><strong>{displayValue(profile.table?.rowCount)}</strong></div>
                                  <div><span>字段数量</span><strong>{displayValue(profile.table?.columnCount ?? profile.columns?.length)}</strong></div>
                                  <div><span>数据体积</span><strong>{displayValue(profile.table?.sizeInByte)}</strong></div>
                                  <div><span>指标字段</span><strong>{profile.columns?.length || 0}</strong></div>
                                </div>
                                <div className="exploration-drawer__subheading">列级指标</div>
                                <Table<DataExplorationColumnProfile>
                                  className="exploration-drawer__detail-table"
                                  size="small"
                                  rowKey={(item) => item.name}
                                  columns={profileColumns}
                                  dataSource={profile.columns || []}
                                  pagination={false}
                                  scroll={{ x: 760, y: 310 }}
                                  locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无列级指标" /> }}
                                />
                              </>
                            ) : (
                              <div className="exploration-drawer__lazy-state">
                                <div className="exploration-drawer__lazy-icon"><ProfileOutlined /></div>
                                <strong>暂无成功的 Profile 结果</strong>
                                <span>当前表尚未生成数据指标，或探查结果暂不可用。</span>
                              </div>
                            )}
                          </div>
                        ),
                      },
                    ]}
                  />
                </>
              )}
            </main>

            <aside className="exploration-drawer__inspector">
              <div className="exploration-drawer__inspector-header">
                <div className="exploration-drawer__eyebrow">INSPECTOR</div>
                <strong>元数据摘要</strong>
              </div>
              {tableDetail ? (
                <div className="exploration-drawer__inspector-scroll">
                  <section className="exploration-drawer__inspector-section">
                    <div className="exploration-drawer__property-label">描述</div>
                    <p className={tableDetail.description ? '' : 'is-empty'}>
                      {tableDetail.description || '尚未补充描述'}
                    </p>
                  </section>
                  <section className="exploration-drawer__inspector-section">
                    <div className="exploration-drawer__property">
                      <span>标签</span>
                      <div className="exploration-drawer__tag-list">
                        {(tableDetail.tags || []).length > 0
                          ? tableDetail.tags?.map((tag) => <Tag key={tag}>{tag}</Tag>)
                          : <em>未设置</em>}
                      </div>
                    </div>
                    <div className="exploration-drawer__property"><span>域</span><strong>{(tableDetail.domains || []).join(', ') || '未分配'}</strong></div>
                    <div className="exploration-drawer__property"><span>保留周期</span><strong>{tableDetail.retentionPeriod || '未设置'}</strong></div>
                  </section>
                  <section className="exploration-drawer__inspector-section">
                    <div className="exploration-drawer__property-label">约束</div>
                    <div className="exploration-drawer__constraint-list">
                      {constraints.length > 0 ? constraints.map((item: DataExplorationConstraint, index) => (
                        <div key={`${item.constraintType}-${index}`}>
                          <span>{item.constraintType || 'CONSTRAINT'}</span>
                          <code>{(item.columns || []).join(', ') || '-'}</code>
                        </div>
                      )) : <em>未发现表级约束</em>}
                    </div>
                  </section>
                  <section className="exploration-drawer__inspector-actions">
                    <Button
                      type="primary"
                      block
                      icon={<BulbOutlined />}
                      loading={completionLoading}
                      onClick={() => void startCompletion()}
                    >
                      补全元数据
                    </Button>
                    <Button block icon={<EditOutlined />} onClick={openMetadataEditor}>编辑元数据</Button>
                  </section>
                  <section className="exploration-drawer__agent-status">
                    <div className="exploration-drawer__agent-status-head">
                      <span><BulbOutlined /> Metadata agent</span>
                      {completionJob ? (
                        <Tag color={completionColor(completionJob.status)}>{completionLabel(completionJob.status)}</Tag>
                      ) : (
                        <Tag color="success">已就绪</Tag>
                      )}
                    </div>
                    {completionJob ? (
                      <>
                        <span className="exploration-drawer__agent-status-copy">
                          {completionJob.error || '正在跟踪元数据补全任务'}
                        </span>
                        {completionJob.progress && (
                          <div className="exploration-drawer__agent-progress">
                            {completionJob.progress.completed !== undefined && <span>{completionJob.progress.completed} 已处理</span>}
                            {completionJob.progress.skipped !== undefined && <span>{completionJob.progress.skipped} 已跳过</span>}
                            {completionJob.progress.failed !== undefined && <span>{completionJob.progress.failed} 失败</span>}
                          </div>
                        )}
                      </>
                    ) : (
                      <span className="exploration-drawer__agent-status-copy">当前元数据来自 OpenMetadata 缓存</span>
                    )}
                  </section>
                </div>
              ) : (
                <div className="exploration-drawer__inspector-empty">选择表后查看描述、标签和约束。</div>
              )}
            </aside>
          </div>
        </Spin>
  );

  return (
    <>
      {inline ? (
        <section className="exploration-drawer exploration-drawer--inline" aria-label="数据探查详情">
          <div className="exploration-drawer__inline-header">
            {explorationTitle}
            <Button type="text" icon={<RightOutlined rotate={180} />} onClick={closeExploration}>返回探查结果</Button>
          </div>
          {workspace}
        </section>
      ) : (
        <Drawer
          className="exploration-drawer"
          open={open}
          onClose={closeExploration}
          width="min(1500px, calc(100vw - 80px))"
          destroyOnHidden
          title={explorationTitle}
          styles={{ body: { padding: 0 } }}
        >
          {workspace}
        </Drawer>
      )}
      <DataExplorationErDiagram
        open={erOpen}
        dataSourceId={dataSourceId}
        databaseFqn={databaseFqn}
        onClose={() => setErOpen(false)}
      />
      <Modal
        className="exploration-action-modal"
        open={metadataEditorOpen}
        title="编辑表元数据"
        okText="保存到 OpenMetadata"
        cancelText="取消"
        confirmLoading={metadataSaving}
        onCancel={() => setMetadataEditorOpen(false)}
        onOk={() => void saveMetadata()}
        destroyOnHidden
      >
        <div className="exploration-modal-form">
          <label>
            <span>显示名称</span>
            <Input
              value={metadataDraft.displayName}
              placeholder="例如：订单明细"
              onChange={(event) => setMetadataDraft((current) => ({ ...current, displayName: event.target.value }))}
            />
          </label>
          <label>
            <span>描述</span>
            <Input.TextArea
              rows={4}
              value={metadataDraft.description}
              placeholder="补充表的业务含义"
              onChange={(event) => setMetadataDraft((current) => ({ ...current, description: event.target.value }))}
            />
          </label>
          <label>
            <span>标签</span>
            <Input
              value={metadataDraft.tags}
              placeholder="多个标签用逗号分隔"
              onChange={(event) => setMetadataDraft((current) => ({ ...current, tags: event.target.value }))}
            />
          </label>
          <div className="exploration-modal-form__row">
            <label>
              <span>域 ID</span>
              <Input
                value={metadataDraft.domainId}
                placeholder="可选"
                onChange={(event) => setMetadataDraft((current) => ({ ...current, domainId: event.target.value }))}
              />
            </label>
            <label>
              <span>保留周期</span>
              <Input
                value={metadataDraft.retentionPeriod}
                placeholder="例如：30d"
                onChange={(event) => setMetadataDraft((current) => ({ ...current, retentionPeriod: event.target.value }))}
              />
            </label>
          </div>
        </div>
      </Modal>
    </>
  );
};

/**
 * Keep the established database drawer as the compatibility path while
 * routing connector-backed sources through their native catalog workspace.
 * The router itself is hook-free so changing dbType never violates React's
 * hook ordering rules in the database implementation.
 */
const DataExplorationDrawer: React.FC<DataExplorationDrawerProps> = (props) => {
  if (isGenericExplorationDbType(props.dbType)) {
    return <GenericDataExplorationDrawer {...props} />;
  }
  return <DatabaseDataExplorationDrawer {...props} />;
};

export default DataExplorationDrawer;
