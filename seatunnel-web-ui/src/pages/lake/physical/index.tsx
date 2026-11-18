import {
  LinkOutlined,
  ReloadOutlined,
  RightOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import {
  Alert,
  Button,
  Empty,
  Input,
  Select,
  Space,
  Tooltip,
  Pagination,
  Tag,
  Typography,
  message,
} from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  createOdsDatabase,
  fetchPhysicalSource,
  fetchPhysicalSources,
} from './service';
import type { LakePage, LakeResourceStatus, OdsDatabase, PhysicalDataSource } from './types';
import { useMasterDataNames } from '../useMasterDataNames';
import { withTimeout } from '@/utils/withTimeout';
import { DATA_SOURCE_CATEGORIES, type DataSourceCategoryKey } from '@/pages/data-source/dataSourceRegistry';
import OdsNameDialog from './OdsNameDialog';
import { physicalSourcePath, physicalTableCreatePath } from './paths';
import './index.less';

const { Text } = Typography;

const RESOURCE_STATUS: Array<{ label: string; value: LakeResourceStatus }> = [
  { label: '创建中', value: 'CREATING' },
  { label: '待创建', value: 'PENDING_CREATE' },
  { label: '已就绪', value: 'READY' },
  { label: '失败', value: 'CREATE_FAILED' },
  { label: '异常', value: 'ERROR' },
  { label: '缺失', value: 'MISSING' },
  { label: '未知', value: 'UNKNOWN' },
  { label: '删除中', value: 'DELETING' },
  { label: '已删除', value: 'DELETED' },
];

const statusLabel: Record<string, string> = Object.fromEntries(RESOURCE_STATUS.map((item) => [item.value, item.label]));
const statusColor: Record<string, string> = {
  CREATING: 'processing',
  PENDING_CREATE: 'processing',
  READY: 'success',
  CREATE_FAILED: 'error',
  ERROR: 'error',
  MISSING: 'error',
  UNKNOWN: 'warning',
  DELETING: 'processing',
  DELETED: 'default',
};

const unwrapPage = (data?: LakePage<PhysicalDataSource>): { rows: PhysicalDataSource[]; total: number } => ({
  rows: Array.isArray(data?.bizData) ? data.bizData : [],
  // Some deployed MyBatis versions return total=0 with a usable page.
  total: Number(data?.pagination?.total || data?.bizData?.length || 0),
});

const sourceCategories = [{ key: 'ALL' as const, label: '全部' }, ...DATA_SOURCE_CATEGORIES];

const resourceTag = (value?: LakeResourceStatus) => (
  <Tag color={value ? statusColor[value] : undefined}>
    {value ? statusLabel[value] || value : '未绑定'}
  </Tag>
);

const PhysicalResourcesPage: React.FC = () => {
  const actionRef = useRef<ActionType>();
  const { unitNameByCode, systemNameByCode } = useMasterDataNames();
  const [startingId, setStartingId] = useState<number>();
  const [naming, setNaming] = useState<{ sourceId: number; navigation: number; defaultName?: string }>();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [total, setTotal] = useState(0);
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [category, setCategory] = useState<DataSourceCategoryKey | 'ALL'>('RELATIONAL');
  const [resourceStatus, setResourceStatus] = useState<LakeResourceStatus>();
  const [listError, setListError] = useState<string>();
  const listRequest = useRef(0);
  const navigationRequest = useRef(0);

  useEffect(() => () => {
    listRequest.current += 1;
    navigationRequest.current += 1;
  }, []);

  const openSource = (sourceId: number) => {
    navigationRequest.current += 1;
    history.push(physicalSourcePath(sourceId));
  };

  const reload = () => {
    actionRef.current?.reload();
  };

  const physicalTypes = ['MYSQL', 'POSTGRE_SQL', 'ORACLE', 'JDBC', 'DORIS', 'DAMENG', 'KINGBASE', 'VASTBASE'];
  const openDatabase = (sourceId: number, database: OdsDatabase) => {
    if (database.sourceDataSourceId != null && String(database.sourceDataSourceId) !== String(sourceId)) {
      console.warn('[lake-physical-list] 入湖库与所选行不匹配', { sourceId, bindingId: database.id });
      throw new Error('入湖库与当前数据源不匹配，请刷新后重试。');
    }
    if (database.resourceStatus !== 'READY') {
      message.info('ODS 库尚未就绪，请在资源详情中查看状态或重试');
      history.push(physicalSourcePath(sourceId));
      return;
    }
    history.push(physicalTableCreatePath(sourceId, database.id));
  };

  const startPhysical = async (record: PhysicalDataSource) => {
    const navigation = ++navigationRequest.current;
    setStartingId(record.sourceDataSourceId);
    try {
      // Re-read before creating so a stale list cannot produce a second binding.
      const latest = await fetchPhysicalSource(record.sourceDataSourceId);
      if (navigation !== navigationRequest.current) return;
      if (latest.code !== 0 || !latest.data) throw new Error(latest.message || latest.msg || '数据源读取失败');
      if (String(latest.data.sourceDataSourceId) !== String(record.sourceDataSourceId)) {
        console.warn('[lake-physical-list] 数据源与所选行不匹配', { sourceId: record.sourceDataSourceId });
        throw new Error('数据源已变化，请刷新列表后重试。');
      }
      if (latest.data.sourceEnabled === false) {
        message.warning('请先在数据源管理中为该连接开启来源用途');
        history.push('/data-source');
        return;
      }
      let database = latest.data.odsDatabase;
      if (!database || database.resourceStatus === 'DELETED') {
        if (!latest.data.previousOdsName) {
          setNaming({
            sourceId: record.sourceDataSourceId,
            navigation,
            defaultName: latest.data.sourceDataSourceName,
          });
          return;
        }
        // Restore the prior binding using its recorded name.
        database = await createOdsDatabase(record.sourceDataSourceId, latest.data.previousOdsName);
        if (navigation !== navigationRequest.current) return;
      }
      openDatabase(record.sourceDataSourceId, database);
    } catch (error) {
      if (navigation === navigationRequest.current) message.error(error instanceof Error ? error.message : '准备入湖失败');
    } finally {
      if (navigation === navigationRequest.current) {
        setStartingId(undefined);
        reload();
      }
    }
  };

  const columns = useMemo<ProColumns<PhysicalDataSource>[]>(
    () => [
      {
        title: '单位 / 业务系统',
        key: 'owner',
        width: 190,
        render: (_, record) => (
          <div>
            <div>{unitNameByCode(record.unitCode) || '待归属'}</div>
            <Text type="secondary">{systemNameByCode(record.systemCode) || '待归属'}</Text>
          </div>
        ),
      },
      {
        title: '数据源',
        dataIndex: 'sourceDataSourceName',
        key: 'sourceDataSourceName',
        width: 210,
        ellipsis: true,
        render: (_, record) => (
          <Button type="link" className="lake-source-link" onClick={() => openSource(record.sourceDataSourceId)}>
            {record.sourceDataSourceName || `DataSource #${record.sourceDataSourceId}`}
            <RightOutlined />
          </Button>
        ),
      },
      {
        title: '入湖库',
        key: 'displayName',
        width: 230,
        render: (_, record) => record.odsDatabase ? (
          <Text style={{ overflowWrap: 'anywhere' }}>
            {record.odsDatabase.displayName || record.sourceDataSourceName || '入湖库'}
          </Text>
        ) : <Text type="secondary">未创建</Text>,
      },
      {
        title: '入湖库状态',
        key: 'resourceStatus',
        width: 130,
        render: (_, record) => resourceTag(record.odsDatabase?.resourceStatus),
        valueType: 'select',
        valueEnum: Object.fromEntries(RESOURCE_STATUS.map((item) => [item.value, { text: item.label }])),
      },
      {
        title: '操作',
        key: 'option',
        valueType: 'option',
        render: (_, record) => {
          const database = record.odsDatabase;
          const hasDatabase = Boolean(database && database.resourceStatus !== 'DELETED');
          const ready = database?.resourceStatus === 'READY';
          const supported = physicalTypes.includes(String(record.dbType || ''));
          // Existing bindings that need attention recover from their own detail page.
          return <Space className="lake-physical-actions" wrap size={0}>
            {(!hasDatabase || ready) && (
              <Tooltip title={record.sourceEnabled === false ? '该连接未开启来源用途，请先到数据源管理中开启' : !supported ? '此数据类型尚不支持可视化创建 ODS 表' : undefined}>
                <Button type="link" disabled={!supported || record.sourceEnabled === false} loading={startingId === record.sourceDataSourceId}
                  onClick={() => void startPhysical(record)}>{ready ? '新增入湖表' : '开始入湖'}</Button>
              </Tooltip>
            )}
            {hasDatabase && (
              <Button type="link" onClick={() => openSource(record.sourceDataSourceId)}>
                {ready ? '查看入湖表' : '查看入湖状态'}
              </Button>
            )}
          </Space>;
        },
      },
    ],
    [startingId, unitNameByCode, systemNameByCode],
  );

  return (
    <PageContainer
      title={false}
      extra={[
        <Button key="datasource" type="link" icon={<LinkOutlined />} onClick={() => history.push('/data-source')}>
          查看数据源
        </Button>,
        <Button key="refresh" icon={<ReloadOutlined />} onClick={reload}>
          刷新列表
        </Button>,
      ]}
    >
      <div className="lake-physical-page">
        {listError ? (
          <Alert
            className="lake-table-alert"
            type="error"
            showIcon
            message="物理入湖列表加载失败"
            description={listError}
            action={<Button type="link" onClick={reload}>重试</Button>}
          />
        ) : null}
        <ProTable<PhysicalDataSource>
          actionRef={actionRef}
          rowKey="sourceDataSourceId"
          cardBordered
          columns={columns}
          scroll={{ x: '100%' }}
          tableLayout="fixed"
          search={false}
          options={false}
          pagination={false}
          headerTitle={<Space wrap role="group" aria-label="数据源分类">
            {sourceCategories.map((item) => <Button key={item.key} aria-pressed={category === item.key}
              type={category === item.key ? 'primary' : 'default'} onClick={() => { setCategory(item.key); setPage(1); }}>
              {item.label}
            </Button>)}
          </Space>}
          params={{ current: page, pageSize, keyword, resourceStatus, category }}
          request={async (params) => {
            const request = ++listRequest.current;
            setListError(undefined);
            try {
              const response = await withTimeout(
                fetchPhysicalSources({
                  pageNo: params.current || 1,
                  pageSize: params.pageSize || 10,
                  keyword: params.keyword || undefined,
                  resourceStatus: params.resourceStatus,
                  dbTypes: DATA_SOURCE_CATEGORIES.find((item) => item.key === params.category)?.dbTypes,
                }),
                10000,
                '物理入湖列表请求超时，请稍后重试',
              );
              if (request !== listRequest.current) return { data: [], success: false, total: 0 };
              if (response.code !== 0) throw new Error(response.message || response.msg || '物理入湖列表加载失败');
              const page = unwrapPage(response.data);
              setTotal(page.total);
              return { data: page.rows, success: true, total: page.total };
            } catch (error) {
              if (request === listRequest.current) {
                setTotal(0);
                setListError(error instanceof Error ? error.message : '物理入湖列表加载失败');
              }
              return { data: [], success: false, total: 0 };
            }
          }}
          toolBarRender={() => [
            <Input.Search
              key="search"
              allowClear
              placeholder="搜索数据源名称"
              value={keywordInput}
              onChange={(event) => setKeywordInput(event.target.value)}
              onSearch={(value) => { setPage(1); setKeyword(value.trim()); }}
              style={{ width: 'min(240px, 100%)' }}
            />,
            <Select
              key="status"
              aria-label="筛选入湖库状态"
              allowClear
              placeholder="状态"
              value={resourceStatus}
              options={RESOURCE_STATUS.map((item) => ({ label: item.label, value: item.value }))}
              onChange={(value) => {
                setResourceStatus(value);
                setPage(1);
              }}
              style={{ width: 'min(130px, 100%)' }}
            />,
          ]}
          tableAlertRender={false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前筛选条件下没有数据源" /> }}
        />
        <div className="lake-fixed-pagination"><Pagination current={page} pageSize={pageSize} total={total} showSizeChanger showTotal={(count) => `共 ${count} 个数据源`} onChange={(next, size) => { setPage(next); setPageSize(size); }} /></div>
      </div>
      {naming ? <OdsNameDialog
        defaultName={naming.defaultName}
        onCancel={() => setNaming(undefined)}
        onSubmit={async (name) => {
          const database = await createOdsDatabase(naming.sourceId, name);
          if (naming.navigation !== navigationRequest.current) return;
          setNaming(undefined);
          reload();
          openDatabase(naming.sourceId, database);
        }}
      /> : null}
    </PageContainer>
  );
};

export default PhysicalResourcesPage;
