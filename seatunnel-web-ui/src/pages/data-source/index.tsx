import ClickSpark from '@/components/ClickSpark';
import { history, useIntl } from '@umijs/max';
import {
  ApiOutlined,
  ApartmentOutlined,
  AppstoreOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import {
  Button,
  Drawer,
  Empty,
  message,
  Modal,
  Pagination,
  Segmented,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { motion } from 'framer-motion';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import AddOrEditDataSourceModal from './components/AddOrEditDataSourceModal';
import DataSourceCard from './components/DataSourceCard';
import EmptyState from './components/EmptyState';
import PageHeader from './components/PageHeader';
import SearchBar from './components/SearchBar';
import MasterDataPage from '../master-data';
import { PAGE_ANIMATION, PAGE_DEFAULT_PAGINATION } from './constants';
import { DATA_SOURCE_CATEGORIES } from './dataSourceRegistry';
import './index.less';
import {
  checkDataSourceUsage,
  deleteDataSource,
  fetchBusinessSystemOptions,
  fetchDataSourcePage,
  fetchDataSourceUnitOptions,
  normalizeDataSourcePageResult,
  testDataSourceConnection,
  unwrapMasterDataList,
  updateDataSourceStatus,
} from './service';
import type {
  BusinessSystemOption,
  DataSourceLifecycleStatus,
  DataSourceModalRef,
  DataSourceOperateType,
  DataSourcePageParams,
  DataSourceRecord,
  DataSourceUnitOption,
  PaginationInfo,
} from './types';
import DataSourceLifecycleStatusTag from './components/DataSourceLifecycleStatus';
import DataSourceStatus from './components/DataSourceStatus';

const { confirm } = Modal;

type DataSourceViewMode = 'card' | 'list';

const DataSourcePage: React.FC = () => {
  const intl = useIntl();
  const modalRef = useRef<DataSourceModalRef>(null);

  const [loading, setLoading] = useState(false);
  const [dataSourceList, setDataSourceList] = useState<DataSourceRecord[]>([]);
  const [pagination, setPagination] = useState<PaginationInfo>(PAGE_DEFAULT_PAGINATION);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('ALL');
  const [unitOptions, setUnitOptions] = useState<DataSourceUnitOption[]>([]);
  const [selectedUnit, setSelectedUnit] = useState<string>();
  const [businessSystemOptions, setBusinessSystemOptions] = useState<BusinessSystemOption[]>([]);
  const [selectedBusinessSystem, setSelectedBusinessSystem] = useState<string>();
  const [selectedStatus, setSelectedStatus] = useState<DataSourceLifecycleStatus>();
  const [viewMode, setViewMode] = useState<DataSourceViewMode>('card');
  const [masterDataOpen, setMasterDataOpen] = useState(false);

  const refreshUnitOptions = async () => {
    try {
      const response = await fetchDataSourceUnitOptions();
      if (response.code === 0) {
        setUnitOptions(unwrapMasterDataList(response));
      }
    } catch (_) {
      setUnitOptions([]);
    }
  };

  const refreshBusinessSystemOptions = async (unitId?: string) => {
    if (!unitId) {
      setBusinessSystemOptions([]);
      return;
    }

    try {
      const response = await fetchBusinessSystemOptions(unitId);
      if (response.code === 0) {
        setBusinessSystemOptions(unwrapMasterDataList(response));
      }
    } catch (_) {
      setBusinessSystemOptions([]);
    }
  };

  const fetchList = async (params?: Partial<DataSourcePageParams>) => {
    try {
      setLoading(true);

      const requestParams: DataSourcePageParams = {
        pageNo: pagination.pageNo,
        pageSize: pagination.pageSize,
        name: searchKeyword.trim() || undefined,
        dbTypes: selectedCategory === 'ALL' ? undefined : selectedCategoryConfig?.dbTypes || [],
        unitId: selectedUnit || undefined,
        businessSystemId: selectedBusinessSystem || undefined,
        status: selectedStatus,
        ...params,
      };

      const response = await fetchDataSourcePage(requestParams);

      if (response.code !== 0) {
        return;
      }

      const page = normalizeDataSourcePageResult(response.data);
      setDataSourceList(page.bizData);
      setPagination(page.pagination);
    } catch (error: any) {
    } finally {
      setLoading(false);
    }
  };

  const selectedCategoryConfig = DATA_SOURCE_CATEGORIES.find((category) => category.key === selectedCategory);

  useEffect(() => {
    refreshUnitOptions();
  }, []);

  useEffect(() => {
    refreshBusinessSystemOptions(selectedUnit);
    if (!selectedUnit) {
      setSelectedBusinessSystem(undefined);
    }
  }, [selectedUnit]);

  useEffect(() => {
    const timer = window.setTimeout(
      () => {
        fetchList({
          pageNo: pagination.pageNo,
          pageSize: pagination.pageSize,
          name: searchKeyword.trim() || undefined,
          dbTypes: selectedCategory === 'ALL' ? undefined : selectedCategoryConfig?.dbTypes || [],
        });
      },
      searchKeyword ? 300 : 0,
    );
    return () => window.clearTimeout(timer);
    // Scalar pagination fields avoid a request loop when the server returns pagination metadata.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    pagination.pageNo,
    pagination.pageSize,
    searchKeyword,
    selectedCategory,
    selectedUnit,
    selectedBusinessSystem,
    selectedStatus,
  ]);

  const handleRefresh = () => {
    fetchList();
    refreshUnitOptions();
  };

  const handleCreate = () => {
    modalRef.current?.open({
      operateType: 'CREATE' as DataSourceOperateType,
      onSuccess: handleRefresh,
    });
  };

  const handleEdit = (record: DataSourceRecord) => {
    modalRef.current?.open({
      operateType: 'EDIT' as DataSourceOperateType,
      currentRecord: record,
      onSuccess: handleRefresh,
    });
  };

  const handleDelete = async (record: DataSourceRecord) => {
    if (!record.id) {
      message.error(
        intl.formatMessage({
          id: 'pages.datasource.message.idNotExist',
          defaultMessage: 'id does not exist',
        }),
      );
      return;
    }
    const dataSourceId = record.id;

    try {
      const usageResponse = await checkDataSourceUsage(dataSourceId);
      if (usageResponse.code !== 0) {
        message.error(usageResponse.message || '检查数据源任务关联失败，请稍后重试');
        return;
      }

      if (usageResponse.data) {
        Modal.warning({
          title: '数据源暂不可删除',
          centered: true,
          content: (
            <span>
              数据源 <strong>{record.name || '-'}</strong> 当前已被任务引用，请先解除任务关联后再删除。
            </span>
          ),
          okText: '知道了',
        });
        return;
      }
    } catch (error: any) {
      message.error(
        error?.response?.data?.message || error?.response?.data?.msg || '检查数据源任务关联失败，请稍后重试',
      );
      return;
    }

    confirm({
      title: intl.formatMessage({
        id: 'pages.datasource.delete.confirmTitle',
        defaultMessage: 'Are you sure you want to delete it ?',
      }),
      centered: true,
      content: (
        <span>
          {intl.formatMessage(
            {
              id: 'pages.datasource.delete.confirmContentLine1',
              defaultMessage: 'Are you sure you delete datasource [{name}] ?',
            },
            {
              name: <span style={{ color: 'orange' }}>{record.name}</span>,
            },
          )}
          <br />
          {intl.formatMessage({
            id: 'pages.datasource.delete.confirmContentLine2',
            defaultMessage: 'Once a data source is deleted, it cannot be recovered. Please proceed with caution.',
          })}
        </span>
      ),
      okText: intl.formatMessage({
        id: 'pages.datasource.delete.okText',
        defaultMessage: 'Delete',
      }),
      okType: 'primary',
      okButtonProps: {
        size: 'small',
        danger: true,
      },
      cancelButtonProps: {
        size: 'small',
      },
      maskClosable: true,
      async onOk() {
        try {
          const response = await deleteDataSource(dataSourceId);

          if (response.code !== 0) {
            message.error(response.message || '删除数据源失败，请稍后重试');
            return;
          }

          message.success(response.message || 'Delete success');
          handleRefresh();
        } catch (error: any) {
          message.error(error?.response?.data?.message || error?.response?.data?.msg || '删除数据源失败，请稍后重试');
        }
      },
    });
  };

  const handleTestConnection = async (record: DataSourceRecord) => {
    if (!record.id) {
      message.error(
        intl.formatMessage({
          id: 'pages.datasource.message.unknownError',
          defaultMessage: 'Unknown error',
        }),
      );
      return;
    }

    try {
      await testDataSourceConnection(record.id);

      message.success(
        intl.formatMessage({
          id: 'pages.datasource.message.connectSuccess',
          defaultMessage: 'Connected Success',
        }),
      );

      handleRefresh();
    } catch (_) {}
  };

  const handleViewExploration = (record: DataSourceRecord) => {
    if (!record.id) {
      return;
    }
    history.push(`/data-exploration/results?dataSourceId=${encodeURIComponent(record.id)}`);
  };

  const handleStatusChange = (record: DataSourceRecord, nextStatus: DataSourceLifecycleStatus) => {
    const statusLabel = {
      ENABLED: '启用',
      DISABLED: '停用',
      REVOKED: '注销',
    }[nextStatus];

    confirm({
      title: `确认${statusLabel}数据源？`,
      centered: true,
      content:
        nextStatus === 'REVOKED'
          ? `数据源“${record.name || '-'}”注销后不可恢复启用，但记录仍会保留。`
          : `数据源“${record.name || '-'}”将变更为“${statusLabel}”状态。`,
      okText: '确认',
      cancelText: '取消',
      okButtonProps: { danger: nextStatus === 'REVOKED' },
      async onOk() {
        if (!record.id) {
          message.error('数据源 ID 不存在');
          return;
        }

        try {
          const response = await updateDataSourceStatus(record.id, nextStatus);
          if (response.code !== 0) {
            message.error(response.message || '状态更新失败');
            return;
          }

          message.success(`数据源已${statusLabel}`);
          handleRefresh();
        } catch (error: any) {
          message.error(error?.message || '状态更新失败');
        }
      },
    });
  };

  const dataSourceColumns: TableColumnsType<DataSourceRecord> = useMemo(() => [
    {
      title: '数据源',
      key: 'name',
      fixed: 'left',
      width: 220,
      render: (_value, record) => (
        <div className="datasource-catalog-name">
          <div className="datasource-catalog-name__title" title={record.name}>{record.name || '-'}</div>
          <div className="datasource-catalog-name__type">{record.dbType || '-'}</div>
        </div>
      ),
    },
    {
      title: '连接地址',
      key: 'jdbcUrl',
      width: 260,
      ellipsis: true,
      render: (_value, record) => <span title={record.jdbcUrl}>{record.jdbcUrl || '-'}</span>,
    },
    {
      title: '归属',
      key: 'owner',
      width: 220,
      render: (_value, record) => (
        <div className="datasource-catalog-owner">
          <div><span>单位</span>{record.unitName || record.dataSourceUnit || '待归属'}</div>
          <div><span>系统</span>{record.businessSystemName || record.systemName || '待归属'}</div>
        </div>
      ),
    },
    {
      title: '状态',
      key: 'status',
      width: 190,
      render: (_value, record) => (
        <Space wrap size={[4, 4]}>
          <DataSourceStatus status={record.connStatus} />
          <DataSourceLifecycleStatusTag status={record.status} />
        </Space>
      ),
    },
    {
      title: '探查',
      key: 'exploration',
      width: 150,
      render: (_value, record) => {
        const status = record.profileStatus;
        const color = status === 'SUCCESS' ? 'success' : status === 'FAILED' ? 'error' : status === 'RUNNING' || status === 'QUEUED' ? 'processing' : 'default';
        const label = status === 'SUCCESS' ? '已完成' : status === 'FAILED' ? '异常' : status === 'RUNNING' || status === 'QUEUED' ? '处理中' : '未探查';
        return <Tag color={color}>{label}</Tag>;
      },
    },
    {
      title: '最近更新',
      dataIndex: 'updateTime',
      key: 'updateTime',
      width: 170,
      render: (value) => value || '-',
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 210,
      render: (_value, record) => {
        const currentStatus = record.status || 'ENABLED';
        const isRevoked = currentStatus === 'REVOKED';
        const isDeleting = isRevoked || record.metadataSyncStatus === 'DELETING';
        const nextStatus = currentStatus === 'DISABLED' ? 'ENABLED' : 'DISABLED';
        return (
          <Space size={0}>
            <Tooltip title="查看探查结果"><Button type="link" size="small" icon={<ApartmentOutlined />} disabled={isDeleting} onClick={() => handleViewExploration(record)} /></Tooltip>
            <Tooltip title="测试连接"><Button type="link" size="small" icon={<ApiOutlined />} disabled={isDeleting} onClick={() => void handleTestConnection(record)} /></Tooltip>
            <Tooltip title="编辑"><Button type="link" size="small" icon={<EditOutlined />} disabled={isDeleting} onClick={() => handleEdit(record)} /></Tooltip>
            <Tooltip title={currentStatus === 'DISABLED' ? '启用' : '停用'}><Button type="link" size="small" icon={currentStatus === 'DISABLED' ? <PlayCircleOutlined /> : <PauseCircleOutlined />} disabled={isDeleting} onClick={() => handleStatusChange(record, nextStatus)} /></Tooltip>
            <Tooltip title={isRevoked ? '已注销' : '注销'}><Button type="link" danger size="small" icon={<CloseCircleOutlined />} disabled={isDeleting} onClick={() => handleStatusChange(record, 'REVOKED')} /></Tooltip>
            <Tooltip title="删除"><Button type="link" danger size="small" icon={<DeleteOutlined />} disabled={isDeleting} onClick={() => void handleDelete(record)} /></Tooltip>
          </Space>
        );
      },
    },
  ], [handleDelete, handleEdit, handleStatusChange, handleTestConnection, handleViewExploration]);

  return (
    <>
      <ClickSpark
        sparkColor="#4DD2FF"
        sparkSize={10}
        sparkRadius={15}
        sparkCount={8}
        duration={400}
        easing="ease-out"
        extraScale={1}
      >
        <div className="datasource-page-container">
          <div className="datasource-page-content">
            <motion.div initial="hidden" animate="visible" variants={PAGE_ANIMATION.sectionStagger}>
              <motion.div variants={PAGE_ANIMATION.fadeUp}>
                <PageHeader
                  onCreate={handleCreate}
                  onManageMasterData={() => setMasterDataOpen(true)}
                />
              </motion.div>

              <motion.div variants={PAGE_ANIMATION.fadeUp}>
                <SearchBar
                  value={searchKeyword}
                  onChange={(value) => {
                    setSearchKeyword(value);
                    setPagination((current) => ({ ...current, pageNo: 1 }));
                  }}
                  unitOptions={unitOptions}
                  selectedUnit={selectedUnit}
                  onUnitChange={(value) => {
                    setSelectedUnit(value);
                    setSelectedBusinessSystem(undefined);
                    setPagination((current) => ({ ...current, pageNo: 1 }));
                  }}
                  businessSystemOptions={businessSystemOptions}
                  selectedBusinessSystem={selectedBusinessSystem}
                  onBusinessSystemChange={(value) => {
                    setSelectedBusinessSystem(value);
                    setPagination((current) => ({ ...current, pageNo: 1 }));
                  }}
                  selectedStatus={selectedStatus}
                  onStatusChange={(value) => {
                    setSelectedStatus(value as DataSourceLifecycleStatus | undefined);
                    setPagination((current) => ({ ...current, pageNo: 1 }));
                  }}
                />
              </motion.div>

              <motion.div variants={PAGE_ANIMATION.fadeUp} className="mt-4 flex flex-wrap gap-2">
                <Button
                  type={selectedCategory === 'ALL' ? 'primary' : 'default'}
                  shape="round"
                  onClick={() => {
                    setSelectedCategory('ALL');
                    setPagination((current) => ({ ...current, pageNo: 1 }));
                  }}
                >
                  全部
                </Button>
                {DATA_SOURCE_CATEGORIES.map((category) => (
                  <Button
                    key={category.key}
                    type={selectedCategory === category.key ? 'primary' : 'default'}
                    shape="round"
                    onClick={() => {
                      setSelectedCategory(category.key);
                      setPagination((current) => ({ ...current, pageNo: 1 }));
                    }}
                  >
                    {category.label}
                  </Button>
                ))}
              </motion.div>

              <motion.p variants={PAGE_ANIMATION.fadeUp} className="datasource-page-count">
                发现 {pagination.total} 个数据源
              </motion.p>

              <Spin spinning={loading}>
                <motion.div variants={PAGE_ANIMATION.cardStagger} initial="hidden" animate="visible">
                  {dataSourceList.length > 0 ? (
                    <section className="datasource-catalog-panel">
                      <div className="datasource-catalog-panel__heading">
                        <div>
                          <h2 className="datasource-category-title">数据源清单</h2>
                          <p>集中查看连接、归属和探查状态；支持在卡片和列表视图之间切换。</p>
                        </div>
                        <div className="datasource-catalog-panel__controls">
                          <span className="datasource-category-count">{pagination.total}</span>
                          <Segmented
                            aria-label="数据源视图"
                            className="datasource-view-switcher"
                            value={viewMode}
                            onChange={(value) => setViewMode(value as DataSourceViewMode)}
                            options={[
                              {
                                label: (
                                  <span className="datasource-view-option">
                                    <AppstoreOutlined />
                                    卡片
                                  </span>
                                ),
                                value: 'card',
                              },
                              {
                                label: (
                                  <span className="datasource-view-option">
                                    <UnorderedListOutlined />
                                    列表
                                  </span>
                                ),
                                value: 'list',
                              },
                            ]}
                          />
                        </div>
                      </div>
                      {viewMode === 'card' ? (
                        <div className="datasource-card-grid">
                          {dataSourceList.map((record, index) => (
                            <DataSourceCard
                              key={String(record.id || record.name || `data-source-${index}`)}
                              record={record}
                              onEdit={handleEdit}
                              onDelete={(currentRecord) => {
                                void handleDelete(currentRecord);
                              }}
                              onTestConnection={(currentRecord) => {
                                void handleTestConnection(currentRecord);
                              }}
                              onViewExploration={handleViewExploration}
                              onStatusChange={handleStatusChange}
                            />
                          ))}
                        </div>
                      ) : (
                        <Table<DataSourceRecord>
                          rowKey={(record) => String(record.id || record.name)}
                          className="datasource-catalog-table"
                          columns={dataSourceColumns}
                          dataSource={dataSourceList}
                          pagination={false}
                          scroll={{ x: 1320 }}
                          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据源" /> }}
                        />
                      )}
                    </section>
                  ) : (
                    !loading && <EmptyState onCreate={handleCreate} />
                  )}

                  {pagination.total > 0 && (
                    <div className="mt-8 flex justify-end">
                      <Pagination
                        current={pagination.pageNo}
                        pageSize={pagination.pageSize}
                        total={pagination.total}
                        showSizeChanger
                        showQuickJumper
                        pageSizeOptions={[10, 20, 50, 100]}
                        showTotal={(total) => `共 ${total} 条`}
                        onChange={(pageNo, pageSize) =>
                          setPagination((current) => ({
                            ...current,
                            pageNo,
                            pageSize,
                          }))
                        }
                        onShowSizeChange={(_, pageSize) =>
                          setPagination((current) => ({
                            ...current,
                            pageNo: 1,
                            pageSize,
                          }))
                        }
                      />
                    </div>
                  )}
                </motion.div>
              </Spin>
            </motion.div>
          </div>
        </div>
      </ClickSpark>

      <AddOrEditDataSourceModal ref={modalRef} onManageMasterData={() => setMasterDataOpen(true)} />
      <Drawer
        title="单位与业务系统维护"
        placement="right"
        width={1120}
        open={masterDataOpen}
        destroyOnClose
        onClose={() => setMasterDataOpen(false)}
        styles={{ body: { padding: '8px 20px 24px' } }}
      >
        <MasterDataPage embedded />
      </Drawer>
    </>
  );
};

export default DataSourcePage;
