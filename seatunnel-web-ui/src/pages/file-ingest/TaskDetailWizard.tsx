import { FileOutlined } from '@ant-design/icons';
import { history, useLocation, useParams } from '@umijs/max';
import { Button, Form, Input } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  DataSourceSelect,
  type DataSourceType,
  generateDataSourceOptions,
} from '@/pages/batch-link-up/DataSourceSelect';
import IconRightArrow from '@/pages/batch-link-up/IconRightArrow';
import BottomActionBar from '@/pages/batch-link-up/detail/components/BottomActionBar';
import PageHeader from '@/pages/batch-link-up/detail/components/PageHeader';
import { STEP_THEME } from '@/pages/batch-link-up/detail/constants';
import CommonClientLinkSection, {
  type ConnectivityStatus,
} from '@/pages/common/components/CommonClientLinkSection';
import { DATA_SOURCE_REGISTRY } from '@/pages/data-source/dataSourceRegistry';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { openPrettyNotification } from '@/utils/prettyNotification';
import {
  FILE_RESOURCE_SOURCE,
  fileTaskDraftKey,
  type FileTaskDetailConfig,
} from './types';

const fileResourceOption: DataSourceType = {
  value: FILE_RESOURCE_SOURCE.dbType,
  connectorType: FILE_RESOURCE_SOURCE.connectorType,
  pluginName: FILE_RESOURCE_SOURCE.pluginName,
  rawLabel: '湖文件',
  sourceManaged: true,
  label: (
    <div className="flex items-center">
      <FileOutlined className="text-[22px] text-blue-600" />
      <span className="ml-2">湖文件</span>
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
  title: '离线文件导入',
  description: '上传或选择结构化文件，配置单表解析与入库链路。',
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
  title: '文件同步任务',
  description: '以二进制方式在湖文件与远端文件系统之间同步对象。',
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
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [form] = Form.useForm();
  const [params, setParams] = useState<any>();
  const [activeStep, setActiveStep] = useState<'base' | 'client'>('base');
  const [sourceType, setSourceType] = useState<any>(config.defaultSource);
  const [targetType, setTargetType] = useState<any>(config.defaultTarget);
  const [clientId, setClientId] = useState<string>();
  const [sourceDataSourceId, setSourceDataSourceId] = useState<string>();
  const [targetDataSourceId, setTargetDataSourceId] = useState<string>();
  const [sourceTestStatus, setSourceTestStatus] = useState<ConnectivityStatus>('idle');
  const [targetTestStatus, setTargetTestStatus] = useState<ConnectivityStatus>('idle');
  const clientSectionRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  const isSourceManaged = sourceManaged(sourceType);
  const sourceLabel = isSourceManaged ? '湖文件' : '远端文件系统';
  const targetLabel = config.taskType === 'FILE_TRANSFER' ? '目标文件系统' : '目标表';

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
      setParams(defaultDetailDraft(config, id));
    }
  }, [config, form, id, location.search]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 });
  }, [activeStep]);

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

  const canGoNextFromClient = (isSourceManaged || sourceTestStatus === 'success')
    && targetTestStatus === 'success';

  const goBack = () => history.push(config.listPath);

  const goBaseStep = () => setActiveStep('base');

  const goClientStep = async () => {
    try {
      await form.validateFields(['jobName']);
    } catch {
      return;
    }
    setActiveStep('client');
  };

  const persistDraftAndEnterConfig = () => {
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
  };

  const handleNextWithGuard = async () => {
    if (activeStep === 'base') {
      await goClientStep();
      return;
    }

    if (!canGoNextFromClient) {
      if (!isSourceManaged && sourceTestStatus !== 'success' && targetTestStatus !== 'success') {
        openPrettyNotification({
          type: 'warning',
          title: '操作警告',
          description: '请先完成来源和去向的连通性测试，并确保都通过',
        });
        return;
      }
      if (!isSourceManaged && sourceTestStatus !== 'success') {
        openPrettyNotification({
          type: 'warning',
          title: '操作警告',
          description: '请先完成来源的连通性测试，并确保都通过',
        });
        return;
      }
      openPrettyNotification({
        type: 'warning',
        title: '操作警告',
        description: '请先完成去向的连通性测试，并确保通过',
      });
      return;
    }

    persistDraftAndEnterConfig();
  };

  if (!params) {
    // 深链兜底态：任务数据经列表页缓存进入，缺失时给页壳 + 返回 + 引导。
    return (
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 12,
          padding: '96px 24px',
        }}
      >
        <strong style={{ fontSize: 16, color: 'var(--st-color-text-primary)' }}>
          未找到任务数据
        </strong>
        <span style={{ fontSize: 13, color: 'var(--st-color-text-muted)' }}>
          任务数据在从列表进入时缓存；请回到{config.title}任务列表，重新进入创建流程。
        </span>
        <Button type="primary" onClick={goBack}>
          返回任务列表
        </Button>
      </div>
    );
  }

  const isBaseStep = activeStep === 'base';
  const isClientStep = activeStep === 'client';
  const isBaseDone = activeStep === 'client';

  const basePillClass = isBaseStep
    ? STEP_THEME.base.pill
    : isBaseDone
      ? STEP_THEME.base.pillDone
      : STEP_THEME.base.pillInactive;

  const baseDotClass = isBaseStep
    ? STEP_THEME.base.dot
    : isBaseDone
      ? STEP_THEME.base.dotDone
      : STEP_THEME.base.dotInactive;

  const clientPillClass = isClientStep
    ? STEP_THEME.client.pill
    : STEP_THEME.client.pillInactive;

  const clientDotClass = isClientStep
    ? STEP_THEME.client.dot
    : STEP_THEME.client.dotInactive;

  const stepProgress = isClientStep ? '100%' : '0%';

  return (
    <div className="min-h-screen bg-white">
      <PageHeader onBack={goBack} title={config.title} description={config.description} />

      <div className="mx-auto max-w-[1540px] px-6 pb-28 pt-6">
        <div className="sticky top-0 z-20 mb-6 bg-white/95 pt-1 backdrop-blur">
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={goBaseStep}
              className={[
                'flex items-center gap-2 rounded-full px-3 py-1.5 text-sm transition-all duration-200',
                basePillClass,
              ].join(' ')}
            >
              <span
                className={[
                  'flex h-5 w-5 items-center justify-center rounded-full text-[12px] font-semibold transition-all duration-200',
                  baseDotClass,
                ].join(' ')}
              >
                1
              </span>
              基础配置
            </button>

            <div className="relative h-[2px] flex-1 overflow-hidden rounded-full bg-[#EAECF0]">
              <div
                className="absolute left-0 top-0 h-full rounded-full transition-[width] duration-500 ease-[cubic-bezier(0.22,1,0.36,1)]"
                style={{
                  width: stepProgress,
                  background: 'rgba(23,92,211,0.25)',
                }}
              />
            </div>

            <button
              type="button"
              onClick={() => void goClientStep()}
              className={[
                'flex items-center gap-2 rounded-full px-3 py-1.5 text-sm transition-all duration-200',
                clientPillClass,
              ].join(' ')}
            >
              <span
                className={[
                  'flex h-5 w-5 items-center justify-center rounded-full text-[12px] font-semibold transition-all duration-200',
                  clientDotClass,
                ].join(' ')}
              >
                2
              </span>
              客户端与连接
            </button>
          </div>
        </div>

        <div
          ref={scrollRef}
          style={{ height: 'calc(100vh - 260px)', overflow: 'auto' }}
        >
          <Form form={form} layout="vertical">
            <div className="overflow-hidden rounded-[24px] border border-[#EAECF0] bg-white shadow-[0_1px_2px_rgba(16,24,40,0.04)]">
              {isBaseStep && (
                <div className="p-6">
                  <div className="space-y-6 rounded-[24px] bg-white shadow-sm">
                    <div className="rounded-2xl border border-[#E4E7EC] bg-[#FAFBFC] p-5">
                      <div className="mb-3 text-[14px] font-medium text-[color:var(--st-color-text-primary)]">
                        数据同步方式
                      </div>
                      <div className="flex items-center gap-3">
                        <DataSourceSelect
                          value={sourceType}
                          onChange={handleSourceChange}
                          dataSourceOptions={config.sourceOptions}
                          placeholder="请选择来源"
                          prefix="来源"
                          width="48%"
                        />
                        <div className="text-[color:var(--st-color-text-muted)]">
                          <IconRightArrow />
                        </div>
                        <DataSourceSelect
                          value={targetType}
                          onChange={handleTargetChange}
                          dataSourceOptions={config.targetOptions}
                          placeholder="请选择去向"
                          prefix="去向"
                          width="48%"
                        />
                      </div>
                    </div>

                    <div className="space-y-4">
                      <div className="text-[14px] font-medium text-[color:var(--st-color-text-primary)]">
                        任务信息
                      </div>
                      <div className="grid grid-cols-1 gap-4">
                        <Form.Item
                          label="任务名称"
                          name="jobName"
                          rules={[{ required: true, message: '请输入任务名称' }]}
                          className="mb-0"
                        >
                          <Input
                            placeholder={
                              config.taskType === 'FILE_TRANSFER'
                                ? '例如：湖文件 → S3 归档'
                                : '例如：销售订单文件 → MySQL'
                            }
                            className="!h-[36px] !rounded-[12px]"
                          />
                        </Form.Item>
                        <Form.Item label="任务描述" name="jobDesc" className="mb-0">
                          <Input.TextArea
                            placeholder="描述同步范围、用途、注意事项等"
                            rows={3}
                            className="!rounded-xl"
                          />
                        </Form.Item>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {isClientStep && (
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
                  targetTitle={targetLabel}
                  sourceCreateText="新建来源数据源"
                  targetCreateText="新建去向数据源"
                />
              )}
            </div>
          </Form>
        </div>
      </div>

      <BottomActionBar
        onCancel={goBack}
        onNext={() => void handleNextWithGuard()}
        onPrev={isClientStep ? goBaseStep : undefined}
        nextText={isBaseStep ? '下一步：客户端与连接' : '进入任务配置'}
        hintText={
          isBaseStep
            ? '先完成基础信息，再选择客户端并测试来源与去向连接'
            : '确认客户端链接关系后，将进入任务配置'
        }
        nextDisabled={isClientStep && !canGoNextFromClient}
      />
    </div>
  );
};

export default TaskDetailWizard;
