import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import {
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tabs,
  Typography,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  createBusinessSystem,
  createDataSourceUnit,
  deleteBusinessSystem,
  deleteDataSourceUnit,
  fetchActiveDataSourceUnits,
  fetchBusinessSystemPage,
  fetchDataSourceUnitPage,
  normalizeOptions,
  normalizePageData,
  toUnitOptions,
  updateBusinessSystem,
  updateDataSourceUnit,
} from './service';
import type {
  ApiResponse,
  BusinessSystemPageParams,
  BusinessSystemPayload,
  BusinessSystemRecord,
  DataSourceUnitPageParams,
  DataSourceUnitPayload,
  DataSourceUnitRecord,
  MasterDataId,
  MasterDataOption,
  MasterDataPage,
  MasterDataStatus,
} from './types';
import './index.less';

type TabKey = 'units' | 'business-systems';
type EditorKind = 'unit' | 'business-system';

interface MasterDataPageProps {
  /** Render the maintenance workspace inside the data-source drawer. */
  embedded?: boolean;
}

const DEFAULT_PAGE_SIZE = 10;
const EMPTY_UNIT_PAGE: MasterDataPage<DataSourceUnitRecord> = {
  records: [],
  pagination: { pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, total: 0 },
};
const EMPTY_SYSTEM_PAGE: MasterDataPage<BusinessSystemRecord> = {
  records: [],
  pagination: { pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, total: 0 },
};

interface UnitSearchValues {
  unitName?: string;
  status?: MasterDataStatus;
}

interface BusinessSystemSearchValues {
  unitId?: MasterDataId;
  systemName?: string;
  status?: MasterDataStatus;
}

interface EditorFormValues {
  unitName?: string;
  unitId?: MasterDataId;
  systemName?: string;
  status?: boolean;
  remark?: string;
}

function cleanString(value?: string): string | undefined {
  const trimmed = value?.trim();
  return trimmed || undefined;
}

/** Internal uniqueness codes are generated; operators maintain names only. */
function generatedCode(prefix: string, parts: Array<string | number | undefined>): string {
  const source = parts.filter((part) => part !== undefined && part !== null).join('|').trim();
  let hash = 2166136261;
  for (let index = 0; index < source.length; index += 1) {
    hash ^= source.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return `${prefix}_${(hash >>> 0).toString(36).toUpperCase()}`;
}

function normalizeStatus(value?: boolean): MasterDataStatus {
  return value === false ? 0 : 1;
}

function responseMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

function ensureSuccess(response: ApiResponse<unknown> | undefined, fallback: string): void {
  if (!response || response.code !== 0) {
    throw new Error(response?.message || response?.msg || fallback);
  }
}

function StatusLabel({ status }: { status: MasterDataStatus }) {
  return status === 1 ? (
    <Tag color="success">启用</Tag>
  ) : (
    <Tag>停用</Tag>
  );
}

function formatDate(value?: string): string {
  return value || '-';
}

const MasterDataPage: React.FC<MasterDataPageProps> = ({ embedded = false }) => {
  const [activeTab, setActiveTab] = useState<TabKey>('units');
  const [unitPage, setUnitPage] = useState(EMPTY_UNIT_PAGE);
  const [businessSystemPage, setBusinessSystemPage] = useState(EMPTY_SYSTEM_PAGE);
  const [unitQuery, setUnitQuery] = useState<DataSourceUnitPageParams>({
    pageNo: 1,
    pageSize: DEFAULT_PAGE_SIZE,
  });
  const [businessSystemQuery, setBusinessSystemQuery] = useState<BusinessSystemPageParams>({
    pageNo: 1,
    pageSize: DEFAULT_PAGE_SIZE,
  });
  const [unitLoading, setUnitLoading] = useState(false);
  const [businessSystemLoading, setBusinessSystemLoading] = useState(false);
  const [unitOptions, setUnitOptions] = useState<DataSourceUnitRecord[]>([]);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editorKind, setEditorKind] = useState<EditorKind>('unit');
  const [editingUnit, setEditingUnit] = useState<DataSourceUnitRecord>();
  const [editingBusinessSystem, setEditingBusinessSystem] = useState<BusinessSystemRecord>();
  const [saving, setSaving] = useState(false);
  const [busyKey, setBusyKey] = useState<string>();
  const [unitSearchForm] = Form.useForm<UnitSearchValues>();
  const [businessSystemSearchForm] = Form.useForm<BusinessSystemSearchValues>();
  const [editorForm] = Form.useForm<EditorFormValues>();
  const [messageApi, contextHolder] = message.useMessage();

  const loadUnitOptions = useCallback(async () => {
    try {
      const response = await fetchActiveDataSourceUnits();
      ensureSuccess(response, '加载单位选项失败');
      setUnitOptions(normalizeOptions(response));
    } catch (error) {
      messageApi.error(responseMessage(error, '加载单位选项失败'));
    }
  }, [messageApi]);

  const loadUnitPage = useCallback(async () => {
    setUnitLoading(true);
    try {
      const response = await fetchDataSourceUnitPage(unitQuery);
      ensureSuccess(response, '加载单位列表失败');
      setUnitPage(normalizePageData(response, unitQuery));
    } catch (error) {
      messageApi.error(responseMessage(error, '加载单位列表失败'));
    } finally {
      setUnitLoading(false);
    }
  }, [messageApi, unitQuery]);

  const loadBusinessSystemPage = useCallback(async () => {
    setBusinessSystemLoading(true);
    try {
      const response = await fetchBusinessSystemPage(businessSystemQuery);
      ensureSuccess(response, '加载业务系统列表失败');
      setBusinessSystemPage(normalizePageData(response, businessSystemQuery));
    } catch (error) {
      messageApi.error(responseMessage(error, '加载业务系统列表失败'));
    } finally {
      setBusinessSystemLoading(false);
    }
  }, [businessSystemQuery, messageApi]);

  useEffect(() => {
    loadUnitOptions();
  }, [loadUnitOptions]);

  useEffect(() => {
    loadUnitPage();
  }, [loadUnitPage]);

  useEffect(() => {
    if (activeTab === 'business-systems') {
      loadBusinessSystemPage();
    }
  }, [activeTab, loadBusinessSystemPage]);

  const refreshCurrentList = useCallback(async () => {
    if (activeTab === 'units') {
      await Promise.all([loadUnitPage(), loadUnitOptions()]);
    } else {
      await Promise.all([loadBusinessSystemPage(), loadUnitOptions()]);
    }
  }, [activeTab, loadBusinessSystemPage, loadUnitOptions, loadUnitPage]);

  const unitSelectOptions = useMemo(() => {
    const options = toUnitOptions(unitOptions);
    const current = editingBusinessSystem;
    if (current?.unitId == null || options.some((option) => String(option.id) === String(current.unitId))) {
      return options;
    }
    return [
      {
        id: current.unitId,
        label: current.unitName || String(current.unitId),
        unitCode: current.unitCode,
        unitName: current.unitName,
      },
      ...options,
    ];
  }, [editingBusinessSystem, unitOptions]);

  const openCreate = (kind: EditorKind) => {
    setEditorKind(kind);
    setEditingUnit(undefined);
    setEditingBusinessSystem(undefined);
    editorForm.resetFields();
    editorForm.setFieldsValue({ status: true });
    setEditorOpen(true);
  };

  const openEditUnit = (record: DataSourceUnitRecord) => {
    setEditorKind('unit');
    setEditingUnit(record);
    setEditingBusinessSystem(undefined);
    editorForm.setFieldsValue({
      unitName: record.unitName,
      status: record.status === 1,
      remark: record.remark,
    });
    setEditorOpen(true);
  };

  const openEditBusinessSystem = (record: BusinessSystemRecord) => {
    setEditorKind('business-system');
    setEditingBusinessSystem(record);
    setEditingUnit(undefined);
    editorForm.setFieldsValue({
      unitId: record.unitId,
      systemName: record.systemName,
      status: record.status === 1,
      remark: record.remark,
    });
    setEditorOpen(true);
  };

  const closeEditor = () => {
    if (saving) {
      return;
    }
    setEditorOpen(false);
    editorForm.resetFields();
  };

  const handleEditorSubmit = async (values: EditorFormValues) => {
    setSaving(true);
    try {
      if (editorKind === 'unit') {
        const payload: DataSourceUnitPayload = {
          unitCode: editingUnit?.unitCode || generatedCode('UNIT', [values.unitName]),
          unitName: values.unitName?.trim() || '',
          status: normalizeStatus(values.status),
          remark: cleanString(values.remark),
        };
        const response = editingUnit
          ? await updateDataSourceUnit(editingUnit.id, payload)
          : await createDataSourceUnit(payload);
        ensureSuccess(response, editingUnit ? '更新单位失败' : '新增单位失败');
        messageApi.success(editingUnit ? '单位已更新' : '单位已新增');
      } else {
        if (values.unitId == null) {
          throw new Error('请选择所属单位');
        }
        const payload: BusinessSystemPayload = {
          unitId: values.unitId,
          systemCode: editingBusinessSystem?.systemCode
            || generatedCode('SYSTEM', [values.unitId, values.systemName]),
          systemName: values.systemName?.trim() || '',
          status: normalizeStatus(values.status),
          remark: cleanString(values.remark),
        };
        const response = editingBusinessSystem
          ? await updateBusinessSystem(editingBusinessSystem.id, payload)
          : await createBusinessSystem(payload);
        ensureSuccess(response, editingBusinessSystem ? '更新业务系统失败' : '新增业务系统失败');
        messageApi.success(editingBusinessSystem ? '业务系统已更新' : '业务系统已新增');
      }
      setEditorOpen(false);
      editorForm.resetFields();
      await refreshCurrentList();
    } catch (error) {
      messageApi.error(responseMessage(error, editorKind === 'unit' ? '保存单位失败' : '保存业务系统失败'));
    } finally {
      setSaving(false);
    }
  };

  const updateUnitStatus = async (record: DataSourceUnitRecord, checked: boolean) => {
    const key = `unit-status-${record.id}`;
    setBusyKey(key);
    try {
      const response = await updateDataSourceUnit(record.id, {
        unitCode: record.unitCode,
        unitName: record.unitName,
        status: checked ? 1 : 0,
        remark: record.remark,
      });
      ensureSuccess(response, '更新单位状态失败');
      messageApi.success(checked ? '单位已启用' : '单位已停用');
      await refreshCurrentList();
    } catch (error) {
      messageApi.error(responseMessage(error, '更新单位状态失败'));
    } finally {
      setBusyKey(undefined);
    }
  };

  const updateBusinessSystemStatus = async (record: BusinessSystemRecord, checked: boolean) => {
    const key = `system-status-${record.id}`;
    setBusyKey(key);
    try {
      const response = await updateBusinessSystem(record.id, {
        unitId: record.unitId,
        systemCode: record.systemCode,
        systemName: record.systemName,
        status: checked ? 1 : 0,
        remark: record.remark,
      });
      ensureSuccess(response, '更新业务系统状态失败');
      messageApi.success(checked ? '业务系统已启用' : '业务系统已停用');
      await refreshCurrentList();
    } catch (error) {
      messageApi.error(responseMessage(error, '更新业务系统状态失败'));
    } finally {
      setBusyKey(undefined);
    }
  };

  const deleteUnit = async (record: DataSourceUnitRecord) => {
    const key = `unit-delete-${record.id}`;
    setBusyKey(key);
    try {
      const response = await deleteDataSourceUnit(record.id);
      ensureSuccess(response, '删除单位失败');
      messageApi.success('单位已删除');
      await refreshCurrentList();
    } catch (error) {
      messageApi.error(responseMessage(error, '删除单位失败'));
    } finally {
      setBusyKey(undefined);
    }
  };

  const handleDeleteBusinessSystem = async (record: BusinessSystemRecord) => {
    const key = `system-delete-${record.id}`;
    setBusyKey(key);
    try {
      const response = await deleteBusinessSystem(record.id);
      ensureSuccess(response, '删除业务系统失败');
      messageApi.success('业务系统已删除');
      await refreshCurrentList();
    } catch (error) {
      messageApi.error(responseMessage(error, '删除业务系统失败'));
    } finally {
      setBusyKey(undefined);
    }
  };

  const unitColumns: TableColumnsType<DataSourceUnitRecord> = [
    { title: '单位名称', dataIndex: 'unitName', width: 220, ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 130,
      render: (status: MasterDataStatus, record) => (
        <Space size={8} className="master-data-page__status">
          <Switch
            size="small"
            checked={status === 1}
            loading={busyKey === `unit-status-${record.id}`}
            onChange={(checked) => updateUnitStatus(record, checked)}
          />
          <StatusLabel status={status} />
        </Space>
      ),
    },
    { title: '备注', dataIndex: 'remark', ellipsis: true, render: (value?: string) => value || '-' },
    {
      title: '更新时间',
      dataIndex: 'updateTime',
      width: 180,
      render: (value?: string) => formatDate(value),
    },
    {
      title: '操作',
      key: 'actions',
      width: 150,
      fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEditUnit(record)}>
            编辑
          </Button>
          <Popconfirm
            title="确认删除该单位？"
            description="删除前请确认该单位下没有业务系统。"
            okText="删除"
            cancelText="取消"
            onConfirm={() => deleteUnit(record)}
          >
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
              loading={busyKey === `unit-delete-${record.id}`}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const businessSystemColumns: TableColumnsType<BusinessSystemRecord> = [
    { title: '业务系统名称', dataIndex: 'systemName', width: 220, ellipsis: true },
    { title: '所属单位', dataIndex: 'unitName', width: 220, ellipsis: true, render: (value?: string) => value || '-' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 130,
      render: (status: MasterDataStatus, record) => (
        <Space size={8} className="master-data-page__status">
          <Switch
            size="small"
            checked={status === 1}
            loading={busyKey === `system-status-${record.id}`}
            onChange={(checked) => updateBusinessSystemStatus(record, checked)}
          />
          <StatusLabel status={status} />
        </Space>
      ),
    },
    { title: '备注', dataIndex: 'remark', ellipsis: true, render: (value?: string) => value || '-' },
    {
      title: '更新时间',
      dataIndex: 'updateTime',
      width: 180,
      render: (value?: string) => formatDate(value),
    },
    {
      title: '操作',
      key: 'actions',
      width: 150,
      fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEditBusinessSystem(record)}>
            编辑
          </Button>
          <Popconfirm
            title="确认删除该业务系统？"
            description="删除前请确认该业务系统没有被数据源引用。"
            okText="删除"
            cancelText="取消"
            onConfirm={() => handleDeleteBusinessSystem(record)}
          >
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
              loading={busyKey === `system-delete-${record.id}`}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const handleUnitPageChange = (pageNo: number, pageSize: number) => {
    setUnitQuery((current) => ({ ...current, pageNo, pageSize }));
  };

  const handleBusinessSystemPageChange = (pageNo: number, pageSize: number) => {
    setBusinessSystemQuery((current) => ({ ...current, pageNo, pageSize }));
  };

  const tabs = [
    {
      key: 'units',
      label: '单位管理',
      children: (
        <Card className="master-data-page__card master-data-page__panel" bordered>
          <div className="master-data-page__toolbar">
            <Form
              form={unitSearchForm}
              layout="inline"
              className="master-data-page__filters"
              onFinish={(values) =>
                setUnitQuery({
                  pageNo: 1,
                  pageSize: unitQuery.pageSize,
                  unitName: cleanString(values.unitName),
                  status: values.status,
                })
              }
            >
              <Form.Item name="unitName" label="单位名称">
                <Input allowClear placeholder="请输入单位名称" style={{ width: 240 }} />
              </Form.Item>
              <Form.Item name="status" label="状态">
                <Select
                  allowClear
                  placeholder="全部"
                  options={[
                    { label: '启用', value: 1 },
                    { label: '停用', value: 0 },
                  ]}
                  style={{ width: 120 }}
                />
              </Form.Item>
              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                    查询
                  </Button>
                  <Button
                    icon={<ReloadOutlined />}
                    onClick={() => {
                      unitSearchForm.resetFields();
                      setUnitQuery({ pageNo: 1, pageSize: unitQuery.pageSize });
                    }}
                  >
                    重置
                  </Button>
                </Space>
              </Form.Item>
            </Form>
            <div className="master-data-page__actions">
              <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate('unit')}>
                新增单位
              </Button>
            </div>
          </div>
          <Table<DataSourceUnitRecord>
            rowKey={(record) => String(record.id)}
            loading={unitLoading}
            columns={unitColumns}
            dataSource={unitPage.records}
            scroll={{ x: 1000 }}
            pagination={{
              current: unitPage.pagination.pageNo,
              pageSize: unitPage.pagination.pageSize,
              total: unitPage.pagination.total,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50],
              showTotal: (total) => `共 ${total} 条`,
              onChange: handleUnitPageChange,
            }}
          />
        </Card>
      ),
    },
    {
      key: 'business-systems',
      label: '业务系统管理',
      children: (
        <Card className="master-data-page__card master-data-page__panel" bordered>
          <div className="master-data-page__toolbar">
            <Form
              form={businessSystemSearchForm}
              layout="inline"
              className="master-data-page__filters"
              onFinish={(values) =>
                setBusinessSystemQuery({
                  pageNo: 1,
                  pageSize: businessSystemQuery.pageSize,
                  unitId: values.unitId,
                  systemName: cleanString(values.systemName),
                  status: values.status,
                })
              }
            >
              <Form.Item name="unitId" label="所属单位">
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  placeholder="全部单位"
                  options={unitSelectOptions.map((option) => ({ value: option.id, label: option.label }))}
                  style={{ width: 210 }}
                />
              </Form.Item>
              <Form.Item name="systemName" label="系统名称">
                <Input allowClear placeholder="请输入系统名称" style={{ width: 240 }} />
              </Form.Item>
              <Form.Item name="status" label="状态">
                <Select
                  allowClear
                  placeholder="全部"
                  options={[
                    { label: '启用', value: 1 },
                    { label: '停用', value: 0 },
                  ]}
                  style={{ width: 120 }}
                />
              </Form.Item>
              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                    查询
                  </Button>
                  <Button
                    icon={<ReloadOutlined />}
                    onClick={() => {
                      businessSystemSearchForm.resetFields();
                      setBusinessSystemQuery({ pageNo: 1, pageSize: businessSystemQuery.pageSize });
                    }}
                  >
                    重置
                  </Button>
                </Space>
              </Form.Item>
            </Form>
            <div className="master-data-page__actions">
              <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate('business-system')}>
                新增业务系统
              </Button>
            </div>
          </div>
          <Table<BusinessSystemRecord>
            rowKey={(record) => String(record.id)}
            loading={businessSystemLoading}
            columns={businessSystemColumns}
            dataSource={businessSystemPage.records}
            scroll={{ x: 1260 }}
            pagination={{
              current: businessSystemPage.pagination.pageNo,
              pageSize: businessSystemPage.pagination.pageSize,
              total: businessSystemPage.pagination.total,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50],
              showTotal: (total) => `共 ${total} 条`,
              onChange: handleBusinessSystemPageChange,
            }}
          />
        </Card>
      ),
    },
  ];

  const content = (
    <>
      {contextHolder}
      <Typography.Paragraph className="master-data-page__intro">
        维护数据源归属单位和业务系统。只需填写名称，系统会自动生成内部标识；停用后不会出现在数据源配置的可选项中。
      </Typography.Paragraph>
      <Tabs
        activeKey={activeTab}
        items={tabs}
        onChange={(key) => setActiveTab(key as TabKey)}
        destroyInactiveTabPane={false}
      />
      <Modal
        className="master-data-editor-modal"
        open={editorOpen}
        title={
          editorKind === 'unit'
            ? editingUnit
              ? '编辑单位'
              : '新增单位'
            : editingBusinessSystem
              ? '编辑业务系统'
              : '新增业务系统'
        }
        width={560}
        destroyOnClose
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        onCancel={closeEditor}
        onOk={() => editorForm.submit()}
      >
        <Form form={editorForm} layout="vertical" onFinish={handleEditorSubmit}>
          {editorKind === 'unit' ? (
            <>
              <Form.Item
                label="单位名称"
                name="unitName"
                rules={[{ required: true, whitespace: true, message: '请输入单位名称' }]}
              >
                <Input maxLength={256} placeholder="请输入单位名称" />
              </Form.Item>
            </>
          ) : (
            <>
              <Form.Item
                label="所属单位"
                name="unitId"
                rules={[{ required: true, message: '请选择所属单位' }]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="请选择所属单位"
                  options={unitSelectOptions.map((option: MasterDataOption) => ({
                    value: option.id,
                    label: option.label,
                  }))}
                />
              </Form.Item>
              <Form.Item
                label="系统名称"
                name="systemName"
                rules={[{ required: true, whitespace: true, message: '请输入系统名称' }]}
              >
                <Input maxLength={256} placeholder="请输入业务系统名称" />
              </Form.Item>
            </>
          )}
          <Form.Item label="状态" name="status" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
          <Form.Item label="备注" name="remark">
            <Input.TextArea rows={3} maxLength={512} showCount placeholder="请输入备注（可选）" />
          </Form.Item>
          </Form>
        </Modal>
    </>
  );

  return embedded ? (
    <div className="master-data-page master-data-page--embedded">{content}</div>
  ) : (
    <PageContainer className="master-data-page" title="单位与业务系统维护">
      {content}
    </PageContainer>
  );
};

export default MasterDataPage;
