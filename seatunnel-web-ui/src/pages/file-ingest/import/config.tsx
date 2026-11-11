import { ArrowLeftOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons';
import { history, useParams } from '@umijs/max';
import { Alert, App, Button, Form, Input, Popover, Select, Spin, Tooltip, Upload } from 'antd';
import { Blocks, Check, CheckCircle2, Eye, Save } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { generateDataSourceOptions } from '@/pages/batch-link-up/DataSourceSelect';
import RightConfigPanel from '@/pages/batch-link-up/workflow/RightConfigPanel';
import { getScheduleValidationMessage } from '@/pages/batch-link-up/workflow/components/ScheduleConfigContent/types';
import CodeBlockWithCopy from '@/pages/batch-link-up/workflow/operator/CodeBlockWithCopy';
import type { TabKey } from '@/pages/batch-link-up/workflow/types';
import { dataSourceCatalogApi, fetchDataSourceOptions } from '@/pages/data-source/service';
import DatabaseIcons from '@/pages/data-source/icon/DatabaseIcons';
import { supportsDatabaseScope, supportsSchemaScope } from '@/pages/data-source/dataSourceRegistry';
import { uploadFileResources } from '@/pages/file-resources/service';
import FileResourcePicker from '../FileResourcePicker';
import type { FileResource } from '../types';
import OfflineImportConfigCanvas, { type OfflineImportConfigNode } from './OfflineImportConfigCanvas';
import {
  OFFLINE_IMPORT_TYPE_LABELS,
  offlineImportTargetDbType,
  offlineImportTaskApi,
  type OfflineDatapackInspect,
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
  const [dataSourceOptions, setDataSourceOptions] = useState<Array<{ value: string; label: string; dbType?: string }>>([]);
  const [databaseOptions, setDatabaseOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [schemaOptions, setSchemaOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [targetTableOptions, setTargetTableOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [targetColumnOptions, setTargetColumnOptions] = useState<Array<{ value: string; label: string }>>([]);
  const [fieldMappings, setFieldMappings] = useState<Record<string, string>>({});
  const [packagePassword, setPackagePassword] = useState('');
  const [packageInspection, setPackageInspection] = useState<OfflineDatapackInspect>();
  const [inspectingPackage, setInspectingPackage] = useState(false);
  const [datapackTargetDbType, setDatapackTargetDbType] = useState('MYSQL');
  const [databaseLoading, setDatabaseLoading] = useState(false);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [checking, setChecking] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [checkPassed, setCheckPassed] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewContent, setPreviewContent] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [configNode, setConfigNode] = useState<OfflineImportConfigNode | null>(null);

  const importType = task?.importType;
  const isSql = String(importType || '').endsWith('_SQL');
  const isDatapack = importType === 'DATAPACK';
  const isKingbaseDmp = importType === 'KINGBASE_DMP';
  const isDmp = importType === 'KINGBASE_DMP' || importType === 'DAMENG_DMP';
  const needsTargetDatabase = isSql || isDatapack || isKingbaseDmp;
  const accept = isSql ? '.sql' : isDatapack ? '.datapack' : '.dmp';
  const targetDbType = isDatapack ? datapackTargetDbType : importType ? offlineImportTargetDbType(importType) : '';
  const targetOption = useMemo(
    () => generateDataSourceOptions().find((option) => option.value.toUpperCase() === targetDbType.toUpperCase()),
    [targetDbType],
  );
  const targetLabel = targetOption?.rawLabel || targetDbType;
  const targetDescription = isDatapack
    ? '选择目标数据库、已有表和字段映射'
    : isSql
      ? '数据库连接，可在下方选择数据库和 Schema'
      : isKingbaseDmp
        ? '选择目标数据库；Schema 按备份中的原名称还原'
        : '目标实例由数据源连接决定；Schema 按备份中的原名称还原';
  const targetDataSourceId = Form.useWatch('targetDatasourceId', { form, preserve: true });
  const targetDatabase = Form.useWatch('targetDatabase', { form, preserve: true });
  const targetSchema = Form.useWatch('targetSchema', { form, preserve: true });
  const targetTable = Form.useWatch('datapackTable', { form, preserve: true });
  const selectedFormCode = Form.useWatch('formCode', { form, preserve: true });
  const targetDataSourceName = dataSourceOptions.find((option) => option.value === String(targetDataSourceId))?.label
    || task?.targetDatasourceName
    || '尚未选择目标数据源';
  const basicConfigForPanel = {
    ...basicConfig,
    sourceType: '湖文件资源',
    targetType: targetLabel,
    mode: isDatapack ? '离线数据包单表导入' : 'DMP/SQL 导入',
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
        const currentIsDatapack = currentTask.importType === 'DATAPACK';
        const currentIsDmp = currentTask.importType === 'KINGBASE_DMP' || currentTask.importType === 'DAMENG_DMP';
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
          targetDatabase: currentTask.targetDatabase,
          targetSchema: currentIsDmp ? undefined : currentTask.targetSchema,
          formCode: currentTask.datapackConfig?.formCode,
          datapackTable: currentTask.datapackConfig?.targetTable,
          sourceSchema: currentTask.options?.sourceSchema || (currentIsDmp
            ? currentTask.targetSchema
            : undefined),
          encoding: currentTask.options?.encoding || 'UTF-8',
          stopOnError: currentTask.options?.stopOnError ?? true,
          ignoreErrors: currentTask.options?.ignoreErrors ?? true,
        });

        const datasourceTypes = currentIsDatapack
          ? generateDataSourceOptions().filter((option) => supportsDatabaseScope(option.value))
          : generateDataSourceOptions().filter((option) => option.value.toUpperCase() === offlineImportTargetDbType(currentTask.importType).toUpperCase());
        const optionGroups = await Promise.all(datasourceTypes.map(async (type) => {
          const optionsResponse = await fetchDataSourceOptions(type.value, 'SINK', type.pluginName);
          if (optionsResponse?.code !== 0) {
            throw new Error(optionsResponse?.message || `获取 ${type.rawLabel || type.value} 数据源列表失败`);
          }
          const rows = Array.isArray(optionsResponse?.data) ? optionsResponse.data : [];
          return rows.map((item) => ({
            value: String(item.id ?? item.value ?? ''),
            label: String(item.name ?? item.label ?? item.id ?? ''),
            dbType: type.value,
          })).filter((item) => item.value);
        }));
        const options = optionGroups.flat();
        const currentId = String(currentTask.targetDatasourceId);
        if (currentId && !options.some((option) => option.value === currentId)) {
          options.unshift({
            value: currentId,
            label: currentTask.targetDatasourceName || `数据源 ${currentId}`,
            dbType: currentIsDatapack ? 'MYSQL' : offlineImportTargetDbType(currentTask.importType),
          });
        }
        if (active) {
          setDataSourceOptions(options);
          if (currentIsDatapack) {
            setDatapackTargetDbType(options.find((option) => option.value === currentId)?.dbType || 'MYSQL');
          }
        }
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
        const selectedDatabase = String(form.getFieldValue('targetDatabase') || '');
        if (selectedDatabase && !options.some((item) => item.value === selectedDatabase)) {
          options.unshift({ value: selectedDatabase, label: selectedDatabase });
        }
        if (active) {
          setDatabaseOptions(options);
        }
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
  }, [form, isSql, isDatapack, isKingbaseDmp, message, needsTargetDatabase, targetDataSourceId, targetDbType]);

  useEffect(() => {
    if ((!isSql && !isDatapack) || !targetDataSourceId || !targetDatabase || !supportsSchemaScope(targetDbType)) {
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
        const selectedSchema = String(form.getFieldValue('targetSchema') || '');
        if (selectedSchema && !options.some((item) => item.value === selectedSchema)) {
          options.unshift({ value: selectedSchema, label: selectedSchema });
        }
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
  }, [form, isSql, isDatapack, message, targetDataSourceId, targetDatabase, targetDbType]);

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
        const selectedFields = packageInspection?.forms.find((item) => item.formCode === selectedFormCode)?.fields || [];
        const automatic: Record<string, string> = {};
        selectedFields.forEach((field) => {
          const match = options.find((column) => column.value.toLowerCase() === field.toLowerCase());
          if (match) automatic[field] = match.value;
        });
        const savedConfig = task?.datapackConfig;
        const savedMappings = savedConfig && savedConfig.formCode === selectedFormCode
          && savedConfig.targetTable === targetTable
          && savedConfig.mappings
          ? Object.fromEntries(savedConfig.mappings.map((mapping) => [mapping.sourceField, mapping.targetField]))
          : {};
        setFieldMappings({ ...automatic, ...savedMappings });
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
  }, [isDatapack, message, packageInspection, selectedFormCode, targetDataSourceId, targetDatabase, targetSchema, targetTable, task?.datapackConfig]);

  const resetDatapackInspection = () => {
    setPackagePassword('');
    setPackageInspection(undefined);
    setTargetColumnOptions([]);
    setFieldMappings({});
    form.setFieldsValue({ formCode: undefined, datapackTable: undefined });
  };

  const inspectDatapack = async () => {
    if (!file || !packagePassword) {
      message.warning('请选择数据包并输入数据包密码');
      return;
    }
    setInspectingPackage(true);
    try {
      const response = await offlineImportTaskApi.inspectDatapack(file.id, packagePassword, { skipErrorHandler: true });
      if (response?.code !== 0 || !response?.data) {
        throw new Error(response?.message || response?.msg || '数据包验证失败');
      }
      const inspection = response.data as OfflineDatapackInspect;
      const savedConfig = task?.datapackConfig;
      const matchesSavedPackage = savedConfig?.packageFingerprint === inspection.packageFingerprint;
      setPackageInspection(inspection);
      form.setFieldsValue({
        formCode: matchesSavedPackage && inspection.forms.some((item) => item.formCode === savedConfig?.formCode)
          ? savedConfig?.formCode
          : inspection.forms.length === 1 ? inspection.forms[0].formCode : undefined,
        datapackTable: matchesSavedPackage ? savedConfig?.targetTable : undefined,
      });
      setFieldMappings(matchesSavedPackage && savedConfig?.mappings
        ? Object.fromEntries(savedConfig.mappings.map((mapping) => [mapping.sourceField, mapping.targetField]))
        : {});
      setCheckPassed(false);
      setDirty(true);
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
      if (isDatapack) resetDatapackInspection();
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
        message.warning('请选择目标数据源');
        return;
      }
      if (needsTargetDatabase && !values.targetDatabase) {
        setConfigNode('sink');
        message.warning('请选择目标数据库');
        return;
      }
      if (isDatapack && (!packageInspection || !values.formCode || !values.datapackTable || !packagePassword)) {
        setConfigNode('source');
        message.warning('请验证数据包、选择表单和目标表，并输入密码后保存');
        return;
      }
      if (isDatapack && Object.values(fieldMappings).filter(Boolean).length === 0) {
        setConfigNode('sink');
        message.warning('请至少配置一组字段映射');
        return;
      }
      const options: OfflineImportOptions = isSql
        ? {
            sourceSchema: values.sourceSchema?.trim() || undefined,
            encoding: values.encoding,
            stopOnError: values.stopOnError,
          }
        : isDatapack ? {} : {
            sourceSchema: values.sourceSchema?.trim() || undefined,
            ...(task.importType === 'DAMENG_DMP' ? { ignoreErrors: values.ignoreErrors } : {}),
          };
      const mappings = Object.entries(fieldMappings)
        .filter(([, targetField]) => Boolean(targetField))
        .map(([sourceField, targetField]) => ({ sourceField, targetField }));
      setSaving(true);
      const response = await offlineImportTaskApi.update(id, {
        name: basicConfig.jobName.trim(),
        description: basicConfig.jobDesc,
        importType: task.importType,
        fileResourceId: file.id,
        targetDatasourceId: values.targetDatasourceId,
        targetDatabase: needsTargetDatabase ? values.targetDatabase : undefined,
        targetSchema: isSql || isDatapack ? values.targetSchema || undefined : undefined,
        options,
        ...(isDatapack ? {
          datapackConfig: {
            packageFingerprint: packageInspection!.packageFingerprint,
            formCode: values.formCode,
            targetTable: values.datapackTable,
            mappings,
          },
          datapackPassword: packagePassword,
        } : {}),
        scheduleConfig,
      });
      if (response?.code !== 0) throw new Error(response?.message || response?.msg || '保存配置失败');
      const savedTask = response.data as Partial<OfflineImportTask> | undefined;
      setTask((current) => current ? {
        ...current,
        ...savedTask,
        name: basicConfig.jobName.trim(),
        description: basicConfig.jobDesc,
        fileResourceId: String(file.id),
        fileName: file.name,
        targetDatasourceId: String(values.targetDatasourceId),
        targetDatasourceName: savedTask?.targetDatasourceName || targetDataSourceName,
        targetDatabase: needsTargetDatabase ? values.targetDatabase : undefined,
        targetSchema: isSql || isDatapack ? values.targetSchema || undefined : undefined,
        options,
        ...(isDatapack ? {
          datapackConfig: {
            packageFingerprint: packageInspection!.packageFingerprint,
            formCode: values.formCode,
            targetTable: values.datapackTable,
            mappings,
          },
        } : {}),
        scheduleConfig,
      } : current);
      setBasicConfig((current: any) => ({
        ...current,
        jobName: basicConfig.jobName.trim(),
        jobDesc: basicConfig.jobDesc,
      }));
      setDirty(false);
      message.success('配置已保存');
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error?.message || '保存配置失败');
    } finally {
      setSaving(false);
    }
  };

  const checkConfiguration = async (showSuccess = true): Promise<boolean> => {
    if (!task || isLocked) return false;
    setChecking(true);
    try {
      if (!basicConfig.jobName?.trim()) {
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
      await form.validateFields();
      const values = form.getFieldsValue(true);
      if (!file) {
        setConfigNode('source');
        message.warning('请先选择导入文件');
        return false;
      }
      if (!values.targetDatasourceId) {
        setConfigNode('sink');
        message.warning('请选择目标数据源');
        return false;
      }
      if (needsTargetDatabase && !values.targetDatabase) {
        setConfigNode('sink');
        message.warning('请选择目标数据库');
        return false;
      }
      if (isDatapack && (!packageInspection || !values.formCode || !values.datapackTable || !packagePassword)) {
        setConfigNode('source');
        message.warning('请验证数据包、选择表单和目标表，并输入密码');
        return false;
      }
      if (isDatapack && Object.values(fieldMappings).filter(Boolean).length === 0) {
        setConfigNode('sink');
        message.warning('请至少配置一组字段映射');
        return false;
      }
      setCheckPassed(true);
      if (showSuccess) message.success('配置检查通过');
      return true;
    } catch (error: any) {
      if (!error?.errorFields) message.error(error?.message || '配置检查失败');
      return false;
    } finally {
      setChecking(false);
    }
  };

  const previewConfiguration = async () => {
    if (!task || !await checkConfiguration(false)) return;

    const values = form.getFieldsValue(true);
    const preview = {
      task: {
        name: basicConfig.jobName.trim(),
        description: basicConfig.jobDesc || undefined,
        importType: OFFLINE_IMPORT_TYPE_LABELS[task.importType],
      },
      source: {
        type: '湖文件资源',
        fileName: file?.name,
        fileSize: file?.size,
      },
      target: {
        dataSource: targetDataSourceName,
        database: needsTargetDatabase ? values.targetDatabase : undefined,
        schema: isSql || isDatapack ? values.targetSchema || undefined : undefined,
        ...(isDatapack ? {
          packageType: packageInspection?.packageType,
          formCode: values.formCode,
          recordCount: packageInspection?.forms.find((item) => item.formCode === values.formCode)?.recordCount,
          table: values.datapackTable,
          fieldMappings,
        } : {}),
      },
      options: isSql
        ? {
            sourceSchema: values.sourceSchema?.trim() || undefined,
            encoding: values.encoding,
            stopOnError: values.stopOnError,
          }
        : isDatapack ? {} : {
            sourceSchema: values.sourceSchema?.trim() || undefined,
            ...(task.importType === 'DAMENG_DMP' ? { ignoreErrors: values.ignoreErrors } : {}),
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
      {isDatapack ? (
        <>
          <Form.Item label="数据包密码" className="offline-import-create__field">
            <Input.Password
              value={packagePassword}
              disabled={isLocked}
              onChange={(event) => {
                setPackagePassword(event.target.value);
                setPackageInspection(undefined);
                setTargetColumnOptions([]);
                setFieldMappings({});
                form.setFieldsValue({ formCode: undefined, datapackTable: undefined });
                setDirty(true);
                setCheckPassed(false);
              }}
              placeholder="输入采报端导出时设置的密码"
              maxLength={256}
            />
          </Form.Item>
          <Button
            type="primary"
            loading={inspectingPackage}
            disabled={isLocked || !file || !packagePassword || uploading}
            onClick={() => void inspectDatapack()}
          >
            验证并读取表单
          </Button>
          {packageInspection ? (
            <Alert
              className="mt-3"
              type="success"
              showIcon
              message={`数据包 ${packageInspection.packageType} · ${packageInspection.recordCount} 条记录`}
              description="保存时会再次验证密码与映射配置。"
            />
          ) : null}
          <Form.Item
            name="formCode"
            label="包内表单"
            rules={[{ required: true, message: '请选择包内表单' }]}
            className="offline-import-create__field mt-4"
          >
            <Select
              showSearch
              optionFilterProp="label"
              disabled={isLocked || !packageInspection}
              placeholder={packageInspection ? '请选择包内表单' : '先验证数据包'}
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
      ) : isSql ? (
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
          <Form.Item
            name="sourceSchema"
            label="DMP 内源 Schema"
            className="offline-import-create__field"
            tooltip="仅还原备份文件中的指定 Schema，并保留其原名称；留空则按备份文件默认范围还原"
          >
            <Input disabled={isLocked} placeholder="可选：仅还原指定 Schema，名称保持不变" />
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
          <span>{targetDescription}</span>
        </div>
      </div>
      {isDmp && task?.targetSchema ? (
        <Alert
          className="mb-3"
          type="warning"
          showIcon
          message="旧任务曾配置目标 Schema；保存后将清除该设置。"
          description="已有的源 Schema 筛选会保留，导入时按 DMP 备份中的原名称还原。"
        />
      ) : null}
      <Form.Item name="targetDatasourceId" label="数据源" rules={[{ required: true, message: '请选择目标数据源' }]} className="offline-import-create__field mt-4">
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="请选择数据源连接"
          options={dataSourceOptions}
          loading={!dataSourceOptions.length}
          disabled={isLocked}
          onChange={(value) => {
            const selected = dataSourceOptions.find((option) => option.value === String(value));
            if (isDatapack && selected?.dbType) setDatapackTargetDbType(selected.dbType);
            form.setFieldsValue({ targetDatabase: undefined, targetSchema: undefined });
            setDatabaseOptions([]);
            setSchemaOptions([]);
            setTargetTableOptions([]);
            setTargetColumnOptions([]);
            setFieldMappings({});
          }}
        />
      </Form.Item>
      {needsTargetDatabase ? (
        <>
          <Form.Item
            name="targetDatabase"
            label={isKingbaseDmp ? '目标数据库' : '数据库'}
            rules={[{ required: true, message: '请选择目标数据库' }]}
            className="offline-import-create__field"
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder={isKingbaseDmp ? '请选择还原目标数据库' : '请选择数据库'}
              options={databaseOptions}
              loading={databaseLoading}
              disabled={isLocked || !targetDataSourceId}
              onChange={() => {
                form.setFieldsValue({ targetSchema: undefined, datapackTable: undefined });
                setTargetTableOptions([]);
                setTargetColumnOptions([]);
                setFieldMappings({});
              }}
            />
          </Form.Item>
          {(isSql || isDatapack) && (
            <Form.Item name="targetSchema" label="Schema" className="offline-import-create__field">
              <Select
                showSearch
                allowClear
                optionFilterProp="label"
                placeholder="请选择 Schema（可选）"
                options={schemaOptions}
                loading={schemaLoading}
                disabled={isLocked || !targetDatabase}
                onChange={() => {
                  form.setFieldValue('datapackTable', undefined);
                  setTargetColumnOptions([]);
                  setFieldMappings({});
                }}
              />
            </Form.Item>
          )}
          {isDatapack ? (
            <>
              <Form.Item
                name="datapackTable"
                label="已有目标表"
                rules={[{ required: true, message: '请选择已有目标表' }]}
                className="offline-import-create__field"
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  disabled={isLocked || !targetDatabase}
                  placeholder="选择已有目标表"
                  options={targetTableOptions}
                  onChange={() => {
                    setTargetColumnOptions([]);
                    setFieldMappings({});
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
                    disabled={isLocked || !targetTable}
                    onChange={(value) => {
                      setFieldMappings((current) => ({ ...current, [sourceField]: value || '' }));
                      setDirty(true);
                      setCheckPassed(false);
                    }}
                  />
                </Form.Item>
              ))}
              <div className="offline-import-create__target-hint">仅向已有表追加写入，不创建或覆盖目标表。</div>
            </>
          ) : isKingbaseDmp ? (
            <div className="offline-import-create__target-hint">目标 Schema 按 DMP 备份中的原名称还原；无需选择表。</div>
          ) : <div className="offline-import-create__target-hint">先选择数据源连接，再选择目标数据库和 Schema；不需要选择表。</div>}
        </>
      ) : (
        <div className="offline-import-create__target-hint">
          达梦的目标数据库实例由所选数据源连接确定；Schema 按 DMP 备份中的原名称还原。
        </div>
      )}
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
                <div className="mb-0 text-[20px] font-bold leading-[1.2] text-slate-900">
                  {isDatapack ? '逻辑关系配置（离线数据包单表导入）' : '逻辑关系配置（SQL/DMP 导入任务）'}
                </div>
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
                      <Tooltip title={isLocked ? '任务需要先下线才能编辑' : '检查导入文件和目标数据库配置'}>
                        <span className="relative inline-flex">
                          <Button
                            type="default"
                            icon={<CheckCircle2 size={15} />}
                            loading={checking}
                            disabled={isLocked || saving}
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
                          disabled={isLocked || checking || saving}
                          onClick={() => void handlePreview()}
                          className="generic-workflow-editor-page__action-chip"
                        >
                          预览
                        </Button>
                      </Popover>
                      <Tooltip title={isLocked ? '任务已上线' : dirty ? '当前页面内容已变更，请先保存配置' : '当前配置已保存到数据库'}>
                        <span className={`inline-flex h-[34px] select-none items-center justify-center rounded-full border px-3 text-[14px] font-medium leading-none ${isLocked || !dirty ? 'border-emerald-200 bg-emerald-50 text-emerald-600' : 'border-blue-200 bg-blue-50 text-blue-600'}`}>
                          {isLocked ? '任务已上线' : dirty ? '有未保存的修改' : '配置已保存'}
                        </span>
                      </Tooltip>
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
                        sinkSummary={[
                          targetDataSourceName,
                          needsTargetDatabase ? targetDatabase : undefined,
                          isSql || isDatapack ? targetSchema : undefined,
                        ].filter(Boolean).join(' / ')}
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
            if (isDatapack) resetDatapackInspection();
            setDirty(true);
            setCheckPassed(false);
            setCheckPassed(false);
            setPickerOpen(false);
          }}
        />
      </div>
    </Form>
  );
};

export default OfflineImportConfigPage;
