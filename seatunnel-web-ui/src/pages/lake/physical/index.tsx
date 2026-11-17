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
import React, { useMemo, useRef, useState } from 'react';
import {
  createOdsDatabase,
  fetchPhysicalSource,
  fetchPhysicalSources,
  reconcileOdsDatabase,
  retryOdsDatabase,
} from './service';
import type { LakePage, LakeResourceStatus, OdsDatabase, PhysicalDataSource } from './types';
import { useMasterDataNames } from '../useMasterDataNames';
import { withTimeout } from '@/utils/withTimeout';
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

const formatTime = (value?: string) => (value ? value.replace('T', ' ').replace(/\.\d+Z$/, '') : '-');

const resourceTag = (value?: LakeResourceStatus) => (
  <Tag color={value ? statusColor[value] : undefined}>
    {value ? statusLabel[value] || value : '未绑定'}
  </Tag>
);

const PhysicalResourcesPage: React.FC = () => {
  const actionRef = useRef<ActionType>();
  const { unitNameByCode, systemNameByCode } = useMasterDataNames();
  const [operationId, setOperationId] = useState<number>();
  const [operationLoading, setOperationLoading] = useState(false);
  const [startingId, setStartingId] = useState<number>();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [resourceStatus, setResourceStatus] = useState<LakeResourceStatus>();
  const [listError, setListError] = useState<string>();

  const reload = () => {
    actionRef.current?.reloadAndRest?.();
  };

  const operate = async (type: 'retry' | 'reconcile', database?: OdsDatabase) => {
    if (!database?.id) return;
    setOperationId(database.id);
    setOperationLoading(true);
    try {
      const response = type === 'retry' ? await retryOdsDatabase(database.id) : await reconcileOdsDatabase(database.id);
      if (response.code !== 0) throw new Error(response.message || response.msg || '操作失败');
      message.success(type === 'retry' ? '重试操作已提交' : '对账已完成');
      reload();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '操作失败');
    } finally {
      setOperationLoading(false);
      setOperationId(undefined);
    }
  };

  const physicalTypes = ['MYSQL', 'POSTGRE_SQL', 'ORACLE', 'JDBC', 'DORIS', 'DAMENG', 'KINGBASE', 'VASTBASE'];
  const startPhysical = async (record: PhysicalDataSource) => {
    setStartingId(record.sourceDataSourceId);
    try {
      // Re-read before creating so a stale list cannot produce a second binding.
      const latest = await fetchPhysicalSource(record.sourceDataSourceId);
      if (latest.code !== 0 || !latest.data) throw new Error(latest.message || latest.msg || '数据源读取失败');
      if (latest.data.sourceEnabled === false) {
        message.warning('请先在数据源管理中为该连接开启来源用途');
        history.push('/data-source');
        return;
      }
      let database = latest.data.odsDatabase;
      if (!database || database.resourceStatus === 'DELETED') {
        const created = await createOdsDatabase(record.sourceDataSourceId, `ds_${record.sourceDataSourceId}`);
        if (created.code !== 0 || !created.data) throw new Error(created.message || created.msg || 'ODS 库准备失败，请检查数据源的单位和业务系统归属');
        database = created.data;
      }
      if (database.resourceStatus !== 'READY') {
        message.info('ODS 库尚未就绪，请在资源详情中查看状态或重试');
        history.push(`/lake/resources/${record.sourceDataSourceId}`);
        return;
      }
      history.push(`/lake/resources/table/create?sourceDataSourceId=${record.sourceDataSourceId}&odsDatabaseBindingId=${database.id}`);
    } catch (error) { message.error(error instanceof Error ? error.message : '准备入湖失败'); }
    finally { setStartingId(undefined); reload(); }
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
          <Button type="link" className="lake-source-link" onClick={() => history.push(`/lake/resources/${record.sourceDataSourceId}`)}>
            {record.sourceDataSourceName || `DataSource #${record.sourceDataSourceId}`}
            <RightOutlined />
          </Button>
        ),
      },
      {
        title: 'ODS 库',
        key: 'databaseName',
        width: 230,
        ellipsis: true,
        render: (_, record) => record.odsDatabase?.databaseName || <Text type="secondary">未创建</Text>,
      },
      {
        title: '资源状态',
        key: 'resourceStatus',
        width: 130,
        render: (_, record) => resourceTag(record.odsDatabase?.resourceStatus),
        valueType: 'select',
        valueEnum: Object.fromEntries(RESOURCE_STATUS.map((item) => [item.value, { text: item.label }])),
      },
      {
        title: '最近对账',
        key: 'lastReconcileAt',
        width: 170,
        responsive: ['xxl'],
        render: (_, record) => formatTime(record.odsDatabase?.lastReconcileAt),
      },
      {
        title: '操作',
        key: 'option',
        valueType: 'option',
        render: (_, record) => {
          const database = record.odsDatabase;
          const canRetry = !!database && ['ERROR', 'CREATE_FAILED', 'MISSING', 'UNKNOWN', 'PENDING_CREATE'].includes(database.resourceStatus || '');
          const canReconcile = !!database && database.resourceStatus !== 'DELETED';
          const supported = physicalTypes.includes(String(record.dbType || ''));
          const busy = !!database && ['CREATING', 'DELETING'].includes(database.resourceStatus || '');
          // Keep every applicable action visible; wrapping preserves room for readable labels.
          return <Space className="lake-physical-actions" wrap size={0}>
            <Tooltip title={record.sourceEnabled === false ? '该连接未开启来源用途，请先到数据源管理中开启' : !supported ? '此数据类型尚不支持可视化创建 ODS 表' : busy ? '资源正在处理，可查看详情' : undefined}>
              <Button type="link" disabled={!supported || busy || record.sourceEnabled === false} loading={startingId === record.sourceDataSourceId}
                onClick={() => void startPhysical(record)}>{database?.resourceStatus === 'READY' ? '继续建表' : '开始入湖'}</Button>
            </Tooltip>
            <Button type="link" onClick={() => history.push(`/lake/resources/${record.sourceDataSourceId}`)}>查看资源详情</Button>
            {canRetry && <Button type="link" loading={operationLoading && operationId === database?.id}
              onClick={() => void operate('retry', database)}>重试</Button>}
            {canReconcile && <Button type="link" loading={operationLoading && operationId === database?.id}
              onClick={() => void operate('reconcile', database)}>刷新实际状态</Button>}
          </Space>;
        },
      },
    ],
    [operationId, operationLoading, startingId, unitNameByCode, systemNameByCode],
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
          params={{ current: page, pageSize }}
          request={async (params) => {
            setListError(undefined);
            try {
              const response = await withTimeout(
                fetchPhysicalSources({
                  pageNo: params.current || 1,
                  pageSize: params.pageSize || 10,
                  keyword: keyword || undefined,
                  resourceStatus: resourceStatus || undefined,
                }),
                10000,
                '物理入湖列表请求超时，请稍后重试',
              );
              if (response.code !== 0) throw new Error(response.message || response.msg || '物理入湖列表加载失败');
              const page = unwrapPage(response.data);
              setTotal(page.total);
              return { data: page.rows, success: true, total: page.total };
            } catch (error) {
              setTotal(0);
              setListError(error instanceof Error ? error.message : '物理入湖列表加载失败');
              return { data: [], success: false, total: 0 };
            }
          }}
          toolBarRender={() => [
            <Input.Search
              key="search"
              allowClear
              placeholder="搜索数据源名称"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              onSearch={() => { setPage(1); actionRef.current?.reload(); }}
              style={{ width: 'min(240px, 100%)' }}
            />,
            <Select
              key="status"
              allowClear
              placeholder="状态"
              value={resourceStatus}
              options={RESOURCE_STATUS.map((item) => ({ label: item.label, value: item.value }))}
              onChange={(value) => {
                setResourceStatus(value);
                setPage(1);
                window.setTimeout(() => actionRef.current?.reload(true), 0);
              }}
              style={{ width: 'min(130px, 100%)' }}
            />,
          ]}
          tableAlertRender={false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已接入的数据源" /> }}
        />
        <div className="lake-fixed-pagination"><Pagination current={page} pageSize={pageSize} total={total} showSizeChanger showTotal={(count) => `共 ${count} 个数据源`} onChange={(next, size) => { setPage(next); setPageSize(size); }} /></div>
      </div>
    </PageContainer>
  );
};

export default PhysicalResourcesPage;
