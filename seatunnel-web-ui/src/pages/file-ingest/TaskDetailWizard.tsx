import { FileOutlined } from '@ant-design/icons';
import { history, useLocation, useParams } from '@umijs/max';
import { App, Button, Form, Input } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { type DataSourceType, generateDataSourceOptions } from '@/pages/batch-link-up/DataSourceSelect';
import CommonClientLinkSection, {
  type ConnectivityStatus,
} from '@/pages/common/components/CommonClientLinkSection';
import { DATA_SOURCE_REGISTRY } from '@/pages/data-source/dataSourceRegistry';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import BottomActionBar from '@/pages/batch-link-up/detail/components/BottomActionBar';
import {
  FILE_RESOURCE_SOURCE,
  fileTaskDraftKey,
  type FileTaskDetailConfig,
  type FileTaskType,
} from './types';
import './TaskDetailWizard.less';

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
  const { message } = App.useApp();
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [form] = Form.useForm();
  const [params, setParams] = useState<any>();
  const [sourceType, setSourceType] = useState<any>(config.defaultSource);
  const [targetType, setTargetType] = useState<any>(config.defaultTarget);
  const [clientId, setClientId] = useState<string>();
  const [sourceDataSourceId, setSourceDataSourceId] = useState<string>();
  const [targetDataSourceId, setTargetDataSourceId] = useState<string>();
  const [sourceTestStatus, setSourceTestStatus] = useState<ConnectivityStatus>('idle');
  const [targetTestStatus, setTargetTestStatus] = useState<ConnectivityStatus>('idle');
  const clientSectionRef = useRef<HTMLDivElement>(null);

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

  const canContinue = isSourceManaged || sourceTestStatus === 'success';

  const goBack = () => history.push(config.listPath);

  const handleNext = async () => {
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
    <div className="file-task-create-page">
      <header className="file-task-create-page__header">
        <div className="file-task-create-page__header-inner">
          <div className="file-task-create-page__identity">
            <div className="file-task-create-page__icon" aria-hidden="true">
              <FileOutlined />
            </div>
            <div>
              <h1>{config.title}</h1>
              <p>{config.description}</p>
            </div>
          </div>
          <Button onClick={goBack}>返回列表</Button>
        </div>
      </header>

      <main className="file-task-create-page__body">
        <section className="file-task-create-page__overview">
          <div className="file-task-create-page__section-heading">
            <div>
              <span className="file-task-create-page__eyebrow">任务基础信息</span>
              <h2>填写任务信息</h2>
            </div>
            <span className="file-task-create-page__task-kind">
              {config.mode === 'GUIDE_SINGLE' ? '单表入库' : '对象传输'}
            </span>
          </div>

          <Form form={form} layout="vertical">
            <div className="file-task-create-page__form-grid">
              <Form.Item
                label="任务名称"
                name="jobName"
                rules={[{ required: true, message: '请输入任务名称' }]}
                className="file-task-create-page__field"
              >
                <Input placeholder={config.taskType === 'FILE_TRANSFER' ? '例如：湖文件 → S3 归档' : '例如：销售订单文件 → MySQL'} />
              </Form.Item>
              <div className="file-task-create-page__task-note">
                <strong>{config.mode === 'GUIDE_SINGLE' ? '离线文件导入' : '文件同步任务'}</strong>
                <span>
                  {config.mode === 'GUIDE_SINGLE'
                    ? '读取一个文件，解析后写入目标表。'
                    : '只同步对象，不解析 CSV、Excel、JSON 或 TXT 内容。'}
                </span>
              </div>
            </div>
            <Form.Item label="任务描述" name="jobDesc" className="file-task-create-page__field">
              <Input.TextArea rows={2} placeholder="描述本次任务的范围和用途" />
            </Form.Item>
          </Form>
        </section>

        <section className="file-task-create-page__connections">
          <div className="file-task-create-page__section-heading">
            <div>
              <span className="file-task-create-page__eyebrow">连接与执行</span>
              <h2>确认来源、客户端和去向</h2>
            </div>
            <span className="file-task-create-page__section-hint">连接测试通过后即可进入任务配置</span>
          </div>

          <CommonClientLinkSection
            activeStep="client"
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
        </section>
      </main>

      <BottomActionBar
        onCancel={goBack}
        onNext={() => void handleNext()}
        nextText="进入任务配置"
        hintText="完成任务信息与连接测试后，进入湖文件与规则配置。"
      />
    </div>
  );
};

export default TaskDetailWizard;
