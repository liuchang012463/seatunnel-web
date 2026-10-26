import { DatabaseOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import {
  App,
  Button,
  Collapse,
  Form,
  Input,
  Radio,
  Select,
  Space,
  Spin,
  Upload,
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { fetchDataSourceOptions } from '@/pages/data-source/service';
import { uploadFileResources } from '@/pages/file-resources/service';
import BottomActionBar from '@/pages/batch-link-up/detail/components/BottomActionBar';
import PageHeader from '@/pages/batch-link-up/detail/components/PageHeader';
import FileResourcePicker from '../FileResourcePicker';
import type { FileResource } from '../types';
import {
  OFFLINE_IMPORT_TYPE_LABELS,
  offlineImportTargetDbType,
  offlineImportTaskApi,
  type OfflineImportCategory,
  type OfflineImportTypeCode,
} from './api';

interface ImportTypeOption {
  value: OfflineImportTypeCode;
  label: string;
}

const IMPORT_TYPE_OPTIONS: Record<OfflineImportCategory, ImportTypeOption[]> = {
  DMP: [
    { value: 'KINGBASE_DMP', label: OFFLINE_IMPORT_TYPE_LABELS.KINGBASE_DMP },
    { value: 'DAMENG_DMP', label: OFFLINE_IMPORT_TYPE_LABELS.DAMENG_DMP },
  ],
  SQL: [
    { value: 'KINGBASE_SQL', label: OFFLINE_IMPORT_TYPE_LABELS.KINGBASE_SQL },
    { value: 'DAMENG_SQL', label: OFFLINE_IMPORT_TYPE_LABELS.DAMENG_SQL },
  ],
};

const ENCODING_OPTIONS = [
  { value: 'UTF-8', label: 'UTF-8' },
  { value: 'GBK', label: 'GBK' },
];

const UPLOAD_PATH = '/offline-import';

const CreateOfflineImportPage: React.FC = () => {
  const { message } = App.useApp();
  const location = useLocation();
  const [form] = Form.useForm();
  const category = (new URLSearchParams(location.search).get('type')?.toUpperCase() === 'SQL'
    ? 'SQL'
    : 'DMP') as OfflineImportCategory;
  const typeOptions = IMPORT_TYPE_OPTIONS[category];
  const [selectedImportType, setSelectedImportType] = useState<OfflineImportTypeCode>(typeOptions[0].value);
  const importType = typeOptions.some((option) => option.value === selectedImportType)
    ? selectedImportType
    : typeOptions[0].value;
  const [file, setFile] = useState<FileResource | null>(null);
  const [uploading, setUploading] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [dataSourceOptionsState, setDataSourceOptionsState] = useState<{
    dbType: string;
    options: Array<{ value: string; label: string }>;
  }>({ dbType: offlineImportTargetDbType(typeOptions[0].value), options: [] });
  const [submitting, setSubmitting] = useState(false);

  const accept = category === 'DMP' ? '.dmp' : '.sql';
  const targetDbType = offlineImportTargetDbType(importType);
  const dataSourceOptions = dataSourceOptionsState.dbType === targetDbType ? dataSourceOptionsState.options : [];
  const previousCategory = useRef(category);

  useEffect(() => {
    if (previousCategory.current === category) return;
    previousCategory.current = category;
    setSelectedImportType(typeOptions[0].value);
    setFile(null);
    form.resetFields(['targetDatasourceId', 'targetSchema', 'sourceSchema', 'encoding', 'stopOnError', 'ignoreErrors']);
  }, [category, form, typeOptions]);

  useEffect(() => {
    let cancelled = false;
    setDataSourceOptionsState({ dbType: targetDbType, options: [] });
    fetchDataSourceOptions(targetDbType)
      .then((response) => {
        if (cancelled) return;
        const records = (response?.data || []) as Array<{ id?: string | number; value?: string | number; name?: string; label?: string }>;
        setDataSourceOptionsState({
          dbType: targetDbType,
          options: records.map((record) => ({
            value: String(record.id ?? record.value ?? ''),
            label: String(record.name || record.label || record.value || record.id || ''),
          })),
        });
      })
      .catch(() => {
        if (!cancelled) setDataSourceOptionsState({ dbType: targetDbType, options: [] });
      });
    return () => {
      cancelled = true;
    };
  }, [targetDbType]);

  const uploadRequest = async (options: any) => {
    const { file: rawFile, onSuccess, onError } = options;
    setUploading(true);
    try {
      const response = await uploadFileResources(UPLOAD_PATH, [
        { file: rawFile as File, relativePath: (rawFile as File).name },
      ]);
      const uploaded = response?.resources?.[0];
      if (!uploaded) {
        throw new Error('上传失败');
      }
      setFile({
        id: String(uploaded.id),
        name: String(uploaded.name || (rawFile as File).name),
        size: Number(uploaded.size ?? (rawFile as File).size ?? 0),
        kind: 'FILE',
      } as FileResource);
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
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (!file) {
      message.warning('请先上传或选择导入文件');
      return;
    }
    setSubmitting(true);
    try {
      const response = await offlineImportTaskApi.create({
        name: values.name,
        description: values.description,
        importType,
        fileResourceId: file.id,
        targetDatasourceId: values.targetDatasourceId,
        targetSchema: values.targetSchema || undefined,
        options:
          category === 'SQL'
            ? {
                sourceSchema: values.sourceSchema?.trim() || undefined,
                stopOnError: values.stopOnError,
                encoding: values.encoding,
              }
            : {
                sourceSchema: values.sourceSchema?.trim() || undefined,
                ...(importType === 'DAMENG_DMP' ? { ignoreErrors: values.ignoreErrors } : {}),
              },
      });
      if (response?.code !== 0) {
        throw new Error(response?.msg || '创建任务失败');
      }
      message.success('任务创建成功，可在列表中执行导入');
      history.push('/sync/file-ingest?tab=offline-import');
    } catch (error: any) {
      message.error(error?.message || '创建任务失败');
    } finally {
      setSubmitting(false);
    }
  };

  const advancedPanel = useMemo(
    () => [
      {
        key: 'advanced',
        label: '高级选项',
        children:
          category === 'SQL' ? (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Form.Item
                name="sourceSchema"
                label="SQL 源 Schema"
                style={{ marginBottom: 0, maxWidth: 420 }}
                tooltip="跨模式还原需填写导出文件中的源 Schema；与目标 Schema 不同时映射显式限定的对象。留空仅适用于无模式限定或源、目标同名的脚本"
              >
                <Input placeholder="填写脚本中显式限定的源 Schema；留空不映射" style={{ maxWidth: 320 }} />
              </Form.Item>
              <Form.Item
                name="encoding"
                label="文件编码"
                initialValue="UTF-8"
                style={{ marginBottom: 0, maxWidth: 320 }}
                tooltip="SQL 文件的字符编码，达梦/金仓在 Windows 环境导出的脚本常为 GBK"
              >
                <Select options={ENCODING_OPTIONS} style={{ width: 160 }} />
              </Form.Item>
              <Form.Item
                name="stopOnError"
                label="出错时停止"
                initialValue={true}
                style={{ marginBottom: 0 }}
                tooltip="开启后遇到第一条失败语句即中止；关闭则跳过失败语句继续执行"
              >
                <Radio.Group
                  options={[
                    { value: true, label: '停止（默认）' },
                    { value: false, label: '继续执行' },
                  ]}
                  optionType="button"
                  buttonStyle="solid"
                />
              </Form.Item>
            </Space>
          ) : (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Form.Item
                name="sourceSchema"
                label="DMP 内源 Schema"
                style={{ marginBottom: 0, maxWidth: 420 }}
                tooltip="备份文件内的源 Schema 名；配合目标 Schema 执行映射，留空按目标 Schema 处理"
              >
                <Input placeholder="留空则与目标 Schema 相同" style={{ maxWidth: 320 }} />
              </Form.Item>
              {importType === 'DAMENG_DMP' && (
                <Form.Item
                  name="ignoreErrors"
                  label="忽略对象已存在等错误"
                  initialValue={true}
                  style={{ marginBottom: 0 }}
                  tooltip="对应达梦 dimp 的 IGNORE=Y；重复导入场景建议开启"
                >
                  <Radio.Group
                    options={[
                      { value: true, label: '忽略（默认）' },
                      { value: false, label: '不忽略' },
                    ]}
                    optionType="button"
                    buttonStyle="solid"
                  />
                </Form.Item>
              )}
            </Space>
          ),
      },
    ],
    [category, importType],
  );

  return (
    <div className="min-h-screen bg-white">
      <PageHeader onBack={() => history.push('/sync/file-ingest/select')} title={`离线数据导入 · ${category === 'DMP' ? 'DMP' : 'SQL'} 数据导入`} />

      <div className="mx-auto max-w-[960px] px-6 pb-28 pt-6">
        <Form form={form} layout="vertical">
          <div className="rounded-[24px] border border-[#EAECF0] p-6 shadow-[0_1px_2px_rgba(16,24,40,0.04)]">
            <div className="mb-4 text-[14px] font-medium">导入类型</div>
            <Radio.Group
              value={importType}
              onChange={(event) => {
                const nextImportType = event.target.value as OfflineImportTypeCode;
                if (nextImportType === importType) return;
                setSelectedImportType(nextImportType);
                form.resetFields(['targetDatasourceId', 'targetSchema', 'sourceSchema', 'ignoreErrors']);
              }}
              optionType="button"
              buttonStyle="solid"
              options={typeOptions}
            />
            {category === 'SQL' && (
              <div className="mt-2 text-[12px] text-[color:var(--st-color-text-muted,#98a2b3)]">
                Oracle SQL 导入待支持
              </div>
            )}

            <div className="mt-6 space-y-5">
              <Form.Item
                label="任务名称"
                name="name"
                rules={[{ required: true, message: '请输入任务名称' }]}
                className="mb-0"
              >
                <Input
                  placeholder={category === 'DMP' ? '例如：生产库 DMP 备份导入' : '例如：建库脚本导入'}
                  className="!h-[36px] !rounded-[12px]"
                  maxLength={64}
                />
              </Form.Item>
              <Form.Item label="任务描述" name="description" className="mb-0">
                <Input.TextArea placeholder="描述导入范围、用途、注意事项等" rows={3} className="!rounded-xl" maxLength={256} />
              </Form.Item>
            </div>
          </div>

          <div className="mt-6 rounded-[24px] border border-[#EAECF0] p-6 shadow-[0_1px_2px_rgba(16,24,40,0.04)]">
            <div className="mb-4 text-[14px] font-medium">导入文件</div>
            <Upload.Dragger
              accept={accept}
              maxCount={1}
              customRequest={uploadRequest}
              beforeUpload={beforeUpload}
              showUploadList={false}
              disabled={uploading}
            >
              {uploading ? (
                <Spin />
              ) : (
                <p className="ant-upload-drag-icon !mb-2">
                  <InboxOutlined />
                </p>
              )}
              <p className="ant-upload-text">{file ? file.name : `点击或拖拽上传 ${accept} 文件`}</p>
              <p className="ant-upload-hint">
                {file
                  ? `文件大小：${(Number(file.size || 0) / 1024).toFixed(1)} KB，可重新上传覆盖`
                  : '文件将上传到平台湖文件资源池后执行导入'}
              </p>
            </Upload.Dragger>

            <div className="mt-3 flex items-center justify-between">
              <span className="text-[13px] text-[color:var(--st-color-text-secondary,#475467)]">
                已有文件在湖文件资源池？直接选择
              </span>
              <Button icon={<UploadOutlined />} onClick={() => setPickerOpen(true)}>
                从湖文件选择
              </Button>
            </div>
          </div>

          <div className="mt-6 rounded-[24px] border border-[#EAECF0] p-6 shadow-[0_1px 2px_rgba(16,24,40,0.04)]">
            <div className="mb-4 text-[14px] font-medium">导入目标</div>
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              <Form.Item
                label="目标数据源"
                name="targetDatasourceId"
                rules={[{ required: true, message: '请选择目标数据源' }]}
                className="mb-0"
                tooltip={`仅展示 ${targetDbType === 'KINGBASE' ? '人大金仓' : '达梦'} 类型数据源`}
              >
                <Select
                  placeholder="选择目标数据源"
                  options={dataSourceOptions}
                  notFoundContent="暂无可用数据源，请先在数据源管理中创建"
                  showSearch
                  optionFilterProp="label"
                />
              </Form.Item>
              <Form.Item label="目标 Schema" name="targetSchema" className="mb-0">
                <Input placeholder={targetDbType === 'KINGBASE' ? '例如 public' : '例如 TEST'} maxLength={128} />
              </Form.Item>
            </div>
          </div>

          <div className="mt-6 rounded-[24px] border border-[#EAECF0] p-6 shadow-[0_1px_2px_rgba(16,24,40,0.04)]">
            <Collapse items={advancedPanel} ghost />
          </div>
        </Form>
      </div>

      <BottomActionBar
        onCancel={() => history.push('/sync/file-ingest/select')}
        onNext={() => void submit()}
        nextText="创建导入任务"
        hintText="创建后任务进入待执行状态，可在任务列表中执行导入"
        nextDisabled={submitting || uploading}
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

export default CreateOfflineImportPage;
