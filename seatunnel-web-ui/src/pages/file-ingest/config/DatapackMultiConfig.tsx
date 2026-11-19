import { Alert, App, Button, Modal, Select, Space, Tag } from 'antd';
import { ArrowLeft, Eye, LayoutGrid, Save } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { seatunnelJobDefinitionApi } from '@/pages/batch-link-up/api';
import TableTransferPanel from '@/pages/batch-link-up/config/multi/components/TableTransferPanel';
import {
  buildSchedulePayload,
  getScheduleValidationMessage,
} from '@/pages/batch-link-up/workflow/components/ScheduleConfigContent/types';
import CodeBlockWithCopy from '@/pages/batch-link-up/workflow/operator/CodeBlockWithCopy';
import RightConfigPanel from '@/pages/batch-link-up/workflow/RightConfigPanel';
import type { TabKey } from '@/pages/batch-link-up/workflow/types';
import { supportsSchemaScope } from '@/pages/data-source/dataSourceRegistry';
import { dataSourceCatalogApi, fetchDataSourceOptions } from '@/pages/data-source/service';
import DatapackPackageInput, { useDatapackPackage } from '../DatapackPackageInput';
import type { FileResource } from '../types';
import type { DatapackIdentity } from './datapack';
import { mergeEnvConfig, mergeScheduleConfig } from './runtime';

interface CsvFile {
  fileName: string;
  fileResourceId: string;
  columnNames: string[];
  nullValue: string;
}

interface SavedDatapack extends DatapackIdentity {
  fileNames?: string[];
  files?: CsvFile[];
  mappings?: (Omit<CsvFile, 'columnNames'> & { fields: Record<string, string> })[];
}

interface Target {
  dbType: string;
  connectorType: string;
  pluginName: string;
  datasourceId?: string;
  database?: string;
  schemaName?: string;
}

type Catalog = { code: number; data: any[]; message?: string };

function useCatalog(request: (() => Promise<Catalog>) | undefined) {
  const [state, setState] = useState<{ request?: typeof request; items: any[]; error?: string }>({ items: [] });

  useEffect(() => {
    if (!request) return;
    let active = true;
    request().then((response) => {
      if (response.code !== 0) throw new Error(response.message || '读取目标目录失败');
      if (active) setState({ request, items: Array.isArray(response.data) ? response.data : [] });
    }).catch((error: Error) => {
      if (active) setState({ request, items: [], error: error.message || '读取目标目录失败' });
    });
    return () => { active = false; };
  }, [request]);

  return state.request === request
    ? { ...state, loading: false }
    : { items: [], loading: !!request, error: undefined };
}

const optionsOf = (items: any[]) => items.map((item) => ({
  value: String(item.value ?? item.id ?? ''),
  label: String(item.label ?? item.name ?? item.value ?? ''),
})).filter((item) => item.value);

const DatapackMultiConfig = ({ params, goBack }: { params: any; goBack: () => void }) => {
  const { message } = App.useApp();
  const saved: SavedDatapack | undefined = params.content?.datapack;
  const [initialFiles] = useState(() => saved?.files ?? saved?.mappings?.map(
    ({ fileName, fileResourceId, fields, nullValue }) => ({
      fileName, fileResourceId, columnNames: Object.keys(fields), nullValue,
    }),
  ) ?? []);
  const packet = useDatapackPackage(saved && {
    resource: { id: saved.packageResourceId, name: saved.packageName, kind: 'FILE' },
    inspection: {
      packageFingerprint: saved.packageFingerprint,
      fileNames: saved.fileNames ?? initialFiles.map(({ fileName }) => fileName),
    },
  });
  const { resource, inspection } = packet;
  const [selected, setSelected] = useState(() => initialFiles.map(({ fileName }) => fileName));
  // Keep the extracted resources for this package so preview followed by save can reuse them.
  const [files, setFiles] = useState(() => new Map(initialFiles.map((file) => [file.fileName, file])));

  const [target, setTarget] = useState<Target>(() => {
    const value = params.content?.target || params.targetType;
    return {
      dbType: value.dbType,
      connectorType: value.connectorType,
      pluginName: value.pluginName,
      datasourceId: value.datasourceId ?? params.targetDataSourceId,
      database: value.database,
      schemaName: value.schemaName,
    };
  });
  const [basic, setBasic] = useState(() => ({
    ...(params.basic || { jobName: params.jobName, jobDesc: params.jobDesc, clientId: params.clientId }),
    sourceType: 'S3File',
    targetType: target.dbType,
    mode: 'GUIDE_MULTI',
    runtimeType: 'BATCH',
  }));
  const [schedule, setSchedule] = useState(() => mergeScheduleConfig(params.schedule));
  const [env, setEnv] = useState(() => mergeEnvConfig(params.env));
  const [activeTab, setActiveTab] = useState<TabKey | null>(null);
  const [dirty, setDirty] = useState(!saved);
  const [busy, setBusy] = useState<'preview' | 'save'>();
  const submitting = useRef(false);
  const [hocon, setHocon] = useState<string>();

  const { datasourceId, dbType, pluginName, database, schemaName } = target;
  const schemaScoped = supportsSchemaScope(dbType);
  const datasourceRequest = useMemo(() => () => fetchDataSourceOptions(dbType, 'SINK', pluginName), [dbType, pluginName]);
  const datasources = useCatalog(datasourceRequest);
  const datasourceOptions = optionsOf(datasources.items.filter((item) => !item.systemManaged));
  const canUseDatasource = datasourceOptions.some((item) => item.value === String(datasourceId));
  const databases = useCatalog(useMemo(() => canUseDatasource && datasourceId
    ? () => dataSourceCatalogApi.listDatabases(String(datasourceId))
    : undefined, [canUseDatasource, datasourceId]));
  const databaseOptions = optionsOf(databases.items);
  const canUseDatabase = databaseOptions.some(({ value }) => value === database);
  const schemas = useCatalog(useMemo(() => canUseDatabase && datasourceId && database && schemaScoped
    ? () => dataSourceCatalogApi.listSchemas(String(datasourceId), database)
    : undefined, [canUseDatabase, datasourceId, database, schemaScoped]));
  const schemaOptions = optionsOf(schemas.items);
  const canUseSchema = !schemaScoped || (!schemas.loading && !schemas.error
    && (schemaOptions.length === 0 || schemaOptions.some(({ value }) => value === schemaName)));
  const error = datasources.error || databases.error || schemas.error;

  const changePackage = (next: FileResource) => {
    if (String(next.id) === String(resource?.id)) return;
    packet.setPackage(next);
    setSelected([]);
    setFiles(new Map());
    setDirty(true);
  };

  const inspectPackage = async () => {
    const manifest = await packet.inspect();
    if (!manifest) return;
    if (manifest.packageFingerprint !== inspection?.packageFingerprint) {
      setFiles(new Map());
      setSelected((names) => names.filter((name) => manifest.fileNames.includes(name)));
    }
    setDirty(true);
  };

  const updateTarget = (patch: Partial<Target>) => {
    setTarget((current) => ({ ...current, ...patch }));
    setDirty(true);
  };

  const submit = async (action: 'preview' | 'save') => {
    if (submitting.current || packet.action) return;
    if (!resource || !inspection || !selected.length || !canUseDatasource || !canUseDatabase || !canUseSchema) {
      message.warning('请选择要导入的 CSV 和目标库');
      return;
    }
    if (!basic.jobName?.trim()) {
      setActiveTab('basic');
      message.warning('请输入任务名称');
      return;
    }
    const scheduleWarning = getScheduleValidationMessage(schedule);
    if (action === 'save' && scheduleWarning) {
      setActiveTab('schedule');
      message.warning(scheduleWarning);
      return;
    }

    submitting.current = true;
    setBusy(action);
    try {
      const missing = selected.filter((name) => !files.has(name));
      const selectedResources = new Map(files);
      if (missing.length) {
        const extracted = await packet.extract(missing, true);
        if (!extracted) return;
        extracted.files.forEach((file) => selectedResources.set(file.fileName, {
          fileName: file.fileName,
          fileResourceId: file.fileResource.id,
          columnNames: file.columnNames ?? Object.keys(file.schema),
          nullValue: file.nullValue,
        }));
        setFiles(selectedResources);
      }
      const payload = {
        id: params.id,
        basic,
        content: { target, datapack: {
          packageResourceId: String(resource.id),
          packageName: resource.name,
          packageFingerprint: inspection.packageFingerprint,
          fileNames: inspection.fileNames,
          files: selected.map((name) => selectedResources.get(name)!),
        } },
        schedule: buildSchedulePayload(schedule),
        env,
      };
      if (action === 'preview') {
        const response = await seatunnelJobDefinitionApi.buildGuideMultiConfig(payload);
        if (response.code !== 0) throw new Error(response.message || '预览失败');
        setHocon(response.data);
      } else {
        const response = await seatunnelJobDefinitionApi.saveOrUpdateGuideMulti(payload);
        if (response.code !== 0) throw new Error(response.message || response.msg || '保存配置失败');
        message.success('配置已保存，请返回任务列表上线并运行');
        goBack();
      }
    } catch (error: any) {
      message.error(error?.message || '操作失败');
    } finally {
      submitting.current = false;
      setBusy(undefined);
    }
  };

  const locked = !!busy || !!packet.action;
  return (
    <div className="datapack-multi-config">
      <header className="datapack-multi-config__header">
        <h1><LayoutGrid size={22} />逻辑关系配置（多表导入）</h1>
        <Button icon={<ArrowLeft size={16} />} onClick={goBack} disabled={!!busy}>返回任务列表</Button>
      </header>

      <section className="datapack-multi-config__frame">
        <div className="datapack-multi-config__main">
          <div className="datapack-multi-config__toolbar">
            <h2>任务配置</h2>
            <Space>
              <Button icon={<Eye size={16} />} loading={busy === 'preview'} disabled={locked}
                onClick={() => void submit('preview')}>
                预览
              </Button>
              <Tag color={dirty ? 'warning' : 'success'}>{dirty ? '配置未保存' : '配置已保存'}</Tag>
              <Button type="primary" icon={<Save size={16} />} loading={busy === 'save'} disabled={locked}
                onClick={() => void submit('save')}>
                保存配置
              </Button>
            </Space>
          </div>
          <div className="datapack-multi-config__body">
            <div className="datapack-multi-config__endpoints">
              <DatapackPackageInput config={packet} disabled={!!busy}
                onInspect={() => void inspectPackage()} onSelect={changePackage} />
              <div className="space-y-3">
                <label className="block">
                  <span className="mb-2 block text-sm">目标数据源</span>
                  <Select className="w-full" aria-label="目标数据源" placeholder="请选择目标数据源"
                    value={datasourceId ? String(datasourceId) : undefined}
                    loading={datasources.loading} disabled={locked} options={datasourceOptions}
                    showSearch optionFilterProp="label"
                    onChange={(value) => updateTarget({ datasourceId: value, database: undefined, schemaName: undefined })}
                  />
                </label>
                <label className="block">
                  <span className="mb-2 block text-sm">目标数据库</span>
                  <Select className="w-full" aria-label="目标数据库" placeholder="请选择目标数据库" value={database}
                    loading={databases.loading} disabled={!canUseDatasource || locked} options={databaseOptions}
                    showSearch optionFilterProp="label"
                    onChange={(value) => updateTarget({ database: value, schemaName: undefined })}
                  />
                </label>
                {schemaScoped && (
                  <label className="block">
                    <span className="mb-2 block text-sm">目标 Schema</span>
                    <Select className="w-full" aria-label="目标 Schema" placeholder="请选择目标 Schema" value={schemaName}
                      loading={schemas.loading} disabled={!canUseDatabase || locked} options={schemaOptions}
                      showSearch optionFilterProp="label"
                      onChange={(value) => updateTarget({ schemaName: value })}
                    />
                  </label>
                )}
              </div>
            </div>
            {error && <Alert type="error" showIcon message={error} />}
            {!saved?.files && saved?.mappings && (
              <Alert type="info" showIcon message="重新保存后，已选 CSV 将按文件名和表头导入同名表。" />
            )}
            <TableTransferPanel
              key={inspection?.packageFingerprint}
              files
              matchMode="1"
              loading={!!packet.action}
              disabled={!inspection || locked}
              data={(inspection?.fileNames ?? []).map((name) => ({ key: name, rawTitle: name, title: '' }))}
              targetKeys={selected}
              description="按 CSV 文件名和表头创建同名表，字段统一使用 String"
              onChange={(names) => {
                setSelected(names);
                setDirty(true);
              }}
            />
          </div>
        </div>

        <aside className={activeTab ? 'datapack-multi-config__settings is-open' : 'datapack-multi-config__settings'}>
          <RightConfigPanel
            activeTab={activeTab}
            onTabChange={setActiveTab}
            contentDisabled={!!busy}
            basicConfig={basic}
            setBasicConfig={(update) => { setBasic(update); setDirty(true); }}
            scheduleConfig={schedule}
            setScheduleConfig={(update) => { setSchedule(update); setDirty(true); }}
            envConfig={env}
            setEnvConfig={(update) => { setEnv(update); setDirty(true); }}
          />
        </aside>
      </section>

      <Modal open={hocon !== undefined} title="任务配置预览" footer={null} width={960} onCancel={() => setHocon(undefined)}>
        {hocon !== undefined && <CodeBlockWithCopy content={hocon} height={500} />}
      </Modal>
    </div>
  );
};

export default DatapackMultiConfig;
