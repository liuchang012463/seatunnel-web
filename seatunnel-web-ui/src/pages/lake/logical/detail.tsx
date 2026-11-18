import type {
  LakeApiResponse,
  LakeCatalog,
  LakePhysicalDataSource,
} from '@/services/lake';
import {
  deleteCatalog,
  fetchCatalog,
  fetchPhysicalSource,
  reconcileCatalog,
  refreshCatalog,
  updateCatalog,
  validateCatalog,
} from '@/services/lake';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  EyeOutlined,
  ReloadOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import React, { useEffect, useState } from 'react';
import CatalogScopePicker from './CatalogScopePicker';
import './index.less';
import {
  adapterLabel,
  catalogScopeLabel,
  catalogStatusLabel,
  catalogStatusColor as statusColor,
} from './presentation';

const responseMessage = (response: LakeApiResponse<unknown>) =>
  response.msg || response.message || '请求失败，请稍后重试';

interface CatalogUpdateValues {
  targetCatalogName: string;
  adapter: string;
  scope: string;
  databaseInclude?: string[];
  tableInclude?: string[];
}

const CatalogUpdateDrawer: React.FC<{
  catalog: LakeCatalog;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}> = ({ catalog, open, onClose, onSaved }) => {
  const [form] = Form.useForm<CatalogUpdateValues>();
  const [saving, setSaving] = useState(false);
  const submit = async (values: CatalogUpdateValues) => {
    if (!catalog.id || catalog.lockVersion == null) return;
    setSaving(true);
    try {
      const response = await updateCatalog(catalog.id, {
        ...values,
        databaseInclude: values.databaseInclude || [],
        tableInclude: values.tableInclude || [],
        options: {},
        expectedLockVersion: catalog.lockVersion,
      });
      if (response.code !== 0) throw new Error(responseMessage(response));
      message.success('逻辑挂载配置已更新并验证');
      onClose();
      onSaved();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '更新逻辑挂载失败');
    } finally {
      setSaving(false);
    }
  };
  return (
    <Drawer
      open={open}
      title="调整挂载范围"
      width={760}
      destroyOnHidden
      onClose={onClose}
      footer={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={() => form.submit()}>
            保存并验证
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">勾选需要通过数据湖查询的库表，保存时自动检查配置。</Typography.Paragraph>
      <Form
        name="lake-catalog-update"
        form={form}
        layout="vertical"
        onFinish={submit}
        onFinishFailed={({ errorFields }) => message.warning(errorFields[0]?.errors[0] || '请完善挂载范围')}
        initialValues={{
          targetCatalogName: catalog.targetCatalogName,
          adapter: catalog.adapter,
          scope: catalog.scope,
          databaseInclude: catalog.databaseInclude || [],
          tableInclude: catalog.tableInclude || [],
        }}
        style={{ marginTop: 18 }}
      >
        <Form.Item name="targetCatalogName" hidden>
          <Input />
        </Form.Item>
        <Form.Item name="adapter" hidden>
          <Input />
        </Form.Item>
        <CatalogScopePicker form={form} sourceId={catalog.sourceDataSourceId} adapter={catalog.adapter} />
      </Form>
    </Drawer>
  );
};

const LogicalCatalogDetail: React.FC = () => {
  const { catalogId } = useParams<{ catalogId?: string }>();
  const [catalog, setCatalog] = useState<LakeCatalog>();
  const [source, setSource] = useState<LakePhysicalDataSource>();
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string>();
  const [updateOpen, setUpdateOpen] = useState(false);
  const load = async () => {
    if (!catalogId) return;
    try {
      const response = await fetchCatalog(catalogId);
      if (response.code !== 0) throw new Error(responseMessage(response));
      setCatalog(response.data);
      if (response.data?.sourceDataSourceId) {
        void fetchPhysicalSource(response.data.sourceDataSourceId)
          .then((result) => {
            if (result.code === 0) setSource(result.data);
          })
          .catch(() => undefined);
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '加载挂载失败');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    setLoading(true);
    setCatalog(undefined);
    setSource(undefined);
    void load();
  }, [catalogId]);
  const execute = async (name: string, action: (id: string) => Promise<LakeApiResponse<LakeCatalog>>) => {
    if (!catalogId) return;
    setActionLoading(name);
    try {
      const response = await action(catalogId);
      if (response.code !== 0) throw new Error(responseMessage(response));
      message.success(name + '完成');
      if (name === '移除挂载') {
        history.push('/lake/logical-access');
        return;
      }
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : name + '失败');
    } finally {
      setActionLoading(undefined);
    }
  };
  const confirmDelete = () => {
    Modal.confirm({
      title: '移除此挂载？',
      content: '移除后将无法通过此挂载查询数据，源数据库中的数据会保留。',
      okText: '确认移除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => execute('移除挂载', deleteCatalog),
    });
  };
  if (loading)
    return (
      <PageContainer title={false} className="lake-logical-detail">
        <div className="lake-detail-loading">
          <Spin />
          正在加载挂载…
        </div>
      </PageContainer>
    );
  if (!catalog)
    return (
      <PageContainer title={false}>
        <Empty description="未找到此挂载">
          <Button onClick={() => history.push('/lake/logical-access')}>返回挂载列表</Button>
        </Empty>
      </PageContainer>
    );
  const busy = Boolean(actionLoading) || catalog.resourceStatus === 'DELETING';
  const unavailable = Boolean(catalog.deleted) || catalog.resourceStatus !== 'READY';
  const sourceName = source?.sourceDataSourceName || '数据源 #' + catalog.sourceDataSourceId;
  const errorHints: Record<string, string> = {
    LAKE_DORIS_UNAVAILABLE: '暂时无法确认挂载状态，请检查数据湖连接，或通过“更多操作”重新检查挂载。',
    LAKE_CATALOG_VALIDATION_FAILED: '挂载配置与实际状态有差异，请调整挂载范围后重新保存。',
    LAKE_CATALOG_REQUEST_INVALID: '挂载配置不完整，请检查数据源连接与挂载范围。',
    LAKE_OPERATION_STALE: '挂载状态已更新，请重新检查后重试。',
  };
  const mountInfo = (
    <div className="lake-mount-info">
      <Descriptions bordered column={2}>
        <Descriptions.Item label="数据来源">{sourceName}</Descriptions.Item>
        <Descriptions.Item label="数据库类型">{adapterLabel(catalog.adapter)}</Descriptions.Item>
        <Descriptions.Item label="挂载范围" span={2}>
          {catalogScopeLabel(catalog)}
        </Descriptions.Item>
        {catalog.scope === 'TABLE' ? (
          <Descriptions.Item label="已挂载数据表" span={2}>
            {catalog.tableInclude?.join('、') || '未选择'}
          </Descriptions.Item>
        ) : null}
        <Descriptions.Item label="查询状态">
          <Tag color={statusColor(catalog.resourceStatus)}>{catalogStatusLabel(catalog.resourceStatus)}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="配置检查">
          <Tag color={statusColor(catalog.validationStatus)}>{catalogStatusLabel(catalog.validationStatus)}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="最近检查" span={2}>
          {catalog.lastObservedAt || '尚未检查'}
        </Descriptions.Item>
      </Descriptions>
      <Typography.Paragraph type="secondary" className="lake-info-note">
        源端新增或变更库表后，可点击“刷新库表”更新。挂载范围包含整库或整个 Schema 时，新增表会自动纳入。
      </Typography.Paragraph>
      <Collapse
        ghost
        items={[
          {
            key: 'technical',
            label: '技术信息（排查问题时查看）',
            children: (
              <>
                <Descriptions size="small" bordered column={2}>
                  <Descriptions.Item label="挂载标识">{catalog.targetCatalogName}</Descriptions.Item>
                  <Descriptions.Item label="数据源编号">{catalog.sourceDataSourceId}</Descriptions.Item>
                  <Descriptions.Item label="配置版本">{catalog.lockVersion}</Descriptions.Item>
                  <Descriptions.Item label="最近状态同步">
                    {catalog.lastReconcileAt || '尚未同步'}
                  </Descriptions.Item>
                </Descriptions>
                {catalog.errorCode ? (
                  <Typography.Paragraph>
                    错误信息：{catalog.errorCode} · {catalog.errorMessage}
                  </Typography.Paragraph>
                ) : null}
                <Collapse
                  ghost
                  items={[
                    {
                      key: 'snapshot',
                      label: '查看脱敏配置快照',
                      children: (
                        <pre className="lake-snapshot">{JSON.stringify(catalog.actualSnapshot || {}, null, 2)}</pre>
                      ),
                    },
                  ]}
                />
              </>
            ),
          },
        ]}
      />
    </div>
  );
  return (
    <PageContainer title={false} className="lake-logical-detail">
      <div className="lake-detail-toolbar">
        <div className="lake-detail-identity">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            aria-label="返回挂载列表"
            onClick={() => history.push('/lake/logical-access')}
          />
          <Typography.Text strong ellipsis={{ tooltip: sourceName }}>
            {sourceName}
          </Typography.Text>
          <Tag color={statusColor(catalog.resourceStatus)}>{catalogStatusLabel(catalog.resourceStatus)}</Tag>
        </div>
        <Space wrap>
          <Button
            icon={<EyeOutlined />}
            disabled={unavailable || !catalogId}
            onClick={() => history.push(`/lake/catalog?catalogId=${encodeURIComponent(catalogId || '')}`)}
          >
            在目录查看
          </Button>
          <Button
            icon={<EditOutlined />}
            aria-label="调整挂载范围"
            disabled={busy || Boolean(catalog.deleted)}
            onClick={() => setUpdateOpen(true)}
          >
            调整挂载范围
          </Button>
          <Tooltip title="重新读取源端库表，更新当前可查询的数据表">
            <Button
              icon={<ReloadOutlined />}
              aria-label="刷新库表"
              loading={actionLoading === '刷新库表'}
              disabled={busy || Boolean(catalog.deleted)}
              onClick={() => void execute('刷新库表', refreshCatalog)}
            >
              刷新库表
            </Button>
          </Tooltip>
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                {
                  key: 'validate',
                  label: '重新检查挂载',
                  icon: <CheckOutlined />,
                  onClick: () => void execute('检查挂载', validateCatalog),
                },
                {
                  key: 'reconcile',
                  label: '同步挂载状态',
                  icon: <SyncOutlined />,
                  onClick: () => void execute('同步挂载状态', reconcileCatalog),
                },
                { type: 'divider' },
                {
                  key: 'remove',
                  label: '移除挂载',
                  danger: true,
                  icon: <DeleteOutlined />,
                  onClick: confirmDelete,
                },
              ],
            }}
            disabled={busy || Boolean(catalog.deleted)}
          >
            <Button
              loading={Boolean(actionLoading) && actionLoading !== '刷新库表'}
              aria-label="更多操作"
              aria-haspopup="menu"
            >
              更多操作 <DownOutlined />
            </Button>
          </Dropdown>
        </Space>
      </div>
      {catalog.errorCode ? (
        <Alert
          type="warning"
          showIcon
          message="挂载需要检查"
          description={errorHints[catalog.errorCode] || '请重新检查挂载，或在挂载信息中查看处理线索。'}
        />
      ) : null}
      {mountInfo}
      <CatalogUpdateDrawer
        key={String(catalog.id) + ':' + catalog.lockVersion}
        catalog={catalog}
        open={updateOpen}
        onClose={() => setUpdateOpen(false)}
        onSaved={() => void load()}
      />
    </PageContainer>
  );
};

export default LogicalCatalogDetail;
