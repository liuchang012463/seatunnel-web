import { FileOutlined } from '@ant-design/icons';
import { history, useLocation, useParams } from '@umijs/max';
import { App, Button, Form, Input } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import DataSourceSelect, {
  type DataSourceType,
  generateDataSourceOptions,
} from '@/pages/batch-link-up/DataSourceSelect';
import CommonClientLinkSection, {
  type ConnectivityStatus,
} from '@/pages/common/components/CommonClientLinkSection';
import { DATA_SOURCE_REGISTRY } from '@/pages/data-source/dataSourceRegistry';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import BottomActionBar from '@/pages/batch-link-up/detail/components/BottomActionBar';
import IconRightArrow from '@/pages/batch-link-up/IconRightArrow';
import {
  FILE_RESOURCE_SOURCE,
  fileTaskDraftKey,
  type FileTaskDetailConfig,
  type FileTaskType,
} from './types';

const fileResourceOption: DataSourceType = {
  value: FILE_RESOURCE_SOURCE.dbType,
  connectorType: FILE_RESOURCE_SOURCE.connectorType,
  pluginName: FILE_RESOURCE_SOURCE.pluginName,
  rawLabel: '文件资源库',
  sourceManaged: true,
  label: (
    <div className="flex items-center">
      <FileOutlined className="text-[22px] text-blue-600" />
      <span className="ml-2">文件资源库</span>
    </div>
  ),
};

const toFileOption = (item: (typeof DATA_SOURCE_REGISTRY)[number]): DataSourceType => ({
  value: item.dbType,
  connectorType: item.connectorType,
  pluginName: item.pluginName || item.connectorType,
  rawLabel: item.label,
  label: (
    <div className="flex items-center">
      <DatabaseIcons dbType={item.dbType} width="22px" height="22px" />
      <span className="ml-2">{item.label}</span>
    </div>
  ),
});

const remoteFileOptions = DATA_SOURCE_REGISTRY
  .filter((item) => item.category === 'FILE_TRANSFER')
  .map(toFileOption);

export const FILE_INGEST_DETAIL_CONFIG: FileTaskDetailConfig = {
  taskType: 'FILE_INGEST',
  mode: 'GUIDE_SINGLE',
  listPath: '/sync/file-ingest',
  configPath: '/sync/file-ingest/:id/config/single',
  title: '文件数据引接',
  description: '从文件资源库读取结构化文件，配置单表入库链路。',
  sourceOptions: [fileResourceOption],
  targetOptions: generateDataSourceOptions(),
  defaultSource: FILE_RESOURCE_SOURCE,
  defaultTarget: {
    dbType: 'MYSQL',
    connectorType: 'Jdbc',
    pluginName: 'JDBC-MYSQL',
  },
};

export const FILE_TRANSFER_DETAIL_CONFIG: FileTaskDetailConfig = {
  taskType: 'FILE_TRANSFER',
  mode: 'FILE_SYNC',
  listPath: '/sync/file-transfer',
  configPath: '/sync/file-transfer/:id/config/file-sync',
  title: '文件传输',
  description: '以二进制方式在文件资源和远端文件系统之间传输对象。',
  sourceOptions: [fileResourceOption, ...remoteFileOptions],
  targetOptions: remoteFileOptions,
  defaultSource: FILE_RESOURCE_SOURCE,
  defaultTarget: {
    dbType: 'FTP',
    connectorType: 'FtpFile',
    pluginName: 'FtpFile',
  },
};

const defaultDetailDraft = (config: FileTaskDetailConfig, id: string) => ({
  id,
  taskType: config.taskType,
  mode: config.mode,
  sourceType: config.defaultSource,
  targetType: config.defaultTarget,
  jobName: '',
  jobDesc: '',
});

const sourceManaged = (sourceType: { dbType?: string }) =>
  String(sourceType?.dbType || '').toUpperCase() === 'FILE_RESOURCE';

interface TaskDetailWizardProps {
  config: FileTaskDetailConfig;
}

const TaskDetailWizard: React.FC<TaskDetailWizardProps> = ({ config }) => {
  const { message } = App.useApp();
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [form] = Form.useForm();
  const [params, setParams] = useState<any>();
  const [sourceType, setSourceType] = useState<any>(config.defaultSource);
  const [targetType, setTargetType] = useState<any>(config.defaultTarget);
  const [activeStep, setActiveStep] = useState<'base' | 'client'>('base');
  const [clientId, setClientId] = useState<string>();
  const [sourceDataSourceId, setSourceDataSourceId] = useState<string>();
  const [targetDataSourceId, setTargetDataSourceId] = useState<string>();
  const [sourceTestStatus, setSourceTestStatus] = useState<ConnectivityStatus>('idle');
  const [targetTestStatus, setTargetTestStatus] = useState<ConnectivityStatus>('idle');
  const scrollRef = useRef<HTMLDivElement>(null);
  const clientSectionRef = useRef<HTMLDivElement>(null);

  const isSourceManaged = sourceManaged(sourceType);
  const sourceLabel = isSourceManaged ? '文件资源' : '远端文件';
  const targetLabel = '目标端';

  useEffect(() => {
    if (!id) return;
    const key = fileTaskDraftKey(config.taskType, id);
    const cached = sessionStorage.getItem(key);
    if (!cached) {
      setParams(defaultDetailDraft(config, id));
      form.setFieldsValue({ mode: config.mode });
      return;
    }

    try {
      const data = JSON.parse(cached);
      const nextSourceType = data?.sourceType || config.defaultSource;
      const nextTargetType = data?.targetType || config.defaultTarget;
      setParams({ ...defaultDetailDraft(config, id), ...data });
      setSourceType(nextSourceType);
      setTargetType(nextTargetType);
      setClientId(data?.clientId);
      setSourceDataSourceId(data?.sourceDataSourceId);
      setTargetDataSourceId(data?.targetDataSourceId);
      form.setFieldsValue({
        jobName: data?.jobName || '',
        jobDesc: data?.jobDesc || '',
        mode: config.mode,
      });
    } catch {
      message.error('读取任务草稿失败，请重新配置');
      setParams(defaultDetailDraft(config, id));
    }
  }, [config, form, id, location.search]);

  const handleSourceChange = (value: string, option: any) => {
    const next = {
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
      sourceManaged: option?.sourceManaged,
    };
    setSourceType(next);
    setSourceDataSourceId(undefined);
    setSourceTestStatus('idle');
  };

  const handleTargetChange = (value: string, option: any) => {
    setTargetType({
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
    });
    setTargetDataSourceId(undefined);
    setTargetTestStatus('idle');
  };

  const canContinue = useMemo(
    () => isSourceManaged || sourceTestStatus === 'success',
    [isSourceManaged, sourceTestStatus],
  );

  const goBack = () => history.push(config.listPath);

  const goStep = async (step: 'base' | 'client') => {
    if (step === 'base') {
      setActiveStep('base');
      scrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
      return;
    }
    try {
      await form.validateFields(['jobName']);
      setActiveStep('client');
      scrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
    } catch {
      // Ant Design renders the field-level error.
    }
  };

  const handleNext = async () => {
    if (activeStep === 'base') {
      await goStep('client');
      return;
    }

    try {
      await form.validateFields(['jobName']);
      if (!canContinue) {
        message.warning('请先完成来源连通性测试');
        return;
      }
      if (targetTestStatus !== 'success') {
        message.warning('请先完成目标端连通性测试');
        return;
      }

      const values = form.getFieldsValue(true);
      const nextDraft = {
        ...(params || defaultDetailDraft(config, id || '')),
        ...values,
        id,
        taskType: config.taskType,
        mode: config.mode,
        sourceType,
        targetType,
        clientId,
        sourceDataSourceId,
        targetDataSourceId,
        sourceTestStatus,
        targetTestStatus,
      };
      sessionStorage.setItem(fileTaskDraftKey(config.taskType, String(id)), JSON.stringify(nextDraft));
      history.push(`${config.configPath.replace(':id', encodeURIComponent(String(id)))}?scene=create`);
    } catch {
      // Ant Design renders the field-level error.
    }
  };

  if (!params) return null;

  return (
    <div className="min-h-screen bg-white">
      <div className="border-b border-slate-100 bg-white">
        <div className="mx-auto flex max-w-[1540px] items-center justify-between gap-4 px-6 py-5">
          <div>
            <div className="text-[22px] font-semibold leading-8 text-slate-900">{config.title}</div>
            <div className="mt-1 text-sm leading-6 text-slate-500">{config.description}</div>
          </div>
          <Button onClick={goBack}>返回列表</Button>
        </div>
      </div>

      <div className="mx-auto max-w-[1540px] px-6 pb-28 pt-6">
        <div className="mb-6 flex items-center gap-3">
          {(['base', 'client'] as const).map((step, index) => {
            const active = activeStep === step;
            const done = index === 0 && activeStep === 'client';
            return (
              <div key={step} className="flex min-w-0 flex-1 items-center gap-3">
                <button
                  type="button"
                  onClick={() => void goStep(step)}
                  className={[
                    'inline-flex shrink-0 items-center gap-2 rounded-full px-3 py-1.5 text-sm',
                    active || done ? 'bg-blue-50 text-blue-700' : 'bg-slate-50 text-slate-400',
                  ].join(' ')}
                >
                  <span className={[
                    'flex h-5 w-5 items-center justify-center rounded-full text-xs font-semibold',
                    active ? 'bg-blue-600 text-white' : done ? 'bg-emerald-500 text-white' : 'bg-slate-200 text-slate-500',
                  ].join(' ')}>
                    {index + 1}
                  </span>
                  {step === 'base' ? '基础信息' : '客户端与连接'}
                </button>
                {index === 0 ? <div className="h-px flex-1 bg-slate-200" /> : null}
              </div>
            );
          })}
        </div>

        <div ref={scrollRef} className="max-h-[calc(100vh-250px)] overflow-auto">
          {activeStep === 'base' ? (
            <Form form={form} layout="vertical">
              <div className="rounded-[24px] border border-slate-200 bg-white p-6 shadow-sm">
                <div className="rounded-2xl border border-blue-100 bg-blue-50/50 p-5">
                  <div className="mb-3 text-sm font-medium text-slate-800">引接路径</div>
                  <div className="flex items-center gap-3">
                    <DataSourceSelect
                      value={sourceType}
                      onChange={handleSourceChange}
                      dataSourceOptions={config.sourceOptions}
                      placeholder="请选择来源"
                      prefix="来源"
                      width="48%"
                    />
                    <IconRightArrow />
                    <DataSourceSelect
                      value={targetType}
                      onChange={handleTargetChange}
                      dataSourceOptions={config.targetOptions}
                      placeholder="请选择去向"
                      prefix="去向"
                      width="48%"
                    />
                  </div>
                  <div className="mt-3 text-xs leading-5 text-slate-500">
                    {isSourceManaged
                      ? '下一步选择执行客户端并测试目标连接；文件资源将在进入任务配置后选择。'
                      : '远端文件来源将在后续配置页面中选择目录或对象前缀。'}
                  </div>
                </div>

                <div className="mt-6 grid gap-4">
                  <Form.Item
                    label="任务名称"
                    name="jobName"
                    rules={[{ required: true, message: '请输入任务名称' }]}
                    className="mb-0"
                  >
                    <Input placeholder={config.taskType === 'FILE_TRANSFER' ? '例如：资源库 → S3 归档' : '例如：销售订单文件 → MySQL'} />
                  </Form.Item>
                  <Form.Item label="任务描述" name="jobDesc" className="mb-0">
                    <Input.TextArea rows={3} placeholder="描述本次引接的范围和用途" />
                  </Form.Item>
                </div>

                <div className="mt-6 rounded-xl border border-dashed border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-600">
                  <strong className="text-slate-800">{config.mode === 'GUIDE_SINGLE' ? '单表任务' : '二进制文件任务'}</strong>
                  <span className="ml-2">
                    {config.mode === 'GUIDE_SINGLE'
                      ? '文件数据引接只允许一个文件来源和一个目标表。'
                      : '文件传输只搬运对象，不解析 CSV、Excel、JSON 或 TXT 内容。'}
                  </span>
                </div>
              </div>
            </Form>
          ) : (
            <CommonClientLinkSection
              activeStep={activeStep}
              sourceType={sourceType}
              targetType={targetType}
              sourceLabel={sourceLabel}
              targetLabel={targetLabel}
              clientId={clientId}
              setClientId={setClientId}
              handleSourceChange={handleSourceChange}
              handleTargetChange={handleTargetChange}
              sourceDataSourceId={sourceDataSourceId}
              targetDataSourceId={targetDataSourceId}
              setSourceDataSourceId={setSourceDataSourceId}
              setTargetDataSourceId={setTargetDataSourceId}
              sourceTestStatus={sourceTestStatus}
              targetTestStatus={targetTestStatus}
              setSourceTestStatus={setSourceTestStatus}
              setTargetTestStatus={setTargetTestStatus}
              sourceDataSourceTypeOptions={config.sourceOptions}
              targetDataSourceTypeOptions={config.targetOptions}
              sourceManaged={isSourceManaged}
              sectionRef={clientSectionRef}
              scene="offline"
              sourceTitle="来源"
              targetTitle="去向"
              sourceCreateText="新建来源数据源"
              targetCreateText="新建去向数据源"
            />
          )}
        </div>
      </div>

      <BottomActionBar
        onCancel={goBack}
        onNext={() => void handleNext()}
        onPrev={activeStep === 'client' ? () => setActiveStep('base') : undefined}
        nextText={activeStep === 'base' ? '下一步：客户端与连接' : '进入任务配置'}
        hintText={activeStep === 'base' ? '先保存任务基础信息，再选择客户端并测试目标连接。' : '确认客户端与目标连接后，进入文件资源和任务规则配置。'}
        nextDisabled={activeStep === 'client' && (!canContinue || targetTestStatus !== 'success')}
      />
    </div>
  );
};

export default TaskDetailWizard;
