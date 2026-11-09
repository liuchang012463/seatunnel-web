import { ArrowLeftOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons';
import { history, useParams } from '@umijs/max';
import { Alert, App, Button, Form, Input, Select, Spin, Tooltip, Upload } from 'antd';
import { Blocks, CheckCircle2, Save } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { generateDataSourceOptions } from '@/pages/batch-link-up/DataSourceSelect';
import RightConfigPanel from '@/pages/batch-link-up/workflow/RightConfigPanel';
import { getScheduleValidationMessage } from '@/pages/batch-link-up/workflow/components/ScheduleConfigContent/types';
import type { TabKey } from '@/pages/batch-link-up/workflow/types';
import { fetchDataSourceOptions } from '@/pages/data-source/service';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { uploadFileResources } from '@/pages/file-resources/service';
import FileResourcePicker from '../FileResourcePicker';
import type { FileResource } from '../types';
import OfflineImportConfigCanvas, { type OfflineImportConfigNode } from './OfflineImportConfigCanvas';
import {
  OFFLINE_IMPORT_TYPE_LABELS,
  offlineImportTargetDbType,
  offlineImportTaskApi,
  type OfflineImportOptions,
  type OfflineImportTask,
} from './api';
import './create.less';
import '../index.less';

const UPLOAD_PATH = '/offline-import';
// 保存批量配置面板中的调度项；离线导入执行仍由任务列表的启动操作触发。
const DEFAULT_SCHEDULE_CONFIG = {
  executionMode: 'MANUAL',
  paramsList: [],
  instanceGenerateMode: 'immediately',
  scheduleRunType: 'pause',
  timeoutMode: 'system',
  rerunPolicy: 'disabled',
  autoRetry: false,
};

const OfflineImportConfigPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [task, setTask] = useState<OfflineImportTask>();
  const [basicConfig, setBasicConfig] = useState<any>({ jobName: '', jobDesc: '', showClientId: false });
  const [scheduleConfig, setScheduleConfig] = useState<any>(DEFAULT_SCHEDULE_CONFIG);
  const [activeConfigTab, setActiveConfigTab] = useState<TabKey | null>(null);
  const [file, setFile] = useState<FileResource>();
  const [dataSourceOptions, setDataSourceOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [checking, setChecking] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [checkPassed, setCheckPassed] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [configNode, setConfigNode] = useState<OfflineImportConfigNode | null>(null);

  const importType = task?.importType;
  const isSql = String(importType || '').endsWith('_SQL');
  const accept = isSql ? '.sql' : '.dmp';
  const targetDbType = importType ? offlineImportTargetDbType(importType) : '';
  const targetOption = useMemo(
    () => generateDataSourceOptions().find((option) => option.value.toUpperCase() === targetDbType.toUpperCase()),
    [targetDbType],
  );
  const targetLabel = targetOption?.rawLabel || targetDbType;
  const targetDataSourceId = Form.useWatch('targetDatasourceId', form);
  const targetDataSourceName = dataSourceOptions.find((option) => option.value === String(targetDataSourceId))?.label
    || task?.targetDatasourceName
    || '尚未选择目标数据源';
  const basicConfigForPanel = {
    ...basicConfig,
    sourceType: '湖文件资源',
    targetType: targetLabel,
    mode: 'DMP/SQL 导入',
    showClientId: false,
  };
  const isLocked = task?.releaseState !== 'OFFLINE' || task?.status === 'RUNNING' || task?.status === 'QUEUED';

  useEffect(() => {
    let active = true;
    const load = async () => {
      if (!id) {
        setLoading(false);
        return;
      }
      setLoading(true);
      try {
        const response = await offlineImportTaskApi.get(id, { skipErrorHandler: true });
        if (response?.code !== 0 || !response?.data) {
          throw new Error(response?.message || response?.msg || '获取离线导入任务失败');
        }
        const currentTask = response.data as OfflineImportTask;
        if (!active) return;
        setTask(currentTask);
        setBasicConfig({
          jobName: currentTask.name,
          jobDesc: currentTask.description || '',
          showClientId: false,
        });
        setScheduleConfig(currentTask.scheduleConfig || DEFAULT_SCHEDULE_CONFIG);
        setFile({
          id: String(currentTask.fileResourceId),
          name: currentTask.fileName,
          kind: 'FILE',
        });
        form.setFieldsValue({
          targetDatasourceId: String(currentTask.targetDatasourceId),
          targetSchema: currentTask.targetSchema,
          sourceSchema: currentTask.options?.sourceSchema,
          encoding: currentTask.options?.encoding || 'UTF-8',
          stopOnError: currentTask.options?.stopOnError ?? true,
          ignoreErrors: currentTask.options?.ignoreErrors ?? true,
        });

        const optionsResponse = await fetchDataSourceOptions(
          offlineImportTargetDbType(currentTask.importType),
          'SINK',
          generateDataSourceOptions().find((option) =>
            option.value.toUpperCase() === offlineImportTargetDbType(currentTask.importType).toUpperCase())?.pluginName,
        );
        if (optionsResponse?.code !== 0) {
          throw new Error(optionsResponse?.message || '获取目标数据库列表失败');
        }
        const rows = Array.isArray(optionsResponse?.data) ? optionsResponse.data : [];
        const options = rows.map((item) => ({
          value: String(item.id ?? item.value ?? ''),
          label: String(item.name ?? item.label ?? item.id ?? ''),
        })).filter((item) => item.value);
        const currentId = String(currentTask.targetDatasourceId);
        if (currentId && !options.some((option) => option.value === currentId)) {
          options.unshift({ value: currentId, label: currentTask.targetDatasourceName || `数据源 ${currentId}` });
        }
        if (active) setDataSourceOptions(options);
        if (active) setDirty(false);
      } catch (error: any) {
        if (active) message.error(error?.message || '获取离线导入任务失败');
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => { active = false; };
    // target option derives from the loaded task's supported database type.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, form]);

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
      setDirty(true);
      setCheckPassed(false);
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

  const handleBasicConfigChange = (value: any) => {
    setBasicConfig(value);
    setDirty(true);
    setCheckPassed(false);
  };

  const handleScheduleConfigChange = (value: any) => {
    setScheduleConfig(value);
    setDirty(true);
    setCheckPassed(false);
  };

  const save = async () => {
    if (!task || !id) return;
    if (isLocked) {
      message.warning('请先下线任务，并确保任务没有运行中实例');
      return;
    }
    if (!basicConfig.jobName?.trim()) {
      setActiveConfigTab('basic');
      message.warning('请输入任务名称');
      return;
    }
    const scheduleError = getScheduleValidationMessage(scheduleConfig);
    if (scheduleError) {
      setActiveConfigTab('schedule');
      message.warning(scheduleError);
      return;
    }
    if (!file) {
      message.warning('请先上传或选择导入文件');
      return;
    }
    if (!file.name.toLowerCase().endsWith(accept)) {
      message.warning(`仅支持 ${accept} 文件`);
      return;
    }
    try {
      await form.validateFields();
      // Form.Item fields in the inactive canvas node are preserved but unmounted.
      // Read the full store so switching Source/Sink cannot drop the other node's settings.
      const values = form.getFieldsValue(true);
      if (!values.targetDatasourceId) {
        setConfigNode('sink');
        message.warning('请选择目标数据库');
        return;
      }
      const options: OfflineImportOptions = isSql
        ? {
            sourceSchema: values.sourceSchema?.trim() || undefined,
            encoding: values.encoding,
            stopOnError: values.stopOnError,
          }
        : {
            sourceSchema: values.sourceSchema?.trim() || undefined,
            ...(task.importType === 'DAMENG_DMP' ? { ignoreErrors: values.ignoreErrors } : {}),
          };
      setSaving(true);
      const response = await offlineImportTaskApi.update(id, {
        name: basicConfig.jobName.trim(),
        description: basicConfig.jobDesc,
        importType: task.importType,
        fileResourceId: file.id,
        targetDatasourceId: values.targetDatasourceId,
        targetSchema: values.targetSchema || undefined,
        options,
        scheduleConfig,
      });
      if (response?.code !== 0) throw new Error(response?.message || response?.msg || '保存配置失败');
      message.success('任务配置已保存');
      setDirty(false);
      setCheckPassed(false);
      history.push('/sync/file-ingest?tab=offline-import');
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error?.message || '保存配置失败');
    } finally {
      setSaving(false);
    }
  };

  const checkConfiguration = async () => {
    if (!task || isLocked) return;
    setChecking(true);
    try {
      if (!basicConfig.jobName?.trim()) {
        setActiveConfigTab('basic');
        message.warning('请输入任务名称');
        return;
      }
      const scheduleError = getScheduleValidationMessage(scheduleConfig);
      if (scheduleError) {
        setActiveConfigTab('schedule');
        message.warning(scheduleError);
        return;
      }
      await form.validateFields();
      const values = form.getFieldsValue(true);
      if (!file) {
        setConfigNode('source');
        message.warning('请先选择导入文件');
        return;
      }
      if (!values.targetDatasourceId) {
        setConfigNode('sink');
        message.warning('请选择目标数据库');
        return;
      }
      setCheckPassed(true);
      message.success('配置检查通过');
    } catch (error: any) {
      if (!error?.errorFields) message.error(error?.message || '配置检查失败');
    } finally {
      setChecking(false);
    }
  };

  const backToList = () => history.push('/sync/file-ingest?tab=offline-import');

  if (loading) {
    return <div className="flex min-h-[360px] items-center justify-center"><Spin /></div>;
  }
  if (!task) {
    return <div className="p-8"><Alert type="error" showIcon message="无法读取离线导入任务" action={<Button onClick={backToList}>返回任务列表</Button>} /></div>;
  }

  const sourceConfig = (
    <>
      <div className="offline-import-create__import-type mb-4">
        <span>导入类型</span>
        <strong>{OFFLINE_IMPORT_TYPE_LABELS[task.importType]}</strong>
      </div>
      <div className="offline-import-create__selected-target offline-import-create__selected-file">
        <span className="offline-import-create__file-glyph"><InboxOutlined /></span>
        <div>
          <strong>{file?.name || '尚未选择导入文件'}</strong>
          <span>{file?.size ? `文件大小：${(file.size / 1024).toFixed(1)} KB` : `仅支持 ${accept} 文件`}</span>
        </div>
      </div>
      <div className="offline-import-create__file-picker offline-import-create__edit-file-actions">
        <Upload
          accept={accept}
          maxCount={1}
          customRequest={uploadRequest}
          beforeUpload={beforeUpload}
          showUploadList={false}
          disabled={uploading || isLocked}
        >
          <Button icon={uploading ? undefined : <InboxOutlined />} loading={uploading} disabled={isLocked}>上传替换文件</Button>
        </Upload>
        <Button icon={<UploadOutlined />} disabled={uploading || isLocked} onClick={() => setPickerOpen(true)}>从湖文件选择</Button>
      </div>
      {isSql ? (
        <>
          <Form.Item name="sourceSchema" label="SQL 源 Schema" className="offline-import-create__field" tooltip="跨模式还原时填写脚本中的源 Schema，用于映射到目标 Schema。">
            <Input disabled={isLocked} placeholder="留空表示不进行 Schema 映射" />
          </Form.Item>
          <Form.Item name="encoding" label="文件编码" className="offline-import-create__field">
            <Select disabled={isLocked} options={[{ value: 'UTF-8', label: 'UTF-8' }, { value: 'GBK', label: 'GBK' }]} />
          </Form.Item>
          <Form.Item name="stopOnError" label="出错时停止" className="offline-import-create__field">
            <Select disabled={isLocked} options={[{ value: true, label: '停止（默认）' }, { value: false, label: '继续执行' }]} />
          </Form.Item>
        </>
      ) : (
        <>
          <Form.Item name="sourceSchema" label="DMP 内源 Schema" className="offline-import-create__field">
            <Input disabled={isLocked} placeholder="留空则与目标 Schema 相同" />
          </Form.Item>
          {task.importType === 'DAMENG_DMP' ? (
            <Form.Item name="ignoreErrors" label="忽略对象已存在等错误" className="offline-import-create__field">
              <Select disabled={isLocked} options={[{ value: true, label: '忽略（默认）' }, { value: false, label: '不忽略' }]} />
            </Form.Item>
          ) : null}
        </>
      )}
    </>
  );
  const sinkConfig = (
    <>
      <div className="offline-import-create__selected-target">
        <DatabaseIcons dbType={targetDbType} width="24px" height="24px" />
        <div>
          <strong>{targetLabel}</strong>
          <span>数据库连接，可在下方选择</span>
        </div>
      </div>
      <Form.Item name="targetDatasourceId" label="目标数据库" rules={[{ required: true, message: '请选择目标数据库' }]} className="offline-import-create__field mt-4">
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="选择目标数据源"
          options={dataSourceOptions}
          loading={!dataSourceOptions.length}
          disabled={isLocked}
        />
      </Form.Item>
      <Form.Item name="targetSchema" label="目标 Schema" className="offline-import-create__field">
        <Input disabled={isLocked} placeholder={targetDbType === 'KINGBASE' ? '例如 public' : '例如 TEST'} maxLength={128} />
      </Form.Item>
      <div className="offline-import-create__target-hint">只需选择目标数据库连接；目标 Schema 可选，不需要选择表。</div>
    </>
  );

  return (
    <Form
      form={form}
      component={false}
      onValuesChange={() => {
        setDirty(true);
        setCheckPassed(false);
      }}
    >
      <div className="workflow-editor-page generic-workflow-editor-page flex h-screen flex-col overflow-hidden bg-white">
        <div className="generic-workflow-editor-page__header shrink-0 border-b border-slate-100 bg-white px-6 py-4">
          <div className="flex items-start justify-between gap-4">
            <div className="flex min-w-0 items-start gap-3.5">
              <div className="mt-0.5 flex h-11 w-11 shrink-0 items-center justify-center rounded-[14px] bg-indigo-50 text-indigo-600">
                <Blocks size={18} />
              </div>
              <div className="min-w-0">
                <div className="mb-0 text-[20px] font-bold leading-[1.2] text-slate-900">逻辑关系配置（SQL/DMP 导入任务）</div>
                <div className="mt-1 truncate text-[14px] text-slate-500">{task.name} · 任务 ID：{task.id}</div>
              </div>
            </div>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={backToList}
              className="!h-10 !rounded-full !border !border-slate-200 !bg-white !px-4 !text-slate-700 !shadow-sm hover:!border-slate-300 hover:!bg-slate-50 hover:!text-slate-800"
            >
              返回任务列表
            </Button>
          </div>
        </div>

        <div className="generic-workflow-editor-page__body min-h-0 flex-1 overflow-hidden p-[18px]">
          <div className="generic-workflow-editor-page__frame h-full overflow-hidden rounded-xl border border-slate-200 bg-gradient-to-b from-white via-white to-slate-50 shadow-[0_10px_30px_rgba(15,23,42,0.04)]">
            <div className="generic-workflow-editor-page__columns flex h-full min-w-0 items-stretch">
              <div className="generic-workflow-editor-page__main h-full min-w-0 flex-1 overflow-hidden">
                <div className="generic-workflow-editor-page__content flex h-full flex-col overflow-hidden rounded-lg bg-white shadow-[0_4px_18px_rgba(15,23,42,0.03)]">
                  <div className="generic-workflow-editor-page__toolbar flex h-14 shrink-0 items-center justify-between border-b border-slate-100 bg-gradient-to-b from-white to-slate-50 px-[18px]">
                    <div className="text-[15px] font-semibold text-slate-800">任务配置</div>
                    <div className="flex items-center gap-2.5">
                      <Tooltip title={isLocked ? '任务需要先下线才能编辑' : '检查来源文件和目标数据库配置'}>
                        <Button
                          type="default"
                          icon={<CheckCircle2 size={15} />}
                          loading={checking}
                          disabled={isLocked}
                          onClick={() => void checkConfiguration()}
                          className="generic-workflow-editor-page__action-chip"
                        >
                          校验
                        </Button>
                      </Tooltip>
                      <span className={`inline-flex h-[34px] select-none items-center justify-center rounded-full border px-3 text-[14px] font-medium leading-none ${dirty ? 'border-amber-200 bg-amber-50 text-amber-600' : 'border-emerald-200 bg-emerald-50 text-emerald-600'}`}>
                        {isLocked ? '任务已上线' : dirty ? '配置未保存' : checkPassed ? '校验通过' : '配置已保存'}
                      </span>
                      <Button
                        type="primary"
                        icon={<Save size={15} />}
                        loading={saving}
                        disabled={isLocked || !dirty || uploading}
                        onClick={() => void save()}
                        className="generic-workflow-editor-page__publish-button !inline-flex !h-[34px] !items-center !justify-center !rounded-full !border !border-[var(--st-color-primary)] !bg-[var(--st-color-primary)] !px-3.5 !text-[14px] !font-medium !text-white shadow-[0_6px_16px_rgba(33,135,168,0.2)] transition-all duration-200 hover:!border-[var(--st-color-accent)] hover:!bg-[var(--st-color-accent)] hover:!text-[var(--st-color-bg-primary)] disabled:!cursor-not-allowed"
                      >
                        保存配置
                      </Button>
                    </div>
                  </div>

                  <div className="generic-workflow-editor-page__canvas-body flex min-h-0 flex-1 flex-col bg-white p-[18px] [background:radial-gradient(circle_at_top_left,rgba(78,116,248,0.04),transparent_22%),#ffffff]">
                    {isLocked ? <Alert className="mb-3 shrink-0" type="warning" showIcon message="请先下线任务，并确保没有运行中实例后再编辑配置。" /> : null}
                    <div className="generic-workflow-editor-page__canvas offline-import-config-canvas-host min-h-0 flex-1 overflow-hidden rounded-2xl border border-dashed border-slate-200 bg-slate-50 p-4 text-[14px] text-slate-400">
                      <OfflineImportConfigCanvas
                        presentation="interactive"
                        activeNode={configNode}
                        onActiveNodeChange={setConfigNode}
                        sourceTitle="湖文件资源"
                        sourceSummary={file?.name || `待选择 ${accept} 文件`}
                        sinkTitle={targetLabel}
                        sinkSummary={targetDataSourceName}
                        sinkDbType={targetDbType}
                        sourceConfig={sourceConfig}
                        sinkConfig={sinkConfig}
                      />
                    </div>
                  </div>
                </div>
              </div>
              <div
                className="generic-workflow-editor-page__side h-full shrink-0 overflow-hidden"
                style={{ width: activeConfigTab ? 480 : 58 }}
              >
                <RightConfigPanel
                  activeTab={activeConfigTab}
                  onTabChange={setActiveConfigTab}
                  allowedTabs={['basic', 'schedule']}
                  contentDisabled={isLocked}
                  tabNotices={{
                    schedule: (
                      <Alert
                        className="mb-3"
                        type="info"
                        showIcon
                        message="当前离线导入仍需手动启动；调度配置会保存，但不会自动触发导入。"
                      />
                    ),
                  }}
                  basicConfig={basicConfigForPanel}
                  setBasicConfig={handleBasicConfigChange}
                  scheduleConfig={scheduleConfig}
                  setScheduleConfig={handleScheduleConfigChange}
                />
              </div>
            </div>
          </div>
        </div>
        <FileResourcePicker
          open={pickerOpen}
          value={file}
          selectionMode="file"
          title={`选择 ${accept} 文件`}
          onCancel={() => setPickerOpen(false)}
          onSelect={(resource) => {
            if (!String(resource.name || '').toLowerCase().endsWith(accept)) {
              message.warning(`仅支持 ${accept} 文件`);
              return;
            }
            setFile(resource);
            setDirty(true);
            setCheckPassed(false);
            setPickerOpen(false);
          }}
        />
      </div>
    </Form>
  );
};

export default OfflineImportConfigPage;
