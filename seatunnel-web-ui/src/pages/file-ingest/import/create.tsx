import { ArrowRightOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import { App, Button, Collapse, Form, Input, Radio, Select, Space, Spin, Upload } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { DATA_SOURCE_REGISTRY } from '@/pages/data-source/dataSourceRegistry';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { uploadFileResources } from '@/pages/file-resources/service';
import { generateDataSourceOptions, type DataSourceType } from '@/pages/batch-link-up/DataSourceSelect';
import IconRightArrow from '@/pages/batch-link-up/IconRightArrow';
import BottomActionBar from '@/pages/batch-link-up/detail/components/BottomActionBar';
import PageHeader from '@/pages/batch-link-up/detail/components/PageHeader';
import CommonClientLinkSection, { type ConnectivityStatus } from '@/pages/common/components/CommonClientLinkSection';
import FileResourcePicker from '../FileResourcePicker';
import { FILE_RESOURCE_SOURCE, type FileResource } from '../types';
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
  const [targetType, setTargetType] = useState<any>(() => toTargetType(targetOption));
  const [clientId, setClientId] = useState<string>();
  const [targetDataSourceId, setTargetDataSourceId] = useState<string>();
  const [sourceTestStatus, setSourceTestStatus] = useState<ConnectivityStatus>('idle');
  const [targetTestStatus, setTargetTestStatus] = useState<ConnectivityStatus>('idle');
  const [file, setFile] = useState<FileResource | null>(null);
  const [uploading, setUploading] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setTargetType(toTargetType(targetOption));
  }, [targetOption]);

  useEffect(() => {
    form.setFieldsValue({ encoding: 'UTF-8', stopOnError: true, ignoreErrors: true });
  }, [form]);

  const handleSourceChange = (value: string, option: any) => {
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
  const stepProgress = activeStep === 'base' ? '0%' : activeStep === 'client' ? '50%' : '100%';

  return (
    <div className="offline-import-create">
      <PageHeader onBack={goPrevious} title="离线文件导入" className="offline-import-create__page-header" />

      <div className="offline-import-create__content">
        <div className="offline-import-create__steps">
          {(['base', 'client', 'config'] as CreateStep[]).map((step, index) => {
            const isActive = activeStep === step;
            const isDone = (activeStep === 'client' && step === 'base')
              || (activeStep === 'config' && (step === 'base' || step === 'client'));
            return (
              <div className="offline-import-create__step-group" key={step}>
                {index > 0 && <div className="offline-import-create__step-line" />}
                <button
                  type="button"
                  className={`offline-import-create__step${isActive ? ' is-active' : ''}${isDone ? ' is-done' : ''}`}
                  onClick={() => {
                    if (step === 'base' || isDone) setActiveStep(step);
                  }}
                  disabled={!isActive && !isDone && step !== 'base'}
                >
                  <span className="offline-import-create__step-number">{index + 1}</span>
                  {step === 'base' ? '基础配置' : step === 'client' ? '客户端与连接' : '具体配置'}
                </button>
              </div>
            );
          })}
        </div>

        {activeStep === 'base' && (
          <Form form={form} layout="vertical" initialValues={{ name: '', description: '' }}>
            <section className="offline-import-create__panel offline-import-create__panel--base">
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

              <h2 className="offline-import-create__panel-title offline-import-create__section-heading">任务信息</h2>
              <Form.Item label="任务名称" name="name" rules={[{ required: true, message: '请输入任务名称' }]} className="offline-import-create__field">
                <Input placeholder={category === 'DMP' ? '例如：生产库 DMP 备份导入' : '例如：建库脚本导入'} maxLength={64} />
              </Form.Item>
              <Form.Item label="任务描述" name="description" className="offline-import-create__field offline-import-create__field--last">
                <Input.TextArea placeholder="描述导入范围、用途、注意事项等" rows={3} maxLength={256} />
              </Form.Item>
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
              targetLabel="目标表"
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
              targetTitle="目标表"
              sourceCreateText="新建来源数据源"
              targetCreateText="新建目标数据源"
            />
          </div>
        )}

        {activeStep === 'config' && (
          <div className="offline-import-create__config">
            <section className="offline-import-create__panel offline-import-create__panel--pipeline">
              <div className="offline-import-create__panel-title">导入链路</div>
              <div className="offline-import-create__pipeline">
                <div className="offline-import-create__pipeline-node">
                  <FileResourceGlyph />
                  <div><strong>湖文件资源</strong><span>{file?.name || `待选择 ${accept} 文件`}</span></div>
                </div>
                <ArrowRightOutlined className="offline-import-create__pipeline-arrow" />
                <div className="offline-import-create__pipeline-node">
                  <DatabaseIcons dbType={targetDbType} width="28px" height="28px" />
                  <div><strong>{targetLabel}</strong><span>目标数据源 ID：{targetDataSourceId || '-'}</span></div>
                </div>
              </div>
            </section>

            <Form form={form} layout="vertical" initialValues={{ encoding: 'UTF-8', stopOnError: true, ignoreErrors: true }}>
              <section className="offline-import-create__panel offline-import-create__panel--file">
                <h2 className="offline-import-create__panel-title">导入文件与目标</h2>
                <div className="offline-import-create__target-fields">
                  <Form.Item label="导入类型" className="offline-import-create__field">
                    <Input value={OFFLINE_IMPORT_TYPE_LABELS[importType]} readOnly />
                  </Form.Item>
                  <Form.Item label="目标 Schema" name="targetSchema" className="offline-import-create__field offline-import-create__field--last">
                    <Input placeholder={targetDbType === 'KINGBASE' ? '例如 public' : '例如 TEST'} maxLength={128} />
                  </Form.Item>
                </div>
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
              </section>

              <section className="offline-import-create__panel offline-import-create__panel--advanced">
                <Collapse items={advancedPanel} ghost />
              </section>
            </Form>
          </div>
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
