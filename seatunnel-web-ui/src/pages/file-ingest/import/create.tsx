import { InboxOutlined, UploadOutlined } from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import { App, Button, Collapse, Form, Input, Radio, Select, Space, Spin, Upload } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  DATA_SOURCE_REGISTRY,
  supportsDatabaseScope,
  supportsSchemaScope,
} from '@/pages/data-source/dataSourceRegistry';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { dataSourceCatalogApi } from '@/pages/data-source/service';
import { uploadFileResources } from '@/pages/file-resources/service';
import { generateDataSourceOptions, type DataSourceType } from '@/pages/batch-link-up/DataSourceSelect';
import IconRightArrow from '@/pages/batch-link-up/IconRightArrow';
import BottomActionBar from '@/pages/batch-link-up/detail/components/BottomActionBar';
import PageHeader from '@/pages/batch-link-up/detail/components/PageHeader';
import { STEP_THEME } from '@/pages/batch-link-up/detail/constants';
import CommonClientLinkSection, { type ConnectivityStatus } from '@/pages/common/components/CommonClientLinkSection';
import FileResourcePicker from '../FileResourcePicker';
import { FILE_RESOURCE_SOURCE, type FileResource } from '../types';
import OfflineImportConfigCanvas, { type OfflineImportConfigNode } from './OfflineImportConfigCanvas';
import './create.less';
import {
  OFFLINE_IMPORT_TYPE_LABELS,
  offlineImportTargetDbType,
  offlineImportTaskApi,
  type OfflineImportCategory,
  type OfflineImportTypeCode,
} from './api';

const IMPORT_TYPE_OPTIONS: Record<OfflineImportCategory, OfflineImportTypeCode[]> = {
  DMP: ['KINGBASE_DMP', 'DAMENG_DMP'],
  SQL: ['KINGBASE_SQL', 'DAMENG_SQL'],
};

const ENCODING_OPTIONS = [
  { value: 'UTF-8', label: 'UTF-8' },
  { value: 'GBK', label: 'GBK' },
];

const UPLOAD_PATH = '/offline-import';

const fileResourceOption: DataSourceType = {
  ...FILE_RESOURCE_SOURCE,
  value: FILE_RESOURCE_SOURCE.dbType,
  rawLabel: '湖文件',
  label: <span>湖文件</span>,
};

const getTargetOption = (dbType: string): DataSourceType => {
  const configured = generateDataSourceOptions().find((option) => option.value.toUpperCase() === dbType.toUpperCase());
  if (configured) return configured;

  const registryItem = DATA_SOURCE_REGISTRY.find((item) => item.dbType.toUpperCase() === dbType.toUpperCase());
  return {
    value: dbType,
    rawLabel: registryItem?.label || dbType,
    connectorType: registryItem?.connectorType,
    pluginName: registryItem?.pluginName || registryItem?.connectorType,
    label: (
      <div className="flex items-center">
        <DatabaseIcons dbType={dbType} width="22px" height="22px" />
        <span className="ml-2">{registryItem?.label || dbType}</span>
      </div>
    ),
  };
};

const toTargetType = (option: DataSourceType) => ({
  dbType: option.value,
  connectorType: option.connectorType,
  pluginName: option.pluginName,
  rawLabel: option.rawLabel,
  label: option.label,
});

type CreateStep = 'base' | 'client' | 'config';

const CreateOfflineImportPage: React.FC = () => {
  const { message } = App.useApp();
  const location = useLocation();
  const [form] = Form.useForm();
  const searchParams = new URLSearchParams(location.search);
  const category: OfflineImportCategory = searchParams.get('type')?.toUpperCase() === 'SQL' ? 'SQL' : 'DMP';
  const availableTypes = IMPORT_TYPE_OPTIONS[category];
  const requestedImportType = searchParams.get('importType')?.toUpperCase();
  const importType = (availableTypes.includes(requestedImportType as OfflineImportTypeCode)
    ? requestedImportType
    : availableTypes[0]) as OfflineImportTypeCode;
  const accept = category === 'DMP' ? '.dmp' : '.sql';
  const targetDbType = offlineImportTargetDbType(importType);
  const targetOption = useMemo(() => getTargetOption(targetDbType), [targetDbType]);

  const [activeStep, setActiveStep] = useState<CreateStep>('base');
  const [configNode, setConfigNode] = useState<OfflineImportConfigNode>('source');
  const [targetType, setTargetType] = useState<any>(() => toTargetType(targetOption));
  const [clientId, setClientId] = useState<string>();
  const [targetDataSourceId, setTargetDataSourceId] = useState<string>();
  const [databaseOptions, setDatabaseOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [schemaOptions, setSchemaOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [databaseLoading, setDatabaseLoading] = useState(false);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [sourceTestStatus, setSourceTestStatus] = useState<ConnectivityStatus>('idle');
  const [targetTestStatus, setTargetTestStatus] = useState<ConnectivityStatus>('idle');
  const [file, setFile] = useState<FileResource | null>(null);
  const [uploading, setUploading] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const targetDatabase = Form.useWatch('targetDatabase', { form, preserve: true });

  useEffect(() => {
    setTargetType(toTargetType(targetOption));
  }, [targetOption]);

  useEffect(() => {
    form.setFieldsValue({ encoding: 'UTF-8', stopOnError: true, ignoreErrors: true });
  }, [form]);

  useEffect(() => {
    // 更换目标连接后，清空旧连接下的目录选择，避免数据库与 Schema 错配。
    form.setFieldsValue({ targetDatabase: undefined, targetSchema: undefined });
    setDatabaseOptions([]);
    setSchemaOptions([]);
  }, [form, targetDataSourceId]);

  useEffect(() => {
    if (category !== 'SQL' || !targetDataSourceId || !supportsDatabaseScope(targetDbType)) {
      setDatabaseOptions([]);
      setSchemaOptions([]);
      return;
    }

    let active = true;
    const loadDatabases = async () => {
      setDatabaseLoading(true);
      try {
        const response = await dataSourceCatalogApi.listDatabases(String(targetDataSourceId));
        if (response?.code !== 0) throw new Error(response?.message || '获取目标数据库列表失败');
        const options = (Array.isArray(response.data) ? response.data : []).map((item: any) => ({
          value: String(item?.value ?? item?.label ?? ''),
          label: String(item?.label ?? item?.value ?? ''),
        })).filter((item) => item.value);
        if (active) setDatabaseOptions(options);
      } catch (error: any) {
        if (active) {
          setDatabaseOptions([]);
          message.error(error?.message || '获取目标数据库列表失败');
        }
      } finally {
        if (active) setDatabaseLoading(false);
      }
    };
    void loadDatabases();
    return () => { active = false; };
  }, [category, message, targetDataSourceId, targetDbType]);

  useEffect(() => {
    if (category !== 'SQL' || !targetDataSourceId || !targetDatabase || !supportsSchemaScope(targetDbType)) {
      setSchemaOptions([]);
      return;
    }

    let active = true;
    const loadSchemas = async () => {
      setSchemaLoading(true);
      try {
        const response = await dataSourceCatalogApi.listSchemas(
          String(targetDataSourceId),
          String(targetDatabase),
        );
        if (response?.code !== 0) throw new Error(response?.message || '获取目标 Schema 列表失败');
        const options = (Array.isArray(response.data) ? response.data : []).map((item: any) => ({
          value: String(item?.value ?? item?.label ?? ''),
          label: String(item?.label ?? item?.value ?? ''),
        })).filter((item) => item.value);
        if (active) setSchemaOptions(options);
      } catch (error: any) {
        if (active) {
          setSchemaOptions([]);
          message.error(error?.message || '获取目标 Schema 列表失败');
        }
      } finally {
        if (active) setSchemaLoading(false);
      }
    };
    void loadSchemas();
    return () => { active = false; };
  }, [category, message, targetDataSourceId, targetDatabase, targetDbType]);

  const handleSourceChange = (value: string, _option: any) => {
    if (value !== FILE_RESOURCE_SOURCE.dbType) return;
    setSourceTestStatus('idle');
  };

  const handleTargetChange = (value: string, option: any) => {
    setTargetType({
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
      rawLabel: option?.rawLabel,
      label: option?.label,
    });
    setTargetDataSourceId(undefined);
    setTargetTestStatus('idle');
  };

  const cancelToList = () => history.push('/sync/file-ingest?tab=offline-import');

  const goPrevious = () => {
    if (activeStep === 'config') {
      setActiveStep('client');
    } else if (activeStep === 'client') {
      setActiveStep('base');
    } else {
      history.push('/sync/file-ingest/select');
    }
  };

  const goClientStep = async () => {
    try {
      await form.validateFields(['name']);
      setActiveStep('client');
    } catch {
      // Keep the user on the basic information step until the task name is valid.
    }
  };

  const canEnterConfig = Boolean(clientId && targetDataSourceId && targetTestStatus === 'success');

  const goConfigStep = () => {
    if (!clientId || !targetDataSourceId) {
      message.warning('请选择客户端和目标数据源');
      return;
    }
    if (targetTestStatus !== 'success') {
      message.warning('请先完成目标数据源连通性测试，并确保通过');
      return;
    }
    setActiveStep('config');
  };

  const uploadRequest = async (options: any) => {
    const { file: rawFile, onSuccess, onError } = options;
    setUploading(true);
    try {
      const response = await uploadFileResources(UPLOAD_PATH, [
        { file: rawFile as File, relativePath: (rawFile as File).name },
      ]);
      const uploaded = response?.resources?.[0];
      if (!uploaded) throw new Error('上传失败');
      setFile({
        id: String(uploaded.id),
        name: String(uploaded.name || (rawFile as File).name),
        size: Number(uploaded.size ?? (rawFile as File).size ?? 0),
        kind: 'FILE',
      });
      onSuccess?.(uploaded);
    } catch (error: any) {
      message.error(error?.message || '文件上传失败');
      onError?.(error);
    } finally {
      setUploading(false);
    }
  };

  const beforeUpload = (rawFile: File) => {
    if (!rawFile.name.toLowerCase().endsWith(accept)) {
      message.warning(`仅支持 ${accept} 文件`);
      return Upload.LIST_IGNORE;
    }
    return true;
  };

  const submit = async () => {
    let values: any;
    try {
      await form.validateFields();
      values = form.getFieldsValue(true);
    } catch {
      return;
    }
    if (!targetDataSourceId || targetTestStatus !== 'success') {
      message.warning('请先完成目标数据源连通性测试');
      setActiveStep('client');
      return;
    }
    if (category === 'SQL' && !values.targetDatabase) {
      setConfigNode('sink');
      message.warning('请选择目标数据库');
      return;
    }
    if (!file) {
      message.warning('请先上传或选择导入文件');
      return;
    }
    if (!String(file.name || '').toLowerCase().endsWith(accept)) {
      message.warning(`仅支持 ${accept} 文件`);
      return;
    }

    setSubmitting(true);
    try {
      const response = await offlineImportTaskApi.create({
        name: values.name,
        description: values.description,
        importType,
        fileResourceId: file.id,
        targetDatasourceId: targetDataSourceId,
        targetDatabase: category === 'SQL' ? values.targetDatabase : undefined,
        targetSchema: values.targetSchema || undefined,
        options: category === 'SQL'
          ? {
              sourceSchema: values.sourceSchema?.trim() || undefined,
              stopOnError: values.stopOnError,
              encoding: values.encoding,
            }
          : {
              sourceSchema: values.sourceSchema?.trim() || undefined,
              ...(importType === 'DAMENG_DMP' ? { ignoreErrors: values.ignoreErrors } : {}),
            },
      }, { skipErrorHandler: true });
      if (response?.code !== 0) throw new Error(response?.message || response?.msg || '创建任务失败');
      message.success('任务创建成功，可在列表中执行导入');
      history.push('/sync/file-ingest?tab=offline-import');
    } catch (error: any) {
      message.error(error?.message || '创建任务失败');
    } finally {
      setSubmitting(false);
    }
  };

  const advancedPanel = useMemo(() => [{
    key: 'advanced',
    label: '高级选项',
    children: category === 'SQL' ? (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Form.Item
          name="sourceSchema"
          label="SQL 源 Schema"
          style={{ marginBottom: 0, maxWidth: 420 }}
          tooltip="跨模式还原需填写导出文件中的源 Schema；与目标 Schema 不同时映射显式限定的对象。留空仅适用于无模式限定或源、目标同名的脚本"
        >
          <Input placeholder="填写脚本中显式限定的源 Schema；留空不映射" style={{ maxWidth: 320 }} />
        </Form.Item>
        <Form.Item name="encoding" label="文件编码" style={{ marginBottom: 0, maxWidth: 320 }} tooltip="SQL 文件字符编码，达梦/金仓在 Windows 环境导出的脚本常为 GBK">
          <Select options={ENCODING_OPTIONS} style={{ width: 160 }} />
        </Form.Item>
        <Form.Item name="stopOnError" label="出错时停止" style={{ marginBottom: 0 }} tooltip="开启后遇到第一条失败语句即中止；关闭则跳过失败语句继续执行">
          <Radio.Group
            options={[{ value: true, label: '停止（默认）' }, { value: false, label: '继续执行' }]}
            optionType="button"
            buttonStyle="solid"
          />
        </Form.Item>
      </Space>
    ) : (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Form.Item name="sourceSchema" label="DMP 内源 Schema" style={{ marginBottom: 0, maxWidth: 420 }} tooltip="备份文件内的源 Schema 名；配合目标 Schema 执行映射，留空按目标 Schema 处理">
          <Input placeholder="留空则与目标 Schema 相同" style={{ maxWidth: 320 }} />
        </Form.Item>
        {importType === 'DAMENG_DMP' && (
          <Form.Item name="ignoreErrors" label="忽略对象已存在等错误" style={{ marginBottom: 0 }} tooltip="对应达梦 dimp 的 IGNORE=Y；重复导入场景建议开启">
            <Radio.Group
              options={[{ value: true, label: '忽略（默认）' }, { value: false, label: '不忽略' }]}
              optionType="button"
              buttonStyle="solid"
            />
          </Form.Item>
        )}
      </Space>
    ),
  }], [category, importType]);

  const targetLabel = targetOption.rawLabel || targetDbType;
  const isBaseStep = activeStep === 'base';
  const isClientStep = activeStep === 'client';
  const isConfigStep = activeStep === 'config';
  const basePillClass = isBaseStep ? STEP_THEME.base.pill : STEP_THEME.base.pillDone;
  const baseDotClass = isBaseStep ? STEP_THEME.base.dot : STEP_THEME.base.dotDone;
  const clientPillClass = isClientStep || isConfigStep ? STEP_THEME.client.pill : STEP_THEME.client.pillInactive;
  const clientDotClass = isClientStep || isConfigStep ? STEP_THEME.client.dot : STEP_THEME.client.dotInactive;
  const configPillClass = isConfigStep ? STEP_THEME.client.pill : STEP_THEME.client.pillInactive;
  const configDotClass = isConfigStep ? STEP_THEME.client.dot : STEP_THEME.client.dotInactive;
  const stepProgress = isBaseStep ? '0%' : isClientStep ? '50%' : '100%';

  return (
    <div className="offline-import-create">
      <PageHeader onBack={goPrevious} title="离线文件导入" className="offline-import-create__page-header" />

      <div className="offline-import-create__content">
        <div className="offline-import-create__steps sticky top-0 z-20 mb-6 pt-1 backdrop-blur">
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setActiveStep('base')}
              className={`flex items-center gap-2 rounded-[4px] px-3 py-1.5 text-sm transition-all duration-200 ${basePillClass}`}
            >
              <span className={`flex h-5 w-5 items-center justify-center rounded-full text-[14px] font-semibold transition-all duration-200 ${baseDotClass}`}>1</span>
              基础配置
            </button>
            <div className="offline-import-create__step-track relative h-[2px] flex-1 overflow-hidden rounded-full">
              <div className="offline-import-create__step-progress absolute left-0 top-0 h-full rounded-full transition-[width] duration-500" style={{ width: stepProgress }} />
            </div>
            <button
              type="button"
              onClick={() => void goClientStep()}
              className={`flex items-center gap-2 rounded-full px-3 py-1.5 text-sm transition-all duration-200 ${clientPillClass}`}
            >
              <span className={`flex h-5 w-5 items-center justify-center rounded-full text-[14px] font-semibold transition-all duration-200 ${clientDotClass}`}>2</span>
              客户端与连接
            </button>
            <div className="offline-import-create__step-track relative h-[2px] flex-1 overflow-hidden rounded-full">
              <div className="offline-import-create__step-progress absolute left-0 top-0 h-full rounded-full transition-[width] duration-500" style={{ width: isConfigStep ? '100%' : '0%' }} />
            </div>
            <button
              type="button"
              onClick={goConfigStep}
              className={`flex items-center gap-2 rounded-full px-3 py-1.5 text-sm transition-all duration-200 ${configPillClass}`}
              disabled={!isConfigStep && !canEnterConfig}
            >
              <span className={`flex h-5 w-5 items-center justify-center rounded-full text-[14px] font-semibold transition-all duration-200 ${configDotClass}`}>3</span>
              具体配置
            </button>
          </div>
        </div>

        {activeStep === 'base' && (
          <Form form={form} layout="vertical" initialValues={{ name: '', description: '' }}>
            <section className="offline-import-create__panel offline-import-create__panel--base">
              {/* 基础信息沿用批量引接页的外层卡片、内层卡片和纵向分区结构。 */}
              <div className="offline-import-create__base-content">
                <div className="offline-import-create__base-card">
                  <div className="offline-import-create__sync-method">
                    <div className="offline-import-create__panel-title">数据同步方式</div>
                    <div className="offline-import-create__route">
                      <div className="offline-import-create__route-node">
                        <span>来源</span>
                        <FileResourceGlyph />
                        <strong>湖文件</strong>
                      </div>
                      <IconRightArrow />
                      <div className="offline-import-create__route-node">
                        <span>去向</span>
                        <DatabaseIcons dbType={targetDbType} width="22px" height="22px" />
                        <strong>{targetLabel}</strong>
                      </div>
                    </div>
                    <div className="offline-import-create__import-type">
                      <span>导入类型</span>
                      <strong>{OFFLINE_IMPORT_TYPE_LABELS[importType]}</strong>
                      <span>从统一选择页选择</span>
                    </div>
                  </div>

                  <div className="offline-import-create__task-info">
                    <div className="offline-import-create__panel-title">任务信息</div>
                    <Form.Item label="任务名称" name="name" rules={[{ required: true, message: '请输入任务名称' }]} className="offline-import-create__field">
                      <Input placeholder={category === 'DMP' ? '例如：生产库 DMP 备份导入' : '例如：建库脚本导入'} maxLength={64} />
                    </Form.Item>
                    <Form.Item label="任务描述" name="description" className="offline-import-create__field offline-import-create__field--last">
                      <Input.TextArea placeholder="描述导入范围、用途、注意事项等" rows={3} maxLength={256} />
                    </Form.Item>
                  </div>
                </div>
              </div>
            </section>
          </Form>
        )}

        {activeStep === 'client' && (
          <div className="offline-import-create__client-panel">
            <CommonClientLinkSection
              activeStep="client"
              sourceType={fileResourceOption}
              targetType={targetType}
              sourceLabel="数据来源"
              targetLabel="目标数据库"
              clientId={clientId}
              setClientId={setClientId}
              handleSourceChange={handleSourceChange}
              handleTargetChange={handleTargetChange}
              sourceDataSourceId={undefined}
              targetDataSourceId={targetDataSourceId}
              setSourceDataSourceId={() => undefined}
              setTargetDataSourceId={setTargetDataSourceId}
              sourceTestStatus={sourceTestStatus}
              targetTestStatus={targetTestStatus}
              setSourceTestStatus={setSourceTestStatus}
              setTargetTestStatus={setTargetTestStatus}
              sourceDataSourceTypeOptions={[{ value: fileResourceOption.value, label: '湖文件' }]}
              targetDataSourceTypeOptions={[{ value: targetOption.value, label: targetOption.rawLabel || targetOption.value }]}
              sourceManaged
              scene="offline"
              sourceTitle="来源"
              targetTitle="目标数据库"
              sourceCreateText="新建来源数据源"
              targetCreateText="新建目标数据源"
            />
          </div>
        )}

        {activeStep === 'config' && (
          <Form form={form} layout="vertical" initialValues={{ encoding: 'UTF-8', stopOnError: true, ignoreErrors: true }}>
            <OfflineImportConfigCanvas
              activeNode={configNode}
              onActiveNodeChange={(node) => {
                if (node) setConfigNode(node);
              }}
              sourceTitle="湖文件资源"
              sourceSummary={file?.name || `待选择 ${accept} 文件`}
              sinkTitle={targetLabel}
              sinkSummary={targetDataSourceId ? `已选择目标数据源 ${targetDataSourceId}` : '尚未选择目标数据源'}
              sinkDbType={targetDbType}
              sourceConfig={(
                <>
                  <Form.Item label="导入类型" className="offline-import-create__field">
                    <Input value={OFFLINE_IMPORT_TYPE_LABELS[importType]} readOnly />
                  </Form.Item>
                  <Upload.Dragger
                    accept={accept}
                    maxCount={1}
                    customRequest={uploadRequest}
                    beforeUpload={beforeUpload}
                    showUploadList={false}
                    disabled={uploading}
                    className="offline-import-create__upload"
                  >
                    {uploading ? <Spin /> : <p className="ant-upload-drag-icon !mb-2"><InboxOutlined /></p>}
                    <p className="ant-upload-text">{file ? file.name : `点击或拖拽上传 ${accept} 文件`}</p>
                    <p className="ant-upload-hint">{file ? `文件大小：${(Number(file.size || 0) / 1024).toFixed(1)} KB，可重新上传覆盖` : '文件将上传到平台湖文件资源池后执行导入'}</p>
                  </Upload.Dragger>
                  <div className="offline-import-create__file-picker">
                    <span>已有文件在湖文件资源池？直接选择</span>
                    <Button icon={<UploadOutlined />} disabled={uploading} onClick={() => setPickerOpen(true)}>从湖文件选择</Button>
                  </div>
                  <Collapse items={advancedPanel} ghost />
                </>
              )}
              sinkConfig={(
                <>
                  <div className="offline-import-create__selected-target">
                    <DatabaseIcons dbType={targetDbType} width="24px" height="24px" />
                    <div>
                      <strong>{targetLabel}</strong>
                      <span>数据源 ID：{targetDataSourceId || '-'}</span>
                    </div>
                  </div>
                  <Button className="offline-import-create__change-target" onClick={() => setActiveStep('client')}>
                    更改目标数据源
                  </Button>
                  {category === 'SQL' ? (
                    <>
                      <Form.Item
                        label="数据库"
                        name="targetDatabase"
                        rules={[{ required: true, message: '请选择目标数据库' }]}
                        className="offline-import-create__field"
                      >
                        <Select
                          showSearch
                          optionFilterProp="label"
                          placeholder="请选择数据库"
                          options={databaseOptions}
                          loading={databaseLoading}
                          disabled={!targetDataSourceId}
                          onChange={() => form.setFieldValue('targetSchema', undefined)}
                        />
                      </Form.Item>
                      <Form.Item label="Schema" name="targetSchema" className="offline-import-create__field offline-import-create__field--last">
                        <Select
                          showSearch
                          allowClear
                          optionFilterProp="label"
                          placeholder="请选择 Schema（可选）"
                          options={schemaOptions}
                          loading={schemaLoading}
                          disabled={!targetDatabase}
                        />
                      </Form.Item>
                      <div className="offline-import-create__target-hint">先选择数据源连接，再选择目标数据库和 Schema；不需要选择表。</div>
                    </>
                  ) : (
                    <>
                      <Form.Item label="目标 Schema" name="targetSchema" className="offline-import-create__field offline-import-create__field--last">
                        <Input placeholder={targetDbType === 'KINGBASE' ? '例如 public' : '例如 TEST'} maxLength={128} />
                      </Form.Item>
                      <div className="offline-import-create__target-hint">目标 Schema 为可选项；留空时按目标数据源默认模式执行。</div>
                    </>
                  )}
                </>
              )}
            />
          </Form>
        )}
      </div>

      <BottomActionBar
        onCancel={cancelToList}
        onPrev={activeStep === 'base' ? undefined : goPrevious}
        onNext={() => {
          if (activeStep === 'base') void goClientStep();
          else if (activeStep === 'client') goConfigStep();
          else void submit();
        }}
        nextText={activeStep === 'base' ? '下一步：客户端与连接' : activeStep === 'client' ? '下一步：具体配置' : '创建导入任务'}
        hintText={activeStep === 'base'
          ? '填写基础信息后，选择客户端并测试目标数据源连接'
          : activeStep === 'client'
            ? '任务会在所选客户端节点上进行目标数据源连通性校验'
            : '确认导入文件与目标配置，创建后可在任务列表中执行导入'}
        nextDisabled={activeStep === 'client' ? !canEnterConfig : activeStep === 'config' && (submitting || uploading)}
        className="offline-import-create__action-bar"
      />

      <FileResourcePicker
        open={pickerOpen}
        selectionMode="file"
        title={`选择 ${accept} 文件`}
        onCancel={() => setPickerOpen(false)}
        onSelect={(resource) => {
          if (!String(resource.name || '').toLowerCase().endsWith(accept)) {
            message.warning(`仅支持 ${accept} 文件`);
            return;
          }
          setFile(resource);
          setPickerOpen(false);
        }}
      />
    </div>
  );
};

const FileResourceGlyph: React.FC = () => (
  <span className="offline-import-create__file-glyph" aria-hidden="true">▤</span>
);

export default CreateOfflineImportPage;
