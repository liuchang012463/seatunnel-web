import ClickSpark from "@/components/ClickSpark";
import { useIntl } from "@umijs/max";
import { Button, message, Modal, Pagination, Spin } from "antd";
import { motion } from "framer-motion";
import React, { useEffect, useMemo, useRef, useState } from "react";
import AddOrEditDataSourceModal from "./components/AddOrEditDataSourceModal";
import DataSourceCard from "./components/DataSourceCard";
import EmptyState from "./components/EmptyState";
import PageHeader from "./components/PageHeader";
import SearchBar from "./components/SearchBar";
import { PAGE_ANIMATION, PAGE_DEFAULT_PAGINATION } from "./constants";
import {
  DATA_SOURCE_CATEGORIES,
  groupDataSourcesByCategory,
} from "./dataSourceRegistry";
import "./index.less";
import {
  checkDataSourceUsage,
  deleteDataSource,
  fetchDataSourceUnits,
  fetchDataSourcePage,
  testDataSourceConnection,
  updateDataSourceStatus,
} from "./service";
import type {
  DataSourceModalRef,
  DataSourceOperateType,
  DataSourcePageParams,
  DataSourceRecord,
  DataSourceLifecycleStatus,
  PaginationInfo,
} from "./types";

const { confirm } = Modal;

const DataSourcePage: React.FC = () => {
  const intl = useIntl();
  const modalRef = useRef<DataSourceModalRef>(null);

  const [loading, setLoading] = useState(false);
  const [dataSourceList, setDataSourceList] = useState<DataSourceRecord[]>([]);
  const [pagination, setPagination] = useState<PaginationInfo>(
    PAGE_DEFAULT_PAGINATION
  );
  const [searchKeyword, setSearchKeyword] = useState("");
  const [selectedCategory, setSelectedCategory] = useState<string>("ALL");
  const [unitOptions, setUnitOptions] = useState<string[]>([]);
  const [selectedUnit, setSelectedUnit] = useState<string>();
  const [selectedStatus, setSelectedStatus] = useState<DataSourceLifecycleStatus>();

  const refreshUnitOptions = async () => {
    try {
      const response = await fetchDataSourceUnits();
      if (response.code === 0) {
        setUnitOptions(response.data || []);
      }
    } catch (_) {
      setUnitOptions([]);
    }
  };

  const fetchList = async (params?: Partial<DataSourcePageParams>) => {
    try {
      setLoading(true);

      const requestParams: DataSourcePageParams = {
        pageNo: pagination.pageNo,
        pageSize: pagination.pageSize,
        name: searchKeyword.trim() || undefined,
        dbTypes:
          selectedCategory === "ALL"
            ? undefined
            : selectedCategoryConfig?.dbTypes || [],
        dataSourceUnit: selectedUnit || undefined,
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

  const selectedCategoryConfig = DATA_SOURCE_CATEGORIES.find(
    (category) => category.key === selectedCategory,
  );

  useEffect(() => {
    refreshUnitOptions();
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      fetchList({
        pageNo: pagination.pageNo,
        pageSize: pagination.pageSize,
        name: searchKeyword.trim() || undefined,
        dbTypes:
          selectedCategory === "ALL"
            ? undefined
            : selectedCategoryConfig?.dbTypes || [],
      });
    }, searchKeyword ? 300 : 0);
    return () => window.clearTimeout(timer);
    // Scalar pagination fields avoid a request loop when the server returns pagination metadata.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    pagination.pageNo,
    pagination.pageSize,
    searchKeyword,
    selectedCategory,
    selectedUnit,
    selectedStatus,
  ]);

  const groupedDataSourceList = useMemo(
    () => groupDataSourcesByCategory(dataSourceList),
    [dataSourceList],
  );

  const handleRefresh = () => {
    fetchList();
    refreshUnitOptions();
  };

  const handleCreate = () => {
    modalRef.current?.open({
      operateType: "CREATE" as DataSourceOperateType,
      onSuccess: handleRefresh,
    });
  };

  const handleEdit = (record: DataSourceRecord) => {
    modalRef.current?.open({
      operateType: "EDIT" as DataSourceOperateType,
      currentRecord: record,
      onSuccess: handleRefresh,
    });
  };

  const handleDelete = async (record: DataSourceRecord) => {
    if (!record.id) {
      message.error(
        intl.formatMessage({
          id: "pages.datasource.message.idNotExist",
          defaultMessage: "id does not exist",
        })
      );
      return;
    }
    const dataSourceId = record.id;

    try {
      const usageResponse = await checkDataSourceUsage(dataSourceId);
      if (usageResponse.code !== 0) {
        message.error(usageResponse.message || "检查数据源任务关联失败，请稍后重试");
        return;
      }

      if (usageResponse.data) {
        Modal.warning({
          title: "数据源暂不可删除",
          centered: true,
          content: (
            <span>
              数据源 <strong>{record.name || "-"}</strong> 当前已被任务引用，请先解除任务关联后再删除。
            </span>
          ),
          okText: "知道了",
        });
        return;
      }
    } catch (error: any) {
      message.error(
        error?.response?.data?.message ||
          error?.response?.data?.msg ||
          "检查数据源任务关联失败，请稍后重试"
      );
      return;
    }

    confirm({
      title: intl.formatMessage({
        id: "pages.datasource.delete.confirmTitle",
        defaultMessage: "Are you sure you want to delete it ?",
      }),
      centered: true,
      content: (
        <span>
          {intl.formatMessage(
            {
              id: "pages.datasource.delete.confirmContentLine1",
              defaultMessage: "Are you sure you delete datasource [{name}] ?",
            },
            {
              name: <span style={{ color: "orange" }}>{record.name}</span>,
            }
          )}
          <br />
          {intl.formatMessage({
            id: "pages.datasource.delete.confirmContentLine2",
            defaultMessage:
              "Once a data source is deleted, it cannot be recovered. Please proceed with caution.",
          })}
        </span>
      ),
      okText: intl.formatMessage({
        id: "pages.datasource.delete.okText",
        defaultMessage: "Delete",
      }),
      okType: "primary",
      okButtonProps: {
        size: "small",
        danger: true,
      },
      cancelButtonProps: {
        size: "small",
      },
      maskClosable: true,
      async onOk() {
        try {
          const response = await deleteDataSource(dataSourceId);

          if (response.code !== 0) {
            message.error(response.message || "删除数据源失败，请稍后重试");
            return;
          }

          message.success(response.message || "Delete success");
          handleRefresh();
        } catch (error: any) {
          message.error(
            error?.response?.data?.message ||
              error?.response?.data?.msg ||
              "删除数据源失败，请稍后重试"
          );
        }
      },
    });
  };

  const handleTestConnection = async (record: DataSourceRecord) => {
    if (!record.id) {
      message.error(
        intl.formatMessage({
          id: "pages.datasource.message.unknownError",
          defaultMessage: "Unknown error",
        })
      );
      return;
    }

    try {
      await testDataSourceConnection(record.id);

      message.success(
        intl.formatMessage({
          id: "pages.datasource.message.connectSuccess",
          defaultMessage: "Connected Success",
        })
      );

      handleRefresh();
    } catch (_) {}
  };

  const handleStatusChange = (
    record: DataSourceRecord,
    nextStatus: DataSourceLifecycleStatus,
  ) => {
    const statusLabel = {
      ENABLED: "启用",
      DISABLED: "停用",
      REVOKED: "注销",
    }[nextStatus];

    confirm({
      title: `确认${statusLabel}数据源？`,
      centered: true,
      content:
        nextStatus === "REVOKED"
          ? `数据源“${record.name || "-"}”注销后不可恢复启用，但记录仍会保留。`
          : `数据源“${record.name || "-"}”将变更为“${statusLabel}”状态。`,
      okText: "确认",
      cancelText: "取消",
      okButtonProps: { danger: nextStatus === "REVOKED" },
      async onOk() {
        if (!record.id) {
          message.error("数据源 ID 不存在");
          return;
        }

        try {
          const response = await updateDataSourceStatus(record.id, nextStatus);
          if (response.code !== 0) {
            message.error(response.message || "状态更新失败");
            return;
          }

          message.success(`数据源已${statusLabel}`);
          handleRefresh();
        } catch (error: any) {
          message.error(error?.message || "状态更新失败");
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
            <motion.div
              initial="hidden"
              animate="visible"
              variants={PAGE_ANIMATION.sectionStagger}
            >
              <motion.div variants={PAGE_ANIMATION.fadeUp}>
                <PageHeader onCreate={handleCreate} />
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
                    setPagination((current) => ({ ...current, pageNo: 1 }));
                  }}
                  selectedStatus={selectedStatus}
                  onStatusChange={(value) => {
                    setSelectedStatus(value as DataSourceLifecycleStatus | undefined);
                    setPagination((current) => ({ ...current, pageNo: 1 }));
                  }}
                />
              </motion.div>

              <motion.div
                variants={PAGE_ANIMATION.fadeUp}
                className="mt-4 flex flex-wrap gap-2"
              >
                <Button
                  type={selectedCategory === "ALL" ? "primary" : "default"}
                  shape="round"
                  onClick={() => {
                    setSelectedCategory("ALL");
                    setPagination((current) => ({ ...current, pageNo: 1 }));
                  }}
                >
                  全部
                </Button>
                {DATA_SOURCE_CATEGORIES.map((category) => (
                  <Button
                    key={category.key}
                    type={selectedCategory === category.key ? "primary" : "default"}
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

              <motion.p
                variants={PAGE_ANIMATION.fadeUp}
                className="datasource-page-count"
              >
                发现 {pagination.total} 个数据源
              </motion.p>

              <Spin spinning={loading}>
                <motion.div
                  variants={PAGE_ANIMATION.cardStagger}
                  initial="hidden"
                  animate="visible"
                >
                  {groupedDataSourceList.map(({ category, records }) => (
                    <section key={category.key} className="mb-8">
                      <div className="mb-3 flex items-center gap-3">
                        <h2 className="datasource-category-title">
                          {category.label}
                        </h2>
                        <span className="datasource-category-count">
                          {records.length}
                        </span>
                      </div>
                      <div className="grid grid-cols-[repeat(auto-fill,440px)] justify-start gap-5">
                        {records.map((record) => (
                          <motion.div
                            key={record.id}
                            variants={PAGE_ANIMATION.fadeUp}
                          >
                            <DataSourceCard
                              record={record}
                              onEdit={handleEdit}
                              onDelete={handleDelete}
                              onTestConnection={handleTestConnection}
                              onStatusChange={handleStatusChange}
                            />
                          </motion.div>
                        ))}
                      </div>
                    </section>
                  ))}

                  {!loading && dataSourceList.length === 0 && (
                    <EmptyState onCreate={handleCreate} />
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

      <AddOrEditDataSourceModal ref={modalRef} />
    </>
  );
};

export default DataSourcePage;
