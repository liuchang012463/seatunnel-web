import ClickSpark from '@/components/ClickSpark';
import { useIntl } from '@umijs/max';
import { Button, message, Modal, Pagination, Select, Spin } from 'antd';
import { motion } from 'framer-motion';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import AddOrEditDataSourceModal from './components/AddOrEditDataSourceModal';
import DataSourceCard from './components/DataSourceCard';
import DataExplorationDrawer from './components/DataExplorationDrawer';
import DataInventoryDashboard from './components/DataInventoryDashboard';
import EmptyState from './components/EmptyState';
import PageHeader from './components/PageHeader';
import SearchBar from './components/SearchBar';
import { PAGE_ANIMATION, PAGE_DEFAULT_PAGINATION } from './constants';
import { DATA_SOURCE_CATEGORIES, groupDataSourcesByCategory } from './dataSourceRegistry';
import './index.less';
import {
  checkDataSourceUsage,
  deleteDataSource,
  fetchBusinessSystemOptions,
  fetchDataSourceMetadataDatabases,
  fetchDataSourceMetadataRuns,
  fetchDataSourcePage,
  fetchDataSourceUnitOptions,
  testDataSourceConnection,
  triggerDataSourceExploration,
  triggerDataSourceScan,
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

const { confirm } = Modal;

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
  const [explorationRecord, setExplorationRecord] = useState<DataSourceRecord>();

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

      setDataSourceList(response.data?.bizData || []);
      setPagination(response.data?.pagination || PAGE_DEFAULT_PAGINATION);
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

  const groupedDataSourceList = useMemo(() => groupDataSourcesByCategory(dataSourceList), [dataSourceList]);

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

  const handleScan = async (record: DataSourceRecord) => {
    if (!record.id) {
      return;
    }
    try {
      const response = await triggerDataSourceScan(record.id);
      if (response.code !== 0) {
        message.error(response.message || '自动扫描暂不可触发');
        return;
      }
      message.success('已提交自动扫描');
      handleRefresh();
    } catch (error: any) {
      message.error(error?.response?.data?.message || '自动扫描暂不可触发');
    }
  };

  const handleExplore = async (record: DataSourceRecord) => {
    if (!record.id) {
      return;
    }
    let databases: Array<{ value: string; label: string }> = [];
    try {
      const response = await fetchDataSourceMetadataDatabases(record.id);
      if (response.code !== 0) {
        message.error(response.message || '无法读取可探查的 Database');
        return;
      }
      databases = response.data || [];
    } catch (error: any) {
      message.error(error?.response?.data?.message || '无法读取可探查的 Database');
      return;
    }
    if (databases.length === 0) {
      message.warning('自动扫描尚未发现可探查的 Database');
      return;
    }
    let databaseFqn = databases.length === 1 ? databases[0].value : '';
    Modal.confirm({
      title: '数据源探查',
      centered: true,
      content: (
        <div className="mt-3">
          <div className="mb-2 text-sm text-[var(--st-color-text-muted)]">
            一次探查一个 Database。{databases.length === 1 ? '已自动选择唯一的 Database。' : '请选择要探查的 Database。'}
          </div>
          {databases.length > 1 && (
            <Select
              className="w-full"
              placeholder="选择 Database"
              options={databases}
              onChange={(value) => { databaseFqn = value; }}
            />
          )}
        </div>
      ),
      okText: '开始探查',
      cancelText: '取消',
      async onOk() {
        if (!databaseFqn) {
          message.error('请选择 Database');
          return Promise.reject();
        }
        try {
          const response = await triggerDataSourceExploration(record.id!, databaseFqn);
          if (response.code !== 0) {
            message.error(response.message || '数据源探查暂不可触发');
            return Promise.reject();
          }
          message.success('已提交数据源探查');
          handleRefresh();
        } catch (error: any) {
          message.error(error?.response?.data?.message || '数据源探查暂不可触发');
          return Promise.reject();
        }
      },
    });
  };

  const handleRuns = async (record: DataSourceRecord) => {
    if (!record.id) {
      return;
    }
    try {
      const [scanResponse, explorationResponse] = await Promise.all([
        fetchDataSourceMetadataRuns(record.id, 'SCAN'),
        fetchDataSourceMetadataRuns(record.id, 'EXPLORATION'),
      ]);
      if (scanResponse.code !== 0 || explorationResponse.code !== 0) {
        message.error(scanResponse.message || explorationResponse.message || '无法读取运行记录');
        return;
      }
      const renderRuns = (runs: Array<{ runId: string; status: string; startTime?: string; endTime?: string }>) => (
        runs.length === 0
          ? <div className="text-[var(--st-color-text-muted)]">暂无运行记录</div>
          : runs.map((run) => (
            <div key={run.runId} className="mb-2 rounded-md bg-[rgba(77,210,255,0.06)] px-3 py-2 text-sm">
              <span className="mr-3 font-medium">{run.status}</span>
              <span className="text-[var(--st-color-text-muted)]">{run.startTime || '-'} → {run.endTime || '-'}</span>
            </div>
          ))
      );
      Modal.info({
        title: `${record.name || '数据源'}运行记录`,
        centered: true,
        width: 680,
        content: (
          <div className="mt-4">
            <div className="mb-2 font-medium">自动扫描（最近 5 次）</div>
            {renderRuns(scanResponse.data || [])}
            <div className="mb-2 mt-4 font-medium">数据源探查（最近 5 次）</div>
            {renderRuns(explorationResponse.data || [])}
          </div>
        ),
      });
    } catch (error: any) {
      message.error(error?.response?.data?.message || '无法读取运行记录');
    }
  };

  const handleResults = (record: DataSourceRecord) => {
    if (!record.id) {
      return;
    }
    setExplorationRecord(record);
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
                <PageHeader onCreate={handleCreate} />
              </motion.div>

              <DataInventoryDashboard />

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
                  {groupedDataSourceList.map(({ category, records }) => (
                    <section key={category.key} className="mb-8">
                      <div className="mb-3 flex items-center gap-3">
                        <h2 className="datasource-category-title">{category.label}</h2>
                        <span className="datasource-category-count">{records.length}</span>
                      </div>
                      <div className="grid grid-cols-[repeat(auto-fill,440px)] justify-start gap-5">
                        {records.map((record) => (
                          <motion.div key={record.id} variants={PAGE_ANIMATION.fadeUp}>
                            <DataSourceCard
                              record={record}
                              onEdit={handleEdit}
                              onDelete={handleDelete}
                              onTestConnection={handleTestConnection}
                              onScan={handleScan}
                              onExplore={handleExplore}
                              onRuns={handleRuns}
                              onResults={handleResults}
                              onStatusChange={handleStatusChange}
                            />
                          </motion.div>
                        ))}
                      </div>
                    </section>
                  ))}

                  {!loading && dataSourceList.length === 0 && <EmptyState onCreate={handleCreate} />}

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

      <AddOrEditDataSourceModal ref={modalRef} />
      <DataExplorationDrawer
        open={Boolean(explorationRecord?.id)}
        dataSourceId={explorationRecord?.id}
        dataSourceName={explorationRecord?.name}
        onClose={() => setExplorationRecord(undefined)}
      />
    </>
  );
};

export default DataSourcePage;
