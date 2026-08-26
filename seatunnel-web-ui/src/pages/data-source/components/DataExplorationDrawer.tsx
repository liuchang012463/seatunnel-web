import {
  ApartmentOutlined,
  DatabaseOutlined,
  EyeOutlined,
  ProfileOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  Button,
  Descriptions,
  Drawer,
  Empty,
  message,
  Select,
  Spin,
  Table,
  Tabs,
  Tag,
  Tree,
} from 'antd';
import type { TableColumnsType, TreeDataNode } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import {
  fetchDataExplorationDatabases,
  fetchDataExplorationProfile,
  fetchDataExplorationSchemas,
  fetchDataExplorationTable,
  fetchDataExplorationTables,
  fetchDataSourceTopologyChildren,
  fetchDataSourceTopologyTree,
  previewDataExplorationTable,
} from '../service';
import type {
  DataExplorationColumn,
  DataExplorationColumnProfile,
  DataExplorationConstraint,
  DataExplorationDatabase,
  DataExplorationPreview,
  DataExplorationProfile,
  DataExplorationSchema,
  DataExplorationTable,
  DataExplorationTableDetail,
  DataExplorationTablePage,
  ExplorationQualityStatus,
  DataSourceTopologyNode,
  DataSourceTopologyNodeType,
} from '../types';

interface DataExplorationDrawerProps {
  dataSourceId?: string;
  dataSourceName?: string;
  open: boolean;
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

function topologyKey(node: DataSourceTopologyNode) {
  return `${node.nodeType}:${node.id}`;
}

function topologyTreeData(nodes: DataSourceTopologyNode[]): TreeDataNode[] {
  return nodes.map((node) => ({
    key: topologyKey(node),
    title: node.name || node.id,
    isLeaf: node.nodeType === 'TABLE',
    children: node.children && node.children.length > 0
      ? topologyTreeData(node.children)
      : undefined,
  }));
}

function replaceTopologyChildren(
  nodes: DataSourceTopologyNode[],
  key: string,
  children: DataSourceTopologyNode[],
): DataSourceTopologyNode[] {
  return nodes.map((node) => {
    if (topologyKey(node) === key) {
      return { ...node, children };
    }
    if (node.children && node.children.length > 0) {
      return { ...node, children: replaceTopologyChildren(node.children, key, children) };
    }
    return node;
  });
}

const DataExplorationDrawer: React.FC<DataExplorationDrawerProps> = ({
  dataSourceId,
  dataSourceName,
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
  const [detailLoading, setDetailLoading] = useState(false);
  const [preview, setPreview] = useState<DataExplorationPreview>();
  const [previewLoading, setPreviewLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('structure');
  const [topologyNodes, setTopologyNodes] = useState<DataSourceTopologyNode[]>([]);
  const [topologyLoading, setTopologyLoading] = useState(false);
  const [pendingSchemaFqn, setPendingSchemaFqn] = useState<string>();

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
    setPreview(undefined);
    setActiveTab('structure');
    setTopologyNodes([]);
    setPendingSchemaFqn(undefined);

    setTopologyLoading(true);
    fetchDataSourceTopologyTree({ dataSourceId })
      .then((response) => {
        if (!disposed) {
          if (response.code !== 0) {
            message.warning(response.message || '拓扑暂不可用');
          } else {
            setTopologyNodes(response.data || []);
          }
        }
      })
      .catch((error: any) => {
        if (!disposed) {
          message.warning(error?.response?.data?.message || '拓扑暂不可用');
        }
      })
      .finally(() => {
        if (!disposed) {
          setTopologyLoading(false);
        }
      });

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
        const preferredSchema = pendingSchemaFqn
          && nextSchemas.some((item) => item.fullyQualifiedName === pendingSchemaFqn)
          ? pendingSchemaFqn
          : nextSchemas[0]?.fullyQualifiedName;
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
    setPreview(undefined);
    setActiveTab('structure');

    Promise.all([
      fetchDataExplorationTable(dataSourceId, selectedTableId),
      fetchDataExplorationProfile(dataSourceId, selectedTableId),
    ])
      .then(([detailResponse, profileResponse]) => {
        if (disposed) {
          return;
        }
        if (detailResponse.code !== 0) {
          message.error(detailResponse.message || '无法读取表结构');
        } else {
          setTableDetail(detailResponse.data);
        }
        if (profileResponse.code === 0) {
          setProfile(profileResponse.data);
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

  const onTopologySelect = (keys: React.Key[]) => {
    if (keys.length === 0) {
      return;
    }
    const key = String(keys[0]);
    const separator = key.indexOf(':');
    if (separator <= 0) {
      return;
    }
    const nodeType = key.substring(0, separator) as DataSourceTopologyNodeType;
    const nodeId = key.substring(separator + 1);
    if (nodeType === 'DATABASE') {
      setPendingSchemaFqn(undefined);
      setDatabaseFqn(nodeId);
    } else if (nodeType === 'SCHEMA') {
      const split = nodeId.indexOf('|');
      if (split <= 0) {
        return;
      }
      const nextDatabaseFqn = nodeId.substring(0, split);
      const nextSchemaFqn = nodeId.substring(split + 1);
      if (nextDatabaseFqn === databaseFqn) {
        setSchemaFqn(nextSchemaFqn);
      } else {
        setPendingSchemaFqn(nextSchemaFqn);
        setDatabaseFqn(nextDatabaseFqn);
      }
    } else if (nodeType === 'TABLE') {
      setSelectedTableId(nodeId);
      setActiveTab('structure');
    }
  };

  const loadTopologyData = async (node: TreeDataNode) => {
    const key = String(node.key);
    const separator = key.indexOf(':');
    if (separator <= 0) {
      return;
    }
    const nodeType = key.substring(0, separator) as DataSourceTopologyNodeType;
    if (nodeType === 'TABLE' || node.children) {
      return;
    }
    const nodeId = key.substring(separator + 1);
    try {
      const response = await fetchDataSourceTopologyChildren(nodeType, nodeId);
      if (response.code !== 0) {
        message.warning(response.message || '拓扑节点暂不可用');
        return;
      }
      setTopologyNodes((current) => replaceTopologyChildren(current, key, response.data || []));
    } catch (error: any) {
      message.warning(error?.response?.data?.message || '拓扑节点暂不可用');
    }
  };

  const constraints = tableDetail?.tableConstraints || [];
  const tables = tablePage?.records || [];

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={1120}
      destroyOnClose
      title={(
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-[rgba(77,210,255,0.14)] text-[var(--st-color-primary)]">
            <ApartmentOutlined />
          </div>
          <div>
            <div className="text-base font-semibold">扫描结果</div>
            <div className="text-xs font-normal text-[var(--st-color-text-muted)]">
              {dataSourceName || '数据源'} · Database / Schema / Table
            </div>
          </div>
        </div>
      )}
    >
      <Spin spinning={loading}>
        <div className="mb-4 rounded-lg border border-[var(--st-color-border)] p-3">
          <div className="mb-2 flex items-center justify-between">
            <div className="flex items-center gap-2 font-medium">
              <ApartmentOutlined className="text-[var(--st-color-primary)]" />
              数据源拓扑
            </div>
            <span className="text-xs text-[var(--st-color-text-muted)]">展开节点按需读取 OM</span>
          </div>
          <Spin spinning={topologyLoading}>
            {topologyNodes.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可展示的拓扑" />
            ) : (
              <Tree
                blockNode
                selectable
                treeData={topologyTreeData(topologyNodes)}
                loadData={loadTopologyData}
                onSelect={onTopologySelect}
              />
            )}
          </Spin>
        </div>
        <div className="mb-4 grid grid-cols-1 gap-3 rounded-lg border border-[var(--st-color-border)] bg-[rgba(77,210,255,0.03)] p-4 md:grid-cols-2">
          <div>
            <div className="mb-1 text-xs text-[var(--st-color-text-muted)]">Database</div>
            <Select
              className="w-full"
              value={databaseFqn}
              placeholder="选择 Database"
              options={databases.map((item) => ({ label: item.name || item.fullyQualifiedName, value: item.fullyQualifiedName }))}
              onChange={setDatabaseFqn}
              suffixIcon={<DatabaseOutlined />}
            />
          </div>
          <div>
            <div className="mb-1 text-xs text-[var(--st-color-text-muted)]">Schema</div>
            <Select
              className="w-full"
              value={schemaFqn}
              placeholder="选择 Schema"
              disabled={!databaseFqn}
              options={schemas.map((item) => ({ label: item.name || item.fullyQualifiedName, value: item.fullyQualifiedName }))}
              onChange={setSchemaFqn}
            />
          </div>
        </div>

        {databases.length === 0 ? (
          <Empty description="暂无扫描结果，请先完成数据源自动扫描" />
        ) : !schemaFqn ? (
          <Empty description="该 Database 暂无可展示的 Schema" />
        ) : (
          <div className="grid min-h-[520px] grid-cols-1 gap-4 xl:grid-cols-[minmax(320px,0.82fr)_minmax(520px,1.4fr)]">
            <div className="min-w-0 rounded-lg border border-[var(--st-color-border)] p-3">
              <div className="mb-3 flex items-center justify-between">
                <div className="flex items-center gap-2 font-medium">
                  <DatabaseOutlined className="text-[var(--st-color-primary)]" />
                  表列表
                  <Tag>{tablePage?.total || 0}</Tag>
                </div>
                <Button
                  type="text"
                  size="small"
                  icon={<ReloadOutlined />}
                  loading={tableLoading}
                  onClick={() => void loadTables(tablePageNo)}
                />
              </div>
              <Table<DataExplorationTable>
                size="small"
                rowKey="id"
                loading={tableLoading}
                dataSource={tables}
                columns={[
                  {
                    title: '表名',
                    dataIndex: 'name',
                    key: 'name',
                    render: (name: string, record) => (
                      <Button
                        type="link"
                        className="px-0"
                        onClick={() => setSelectedTableId(record.id)}
                      >
                        {name || record.fullyQualifiedName}
                      </Button>
                    ),
                  },
                  { title: '字段', dataIndex: 'columnCount', key: 'columnCount', width: 60 },
                  {
                    title: '探查',
                    dataIndex: 'profileAvailable',
                    key: 'profileAvailable',
                    width: 76,
                    render: (available: boolean) => available ? <Tag color="success">已有</Tag> : <Tag>未探查</Tag>,
                  },
                ]}
                pagination={{
                  current: tablePage?.pageNo || tablePageNo,
                  pageSize: tablePage?.pageSize || 20,
                  total: tablePage?.total || 0,
                  showSizeChanger: false,
                  size: 'small',
                }}
                onChange={(pagination) => {
                  if (pagination.current) {
                    void loadTables(pagination.current);
                  }
                }}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无表" /> }}
                scroll={{ x: 330 }}
              />
            </div>

            <div className="min-w-0 rounded-lg border border-[var(--st-color-border)] p-3">
              {!selectedTableId ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择一张表查看扫描结果" />
              ) : detailLoading ? (
                <div className="flex min-h-[420px] items-center justify-center"><Spin /></div>
              ) : !tableDetail ? (
                <Empty description="表详情暂不可用" />
              ) : (
                <>
                  <div className="mb-3 flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="truncate text-base font-semibold" title={tableDetail.fullyQualifiedName}>
                        {tableDetail.name || tableDetail.fullyQualifiedName}
                      </div>
                      <div className="truncate text-xs text-[var(--st-color-text-muted)]" title={tableDetail.fullyQualifiedName}>
                        {tableDetail.fullyQualifiedName}
                      </div>
                    </div>
                    <Tag color="blue">{tableDetail.tableType || 'TABLE'}</Tag>
                  </div>
                  <Tabs
                    activeKey={activeTab}
                    onChange={setActiveTab}
                    items={[
                      {
                        key: 'structure',
                        label: <span><ProfileOutlined /> 表结构</span>,
                        children: (
                          <div>
                            <Descriptions size="small" column={1} bordered className="mb-3">
                              <Descriptions.Item label="说明">{tableDetail.description || '-'}</Descriptions.Item>
                              <Descriptions.Item label="Database">{tableDetail.databaseFullyQualifiedName || databaseFqn}</Descriptions.Item>
                              <Descriptions.Item label="Schema">{tableDetail.schemaFullyQualifiedName || schemaFqn}</Descriptions.Item>
                            </Descriptions>
                            <Table<DataExplorationColumn>
                              size="small"
                              rowKey={(item) => item.fullyQualifiedName || item.name}
                              columns={structureColumns}
                              dataSource={tableDetail.columns || []}
                              pagination={false}
                              scroll={{ x: 560, y: 330 }}
                              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无字段" /> }}
                            />
                            {constraints.length > 0 && (
                              <div className="mt-3 text-xs text-[var(--st-color-text-muted)]">
                                约束：{constraints.map((item: DataExplorationConstraint, index) => (
                                  <Tag key={`${item.constraintType}-${index}`}>
                                    {item.constraintType || 'CONSTRAINT'} ({(item.columns || []).join(', ') || '-'})
                                  </Tag>
                                ))}
                              </div>
                            )}
                          </div>
                        ),
                      },
                      {
                        key: 'profile',
                        label: <span><ProfileOutlined /> 探查结果</span>,
                        children: (
                          <div>
                            <div className="mb-3 flex flex-wrap items-center gap-2 text-xs text-[var(--st-color-text-muted)]">
                              <span>最近成功探查：{formatTime(profile?.profileTime)}</span>
                              {profile?.table?.rowCount !== undefined && <Tag>行数 {profile.table.rowCount}</Tag>}
                              {profile?.table?.columnCount !== undefined && <Tag>字段 {profile.table.columnCount}</Tag>}
                            </div>
                            <Table<DataExplorationColumnProfile>
                              size="small"
                              rowKey={(item) => item.name}
                              columns={profileColumns}
                              dataSource={profile?.columns || []}
                              pagination={false}
                              scroll={{ x: 760, y: 360 }}
                              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无成功探查结果" /> }}
                            />
                          </div>
                        ),
                      },
                      {
                        key: 'preview',
                        label: <span><EyeOutlined /> Top20 预览</span>,
                        children: (
                          <div>
                            <div className="mb-3 flex items-center justify-between gap-2 text-xs text-[var(--st-color-text-muted)]">
                              <span>复用现有数据源目录预览，仅展示最多 20 行。</span>
                              <Button type="primary" size="small" icon={<EyeOutlined />} loading={previewLoading} onClick={() => void loadPreview()}>
                                加载预览
                              </Button>
                            </div>
                            {preview ? (
                              <Table<Record<string, unknown>>
                                size="small"
                                rowKey={(_, index) => String(index ?? 0)}
                                columns={previewColumns}
                                dataSource={preview.data || []}
                                pagination={false}
                                scroll={{ x: 760, y: 360 }}
                                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无预览数据" /> }}
                              />
                            ) : (
                              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击“加载预览”读取 Top20 数据" />
                            )}
                          </div>
                        ),
                      },
                    ]}
                  />
                </>
              )}
            </div>
          </div>
        )}
      </Spin>
    </Drawer>
  );
};

export default DataExplorationDrawer;
