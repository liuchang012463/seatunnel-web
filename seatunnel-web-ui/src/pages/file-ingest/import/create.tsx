import { ArrowLeftOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import { Alert, App, Button, Collapse, Form, Input, Popover, Radio, Select, Space, Spin, Tooltip, Upload } from 'antd';
import { Blocks, Check, CheckCircle2, Eye, Save } from 'lucide-react';
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
import RightConfigPanel from '@/pages/batch-link-up/workflow/RightConfigPanel';
import { getScheduleValidationMessage } from '@/pages/batch-link-up/workflow/components/ScheduleConfigContent/types';
import CodeBlockWithCopy from '@/pages/batch-link-up/workflow/operator/CodeBlockWithCopy';
import type { TabKey } from '@/pages/batch-link-up/workflow/types';
import CommonClientLinkSection, { type ConnectivityStatus } from '@/pages/common/components/CommonClientLinkSection';
import FileResourcePicker from '../FileResourcePicker';
import { FILE_RESOURCE_SOURCE, type FileResource } from '../types';
import OfflineImportConfigCanvas, { type OfflineImportConfigNode } from './OfflineImportConfigCanvas';
import './create.less';
import '../index.less';
import {
  OFFLINE_IMPORT_TYPE_LABELS,
  offlineImportTargetDbType,
  offlineImportTaskApi,
  type OfflineImportCategory,
  type OfflineDatapackInspect,
  type OfflineImportTypeCode,
} from './api';

const IMPORT_TYPE_OPTIONS: Record<OfflineImportCategory, OfflineImportTypeCode[]> = {
  DMP: ['KINGBASE_DMP', 'DAMENG_DMP'],
  SQL: ['KINGBASE_SQL', 'DAMENG_SQL'],
  DATAPACK: ['DATAPACK'],
};

const ENCODING_OPTIONS = [
  { value: 'UTF-8', label: 'UTF-8' },
  { value: 'GBK', label: 'GBK' },
];

const UPLOAD_PATH = '/offline-import';

// 离线导入任务保存调度参数，但实际导入仍由任务列表手动启动。
const DEFAULT_SCHEDULE_CONFIG = {
  executionMode: 'MANUAL',
  paramsList: [],
  instanceGenerateMode: 'immediately',
  scheduleRunType: 'pause',
  timeoutMode: 'system',
  rerunPolicy: 'disabled',
  autoRetry: false,
};

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
  const requestedCategory = searchParams.get('type')?.toUpperCase();
  const category: OfflineImportCategory = requestedCategory === 'SQL'
    ? 'SQL'
    : requestedCategory === 'DATAPACK' ? 'DATAPACK' : 'DMP';
  const isDatapack = category === 'DATAPACK';
  const availableTypes = IMPORT_TYPE_OPTIONS[category];
  const requestedImportType = searchParams.get('importType')?.toUpperCase();
  const importType = (availableTypes.includes(requestedImportType as OfflineImportTypeCode)
    ? requestedImportType
    : availableTypes[0]) as OfflineImportTypeCode;
  const isKingbaseDmp = importType === 'KINGBASE_DMP';
  const needsTargetDatabase = category === 'SQL' || isDatapack || isKingbaseDmp;
  const accept = isDatapack ? '.datapack' : category === 'DMP' ? '.dmp' : '.sql';
  const defaultTargetDbType = isDatapack ? 'MYSQL' : offlineImportTargetDbType(importType);
  const [targetType, setTargetType] = useState<any>(() => toTargetType(getTargetOption(defaultTargetDbType)));
  const targetDbType = isDatapack
    ? String(targetType?.dbType || defaultTargetDbType)
    : offlineImportTargetDbType(importType);
  const targetOption = useMemo(() => getTargetOption(targetDbType), [targetDbType]);
  const targetTypeChoices = useMemo(() => {
    if (!isDatapack) return [{ value: targetOption.value, label: targetOption.rawLabel || targetOption.value }];
    return generateDataSourceOptions()
      .filter((option) => supportsDatabaseScope(option.value))
      .map((option) => ({ value: option.value, label: option.rawLabel || option.value }));
  }, [isDatapack, targetOption]);

  const [activeStep, setActiveStep] = useState<CreateStep>('base');
  const [configNode, setConfigNode] = useState<OfflineImportConfigNode | null>(null);
  const [activeConfigTab, setActiveConfigTab] = useState<TabKey | null>(null);
  const [basicConfig, setBasicConfig] = useState<any>({ jobName: '', jobDesc: '', showClientId: false });
  const [scheduleConfig, setScheduleConfig] = useState<any>(DEFAULT_SCHEDULE_CONFIG);
  const [checkPassed, setCheckPassed] = useState(false);
  const [checking, setChecking] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewContent, setPreviewContent] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [clientId, setClientId] = useState<string>();
  const [targetDataSourceId, setTargetDataSourceId] = useState<string>();
  const [databaseOptions, setDatabaseOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [schemaOptions, setSchemaOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [databaseLoading, setDatabaseLoading] = useState(false);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [sourceTestStatus, setSourceTestStatus] = useState<ConnectivityStatus>('idle');
  const [targetTestStatus, setTargetTestStatus] = useState<ConnectivityStatus>('idle');
  const [file, setFile] = useState<FileResource | null>(null);
  const [packagePassword, setPackagePassword] = useState('');
  const [packageInspection, setPackageInspection] = useState<OfflineDatapackInspect>();
  const [inspectingPackage, setInspectingPackage] = useState(false);
  const [targetTableOptions, setTargetTableOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [targetColumnOptions, setTargetColumnOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [fieldMappings, setFieldMappings] = useState<Record<string, string>>({});
  const [uploading, setUploading] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const targetDatabase = Form.useWatch('targetDatabase', { form, preserve: true });
  const targetSchema = Form.useWatch('targetSchema', { form, preserve: true });
  const targetTable = Form.useWatch('datapackTable', { form, preserve: true });
  const selectedFormCode = Form.useWatch('formCode', { form, preserve: true });

  // 基础页和工作台使用同一份名称、描述，避免两步之间出现旧值。
  useEffect(() => {
    form.setFieldsValue({ name: basicConfig.jobName, description: basicConfig.jobDesc });
  }, [basicConfig.jobDesc, basicConfig.jobName, form]);

  useEffect(() => {
    setTargetType(toTargetType(targetOption));
  }, [targetOption]);

  useEffect(() => {
    form.setFieldsValue({ encoding: 'UTF-8', stopOnError: true, ignoreErrors: true });
  }, [form]);

  useEffect(() => {
    // 更换目标连接后，清空旧连接下的目录选择，避免数据库与 Schema 错配。
    form.setFieldsValue({ targetDatabase: undefined, targetSchema: undefined, datapackTable: undefined });
    setDatabaseOptions([]);
    setSchemaOptions([]);
    setTargetTableOptions([]);
    setTargetColumnOptions([]);
    setFieldMappings({});
    setCheckPassed(false);
  }, [form, targetDataSourceId]);

  useEffect(() => {
    if (!needsTargetDatabase || !targetDataSourceId || !supportsDatabaseScope(targetDbType)) {
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
  }, [isDatapack, isKingbaseDmp, message, needsTargetDatabase, targetDataSourceId, targetDbType]);

  useEffect(() => {
    if ((category !== 'SQL' && !isDatapack) || !targetDataSourceId || !targetDatabase || !supportsSchemaScope(targetDbType)) {
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
  }, [category, isDatapack, message, targetDataSourceId, targetDatabase, targetDbType]);

  useEffect(() => {
    if (!isDatapack || !targetDataSourceId || !targetDatabase) {
      setTargetTableOptions([]);
      setTargetColumnOptions([]);
      return;
    }
    let active = true;
    const loadTables = async () => {
      try {
        const response = await dataSourceCatalogApi.listTablesByDatabase(
          String(targetDataSourceId), String(targetDatabase), targetSchema ? String(targetSchema) : undefined,
        );
        if (response?.code !== 0) throw new Error(response?.message || '获取目标表列表失败');
        const options = (Array.isArray(response.data) ? response.data : []).map((item: any) => ({
          value: String(item?.value ?? item?.label ?? ''),
          label: String(item?.label ?? item?.value ?? ''),
        })).filter((item) => item.value);
        if (active) setTargetTableOptions(options);
      } catch (error: any) {
        if (active) {
          setTargetTableOptions([]);
          message.error(error?.message || '获取目标表列表失败');
        }
      }
    };
    void loadTables();
    return () => { active = false; };
  }, [isDatapack, message, targetDataSourceId, targetDatabase, targetSchema]);

  useEffect(() => {
    if (!isDatapack || !targetDataSourceId || !targetTable) {
      setTargetColumnOptions([]);
      setFieldMappings({});
      return;
    }
    let active = true;
    const loadColumns = async () => {
      try {
        const response = await dataSourceCatalogApi.listColumn(String(targetDataSourceId), {
          taskExecuteType: 'SINGLE_TABLE',
          read_mode: 'table',
          table_path: String(targetTable),
          ...(targetDatabase ? { database: String(targetDatabase) } : {}),
          ...(targetSchema ? { schema_name: String(targetSchema) } : {}),
          query: '',
        });
        if (response?.code !== 0) throw new Error(response?.message || '获取目标字段列表失败');
        const options = (Array.isArray(response.data) ? response.data : []).map((item: any) => {
          const name = String(item?.fieldName ?? '');
          const type = String(item?.fieldType ?? '');
          return { value: name, label: type ? `${name}（${type}）` : name };
        }).filter((item) => item.value);
        if (!active) return;
        setTargetColumnOptions(options);
        const formFields = packageInspection?.forms.find((item) => item.formCode === selectedFormCode)?.fields || [];
        const automatic: Record<string, string> = {};
        formFields.forEach((sourceField) => {
          const match = options.find((column) => column.value.toLowerCase() === sourceField.toLowerCase());
          if (match) automatic[sourceField] = match.value;
        });
        setFieldMappings(automatic);
      } catch (error: any) {
        if (active) {
          setTargetColumnOptions([]);
          setFieldMappings({});
          message.error(error?.message || '获取目标字段列表失败');
        }
      }
    };
    void loadColumns();
    return () => { active = false; };
  }, [isDatapack, message, packageInspection, selectedFormCode, targetDataSourceId, targetDatabase, targetSchema, targetTable]);

  const handleSourceChange = (value: string, _option: any) => {
    if (value !== FILE_RESOURCE_SOURCE.dbType) return;
    setSourceTestStatus('idle');
  };

  const handleTargetChange = (value: string, _option: any) => {
    setTargetType(toTargetType(getTargetOption(value)));
    setTargetDataSourceId(undefined);
    setTargetTestStatus('idle');
    setCheckPassed(false);
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
      const values = form.getFieldsValue(true);
      setBasicConfig((previous: any) => ({
        ...previous,
        jobName: values.name || '',
        jobDesc: values.description || '',
      }));
      setCheckPassed(false);
      setActiveStep('client');
    } catch {
      // Keep the user on the basic information step until the task name is valid.
    }
  };

  const canEnterConfig = Boolean(clientId && targetDataSourceId && targetTestStatus === 'success');

  const goConfigStep = () => {
    if (!clientId || !targetDataSourceId) {
      message.warning('请选择引接引擎和目标数据源');
      return;
    }
    if (targetTestStatus !== 'success') {
      message.warning('请先完成目标数据源连通性测试，并确保通过');
      return;
    }
    setActiveStep('config');
  };

  const resetPackageInspection = () => {
    setPackagePassword('');
    setPackageInspection(undefined);
    setTargetColumnOptions([]);
    setFieldMappings({});
    form.setFieldsValue({ formCode: undefined, datapackTable: undefined });
  };

  const inspectPackage = async () => {
    if (!file || !packagePassword) {
      message.warning('请选择数据包并输入数据包密码');
      return;
    }
    setInspectingPackage(true);
    try {
      const response = await offlineImportTaskApi.inspectDatapack(file.id, packagePassword, {
        skipErrorHandler: true,
      });
      if (response?.code !== 0 || !response?.data) {
        throw new Error(response?.message || response?.msg || '数据包验证失败');
      }
      const inspection = response.data as OfflineDatapackInspect;
      setPackageInspection(inspection);
      setFieldMappings({});
      form.setFieldsValue({
        formCode: inspection.forms.length === 1 ? inspection.forms[0].formCode : undefined,
        datapackTable: undefined,
      });
      setCheckPassed(false);
      message.success(`已读取 ${inspection.forms.length} 个表单，共 ${inspection.recordCount} 条记录`);
    } catch (error: any) {
      setPackageInspection(undefined);
      setFieldMappings({});
      message.error(error?.message || '数据包验证失败');
    } finally {
      setInspectingPackage(false);
    }
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
      if (isDatapack) resetPackageInspection();
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

  const submit = async () => {
    if (!validateConfiguration()) return;

    // Rebind the validated state so TypeScript can narrow the API request values.
    const selectedFile = file;
    const selectedDataSourceId = targetDataSourceId;
    if (!selectedFile || !selectedDataSourceId) return;

    let values: any;
    try {
      await form.validateFields();
      values = form.getFieldsValue(true);
    } catch {
      return;
    }

    setSubmitting(true);
    try {
      const mappings = Object.entries(fieldMappings)
        .filter(([, targetField]) => Boolean(targetField))
        .map(([sourceField, targetField]) => ({ sourceField, targetField }));
      const response = await offlineImportTaskApi.create({
        name: String(basicConfig.jobName || values.name || '').trim(),
        description: basicConfig.jobDesc ?? values.description,
        importType,
        fileResourceId: selectedFile.id,
        targetDatasourceId: selectedDataSourceId,
        targetDatabase: needsTargetDatabase ? values.targetDatabase : undefined,
        targetSchema: category === 'SQL' || isDatapack ? values.targetSchema || undefined : undefined,
        options: category === 'SQL'
          ? {
              sourceSchema: values.sourceSchema?.trim() || undefined,
              stopOnError: values.stopOnError,
              encoding: values.encoding,
            }
          : isDatapack ? {} : {
              sourceSchema: values.sourceSchema?.trim() || undefined,
              ...(importType === 'DAMENG_DMP' ? { ignoreErrors: values.ignoreErrors } : {}),
            },
        scheduleConfig,
        ...(isDatapack ? {
          datapackConfig: {
            packageFingerprint: packageInspection!.packageFingerprint,
            formCode: values.formCode,
            targetTable: values.datapackTable,
            mappings,
          },
          datapackPassword: packagePassword,
        } : {}),
      }, { skipErrorHandler: true });
      if (response?.code !== 0) throw new Error(response?.message || response?.msg || '保存配置失败');
      const createdTaskId = response?.data?.id;
      message.success('配置已保存');
      if (createdTaskId) {
        // 新任务首次保存后进入同一编辑配置页，后续修改统一走保存配置流程。
        history.push(`/sync/file-ingest/import/config/${encodeURIComponent(String(createdTaskId))}`);
      } else {
        history.push('/sync/file-ingest?tab=offline-import');
      }
    } catch (error: any) {
      message.error(error?.message || '保存配置失败');
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
        <Form.Item
          name="sourceSchema"
          label="DMP 内源 Schema"
          style={{ marginBottom: 0, maxWidth: 420 }}
          tooltip="仅还原备份文件中的指定 Schema，并保留其原名称；留空则按备份文件默认范围还原"
        >
          <Input placeholder="可选：仅还原指定 Schema，名称保持不变" style={{ maxWidth: 320 }} />
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
  const basicConfigForPanel = {
    ...basicConfig,
    sourceType: '湖文件资源',
    targetType: targetLabel,
    mode: `${category} 导入`,
    showClientId: false,
  };
  const updateBasicConfig: React.Dispatch<React.SetStateAction<any>> = (value) => {
    setBasicConfig(value);
    setCheckPassed(false);
  };
  const updateScheduleConfig: React.Dispatch<React.SetStateAction<any>> = (value) => {
    setScheduleConfig(value);
    setCheckPassed(false);
  };
  const validateConfiguration = () => {
    const taskName = String(basicConfig.jobName || form.getFieldValue('name') || '').trim();
    if (!taskName) {
      setActiveConfigTab('basic');
      message.warning('请输入任务名称');
      return false;
    }

    const scheduleError = getScheduleValidationMessage(scheduleConfig);
    if (scheduleError) {
      setActiveConfigTab('schedule');
      message.warning(scheduleError);
      return false;
    }

    if (!clientId || !targetDataSourceId || targetTestStatus !== 'success') {
      message.warning('请先完成引接引擎和目标数据源连通性校验');
      setActiveStep('client');
      return false;
    }

    const values = form.getFieldsValue(true);
    if (needsTargetDatabase && !values.targetDatabase) {
      setConfigNode('sink');
      message.warning('请选择目标数据库');
      return false;
    }

    if (!file) {
      setConfigNode('source');
      message.warning('请先上传或选择导入文件');
      return false;
    }
    if (!String(file.name || '').toLowerCase().endsWith(accept)) {
      setConfigNode('source');
      message.warning(`仅支持 ${accept} 文件`);
      return false;
    }

    if (isDatapack) {
      if (!packageInspection || !values.formCode) {
        setConfigNode('source');
        message.warning('请先输入数据包密码并读取包内表单');
        return false;
      }
      if (!values.datapackTable) {
        setConfigNode('sink');
        message.warning('请选择已有目标表');
        return false;
      }
      if (Object.values(fieldMappings).filter(Boolean).length === 0) {
        setConfigNode('sink');
        message.warning('请至少配置一组字段映射');
        return false;
      }
      if (!packagePassword) {
        setConfigNode('source');
        message.warning('保存配置时需要重新输入数据包密码以验证映射');
        return false;
      }
    }

    return true;
  };
  const checkConfiguration = async (showSuccess = true): Promise<boolean> => {
    setChecking(true);
    try {
      if (!validateConfiguration()) return false;
      await form.validateFields();
      setCheckPassed(true);
      if (showSuccess) message.success('配置校验通过');
      return true;
    } catch {
      // 保留当前步骤，让表单项自身展示具体校验提示。
      return false;
    } finally {
      setChecking(false);
    }
  };
  const previewConfiguration = async () => {
    if (!await checkConfiguration(false)) return;

    const values = form.getFieldsValue(true);
    const preview = {
      task: {
        name: String(basicConfig.jobName || form.getFieldValue('name') || '').trim(),
        description: basicConfig.jobDesc || form.getFieldValue('description') || undefined,
        importType: OFFLINE_IMPORT_TYPE_LABELS[importType],
      },
      source: {
        type: '湖文件资源',
        fileName: file?.name,
        fileSize: file?.size,
      },
      target: {
        dataSourceId: targetDataSourceId,
        database: needsTargetDatabase ? values.targetDatabase : undefined,
        schema: category === 'SQL' || isDatapack ? values.targetSchema || undefined : undefined,
        ...(isDatapack ? {
          packageType: packageInspection?.packageType,
          formCode: values.formCode,
          recordCount: packageInspection?.forms.find((item) => item.formCode === values.formCode)?.recordCount,
          table: values.datapackTable,
          fieldMappings,
        } : {}),
      },
      options: category === 'SQL'
        ? {
            sourceSchema: values.sourceSchema?.trim() || undefined,
            encoding: values.encoding,
            stopOnError: values.stopOnError,
          }
        : isDatapack ? {} : {
            sourceSchema: values.sourceSchema?.trim() || undefined,
            ...(importType === 'DAMENG_DMP' ? { ignoreErrors: values.ignoreErrors } : {}),
          },
      schedule: scheduleConfig,
    };

    setPreviewContent(JSON.stringify(preview, null, 2));
    setPreviewOpen(true);
  };
  const handlePreview = async () => {
    setPreviewLoading(true);
    try {
      await previewConfiguration();
    } catch (error: any) {
      message.error(error?.message || '预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

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

  const sourceConfig = (
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
      {isDatapack && (
        <>
          <Form.Item label="数据包密码">
            <Input.Password
              value={packagePassword}
              onChange={(event) => {
                setPackagePassword(event.target.value);
                setPackageInspection(undefined);
                setTargetColumnOptions([]);
                setFieldMappings({});
                form.setFieldsValue({ formCode: undefined, datapackTable: undefined });
                setCheckPassed(false);
              }}
              placeholder="输入采报端导出时设置的密码"
              maxLength={256}
            />
          </Form.Item>
          <Button
            type="primary"
            loading={inspectingPackage}
            disabled={!file || !packagePassword || uploading}
            onClick={() => void inspectPackage()}
          >
            验证并读取表单
          </Button>
          {packageInspection && (
            <Alert
              className="mt-3"
              type="success"
              showIcon
              message={`数据包 ${packageInspection.packageType} · ${packageInspection.recordCount} 条记录`}
              description={`可选表单：${packageInspection.forms.length} 个；保存时会再次验证密码。`}
            />
          )}
          <Form.Item
            label="包内表单"
            name="formCode"
            rules={[{ required: true, message: '请选择包内表单' }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder={packageInspection ? '请选择包内表单' : '先验证数据包'}
              disabled={!packageInspection}
              options={packageInspection?.forms.map((item) => ({
                value: item.formCode,
                label: `${item.formCode}（${item.recordCount} 条）`,
              })) || []}
              onChange={() => {
                setFieldMappings({});
                setCheckPassed(false);
              }}
            />
          </Form.Item>
        </>
      )}
      {!isDatapack && <Collapse items={advancedPanel} ghost />}
    </>
  );

  const sinkConfig = (
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
      {needsTargetDatabase ? (
        <>
          <Form.Item
            label={isKingbaseDmp ? '目标数据库' : '数据库'}
            name="targetDatabase"
            rules={[{ required: true, message: '请选择目标数据库' }]}
            className="offline-import-create__field"
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder={isKingbaseDmp ? '请选择还原目标数据库' : '请选择数据库'}
              options={databaseOptions}
              loading={databaseLoading}
              disabled={!targetDataSourceId}
              onChange={() => {
                form.setFieldValue('targetSchema', undefined);
                form.setFieldValue('datapackTable', undefined);
                setTargetColumnOptions([]);
                setFieldMappings({});
                setCheckPassed(false);
              }}
            />
          </Form.Item>
          {(category === 'SQL' || isDatapack) && supportsSchemaScope(targetDbType) && (
            <Form.Item label="Schema" name="targetSchema" className="offline-import-create__field">
              <Select
                showSearch
                allowClear
                optionFilterProp="label"
                placeholder="请选择 Schema（可选）"
                options={schemaOptions}
                loading={schemaLoading}
                disabled={!targetDatabase}
                onChange={() => {
                  form.setFieldValue('datapackTable', undefined);
                  setTargetColumnOptions([]);
                  setFieldMappings({});
                  setCheckPassed(false);
                }}
              />
            </Form.Item>
          )}
          {isDatapack ? (
            <>
              <Form.Item
                label="已有目标表"
                name="datapackTable"
                rules={[{ required: true, message: '请选择已有目标表' }]}
                className="offline-import-create__field"
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="选择表后读取目标字段"
                  options={targetTableOptions}
                  disabled={!targetDatabase}
                  onChange={() => {
                    setTargetColumnOptions([]);
                    setFieldMappings({});
                    setCheckPassed(false);
                  }}
                />
              </Form.Item>
              {packageInspection?.forms.find((item) => item.formCode === selectedFormCode)?.fields.map((sourceField) => (
                <Form.Item key={sourceField} label={`字段映射：${sourceField}`} className="offline-import-create__field">
                  <Select
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    placeholder="选择目标列；留空则忽略该源字段"
                    options={targetColumnOptions}
                    value={fieldMappings[sourceField]}
                    disabled={!targetTable}
                    onChange={(value) => {
                      setFieldMappings((current) => ({ ...current, [sourceField]: value || '' }));
                      setCheckPassed(false);
                    }}
                  />
                </Form.Item>
              ))}
              <div className="offline-import-create__target-hint">
                数据库仅追加写入已有表；目标表不会由导入任务创建或覆盖。
              </div>
            </>
          ) : isKingbaseDmp ? (
            <div className="offline-import-create__target-hint">
              目标数据库由此选择；Schema 将按 DMP 备份中的原名称还原。
            </div>
          ) : (
            <div className="offline-import-create__target-hint">先选择数据源连接，再选择目标数据库和 Schema；不需要选择表。</div>
          )}
        </>
      ) : (
        <div className="offline-import-create__target-hint">
          达梦的目标数据库实例由所选数据源连接确定；Schema 按 DMP 备份中的原名称还原。
        </div>
      )}
    </>
  );

  const fileResourcePicker = (
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
        if (isDatapack) resetPackageInspection();
        setCheckPassed(false);
        setPickerOpen(false);
      }}
    />
  );

  if (isConfigStep) {
    return (
      <div className="offline-import-create">
        <Form form={form} component={false} onValuesChange={() => setCheckPassed(false)}>
          <div className="workflow-editor-page generic-workflow-editor-page flex h-screen flex-col overflow-hidden bg-white">
            <div className="generic-workflow-editor-page__header shrink-0 border-b border-slate-100 bg-white px-6 py-4">
              <div className="flex items-start justify-between gap-4">
                <div className="flex min-w-0 items-start gap-3.5">
                  <div className="mt-0.5 flex h-11 w-11 shrink-0 items-center justify-center rounded-[14px] bg-indigo-50 text-indigo-600">
                    <Blocks size={18} />
                  </div>
                  <div className="min-w-0">
                    <div className="mb-0 text-[20px] font-bold leading-[1.2] text-slate-900">
                      {isDatapack ? '逻辑关系配置（离线数据包单表导入）' : '逻辑关系配置（DMP/SQL 导入任务）'}
                    </div>
                    <div className="mt-1 truncate text-[14px] text-slate-500">{basicConfig.jobName || '新建导入任务'} · {OFFLINE_IMPORT_TYPE_LABELS[importType]}</div>
                  </div>
                </div>
                <Button
                  type="text"
                  icon={<ArrowLeftOutlined />}
                  onClick={goPrevious}
                  className="!h-10 !rounded-full !border !border-slate-200 !bg-white !px-4 !text-slate-700 !shadow-sm hover:!border-slate-300 hover:!bg-slate-50 hover:!text-slate-800"
                >
                  返回上一步
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
                        <Space size={10}>
                          <Tooltip title="检查导入文件和目标数据库配置">
                            <span className="relative inline-flex">
                              <Button
                                type="default"
                                icon={<CheckCircle2 size={15} />}
                                loading={checking}
                                disabled={submitting || uploading || previewLoading}
                                onClick={() => void checkConfiguration()}
                                className="generic-workflow-editor-page__action-chip"
                              >
                                校验
                              </Button>
                              {checkPassed && (
                                <span className="absolute -right-1 -top-1 z-10 inline-flex h-4 w-4 items-center justify-center rounded-full border border-white bg-emerald-500 text-white shadow-sm">
                                  <Check size={10} strokeWidth={3} />
                                </span>
                              )}
                            </span>
                          </Tooltip>
                          <Popover
                            open={previewOpen}
                            placement="leftTop"
                            trigger="click"
                            classNames={{ root: 'st-hocon-popover' }}
                            content={(
                              <div className="w-[700px]">
                                <CodeBlockWithCopy
                                  content={previewContent}
                                  height={560}
                                  title="离线导入配置预览"
                                  onClose={() => setPreviewOpen(false)}
                                />
                              </div>
                            )}
                          >
                            <Button
                              type="default"
                              icon={<Eye size={15} />}
                              loading={previewLoading}
                              disabled={submitting || checking || uploading}
                              onClick={() => void handlePreview()}
                              className="generic-workflow-editor-page__action-chip"
                            >
                              预览
                            </Button>
                          </Popover>
                          <Tooltip title="保存配置后会进入任务配置页，导入仍需手动启动">
                            <span className="inline-flex h-[34px] select-none items-center justify-center rounded-full border border-blue-200 bg-blue-50 px-3 text-[14px] font-medium leading-none text-blue-600">
                              配置未保存
                            </span>
                          </Tooltip>
                          <Button
                            type="primary"
                            icon={<Save size={15} />}
                            loading={submitting}
                            disabled={uploading}
                            onClick={() => void submit()}
                            className="generic-workflow-editor-page__publish-button !inline-flex !h-[34px] !items-center !justify-center !rounded-full !border !border-[var(--st-color-primary)] !bg-[var(--st-color-primary)] !px-3.5 !text-[14px] !font-medium !text-white shadow-[0_6px_16px_rgba(33,135,168,0.2)] transition-all duration-200 hover:!border-[var(--st-color-accent)] hover:!bg-[var(--st-color-accent)] hover:!text-[var(--st-color-bg-primary)] disabled:!cursor-not-allowed"
                          >
                            保存配置
                          </Button>
                        </Space>
                      </div>
                      <div className="generic-workflow-editor-page__canvas-body flex min-h-0 flex-1 flex-col bg-white p-[18px] [background:radial-gradient(circle_at_top_left,rgba(78,116,248,0.04),transparent_22%),#ffffff]">
                        <div className="generic-workflow-editor-page__canvas offline-import-config-canvas-host min-h-0 flex-1 overflow-hidden rounded-2xl border border-dashed border-slate-200 bg-slate-50 p-4 text-[14px] text-slate-400">
                          <OfflineImportConfigCanvas
                            presentation="interactive"
                            activeNode={configNode}
                            onActiveNodeChange={setConfigNode}
                            sourceTitle="湖文件资源"
                            sourceSummary={file?.name || `待选择 ${accept} 文件`}
                            sinkTitle={targetLabel}
                            sinkSummary={targetDataSourceId
                              ? [
                                  `已选择目标数据源 ${targetDataSourceId}`,
                                  needsTargetDatabase ? targetDatabase : undefined,
                                ].filter(Boolean).join(' / ')
                              : '尚未选择目标数据源'}
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
                      setBasicConfig={updateBasicConfig}
                      scheduleConfig={scheduleConfig}
                      setScheduleConfig={updateScheduleConfig}
                    />
                  </div>
                </div>
              </div>
            </div>
          </div>
        </Form>
        {fileResourcePicker}
      </div>
    );
  }

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
              引擎连接
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
              targetDataSourceTypeOptions={targetTypeChoices}
              sourceManaged
              scene="offline"
              sourceTitle="来源"
              targetTitle="目标数据库"
              sourceCreateText="新建来源数据源"
              targetCreateText="新建目标数据源"
            />
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
        nextText={activeStep === 'base' ? '下一步：引擎连接' : activeStep === 'client' ? '下一步：具体配置' : '创建导入任务'}
        hintText={activeStep === 'base'
          ? '填写基础信息后，选择引接引擎并测试目标数据源连接'
          : activeStep === 'client'
            ? '任务会在所选引擎节点上进行目标数据源连通性校验'
            : '确认导入文件与目标配置，创建后可在任务列表中执行导入'}
        nextDisabled={activeStep === 'client' && !canEnterConfig}
        className="offline-import-create__action-bar"
      />

      {fileResourcePicker}
    </div>
  );
};

const FileResourceGlyph: React.FC = () => (
  <span className="offline-import-create__file-glyph" aria-hidden="true">▤</span>
);

export default CreateOfflineImportPage;
