import { Form, type FormInstance, message } from 'antd';
import { debounce } from 'lodash';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  dataSourceCatalogApi,
  fetchDataSourceOptions,
} from '@/pages/data-source/service';
import { supportsSchemaScope } from '@/pages/data-source/dataSourceRegistry';
import { seatunnelStremJobDefinitionApi } from '@/pages/stream-link-up/api';

import {
  buildTableItems,
  DEFAULT_FORM_VALUES,
  DEFAULT_SOURCE_TYPE,
  DEFAULT_TARGET_TYPE,
  validateServerIdRange,
} from '../config';
import type { DbTypeValue, TableItem } from '../types';

import {
  markJobDefinitionSynced,
  normalizeJobDefinitionState,
  type JobDefinitionState,
} from "../jobDefinitionState";

interface UseMultiWorkflowStateProps {
  form: FormInstance;
  params: any;
  setParams: React.Dispatch<React.SetStateAction<any>>;
  basicConfig: any;
  scheduleConfig: any;
  envConfig: any;
}

const resolveWorkflow = (params?: any) => {
  return params?.workflow || params?.content || params?.jobDefinitionInfo || {};
};

const resolveSourceType = (params?: any, fallback?: any) => {
  const workflow = resolveWorkflow(params);

  if (params?.sourceType) return params.sourceType;
  if (workflow?.sourceType) return workflow.sourceType;
  if (workflow?.source?.dbType) {
    return {
      dbType: workflow?.source?.dbType,
      connectorType: workflow?.source?.connectorType,
      pluginName: workflow?.source?.pluginName,
    };
  }

  return fallback || DEFAULT_SOURCE_TYPE;
};

const resolveTargetType = (params?: any, fallback?: any) => {
  const workflow = resolveWorkflow(params);

  if (params?.targetType) return params.targetType;
  if (workflow?.targetType) return workflow.targetType;
  if (workflow?.target?.dbType) {
    return {
      dbType: workflow?.target?.dbType,
      connectorType: workflow?.target?.connectorType,
      pluginName: workflow?.target?.pluginName,
    };
  }

  return fallback || DEFAULT_TARGET_TYPE;
};

const stableStringify = (value: any) => {
  try {
    return JSON.stringify(value ?? {});
  } catch (_error) {
    return '';
  }
};

const isMySqlCdc = (sourceType?: DbTypeValue) =>
  sourceType?.pluginName?.toUpperCase() === 'MYSQL-CDC';

const isPostgreSqlCdc = (sourceType?: DbTypeValue) =>
  sourceType?.pluginName?.toUpperCase() === 'POSTGRESQL-CDC';

/** PG/Oracle/达梦等来源的 catalog 会返回 schema.table，取第一段作为 schema。 */
const resolveTableSchema = (tableKey?: string) => {
  const parts = String(tableKey || '').split('.');
  return parts.length > 1 ? parts[0] : '';
};

const filterTablesBySchema = (tables: TableItem[], schema?: string) =>
  schema ? tables.filter((item) => resolveTableSchema(item.key) === schema) : tables;

const listTableSchemas = (tables: TableItem[]) =>
  Array.from(
    new Set(tables.map((item) => resolveTableSchema(item.key)).filter(Boolean)),
  );

function useTargetCatalogOptions(kind: 'databases' | 'schemas', sinkId?: string, database = '') {
  const [options, setOptions] = useState<{ label: string; value: string }[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setOptions([]);
    if (!sinkId || (kind === 'schemas' && !database)) {
      setLoading(false);
      return;
    }

    let active = true;
    setLoading(true);
    const request = kind === 'databases'
      ? dataSourceCatalogApi.listDatabases(sinkId)
      : dataSourceCatalogApi.listSchemas(sinkId, database);
    request.then((response) => {
      if (!active) return;
      if (response.code !== 0) throw new Error(response.message);
      setOptions(response.data.map((item) => ({
        label: String(item.label ?? item.value),
        value: String(item.value),
      })));
    }).catch((error) => {
      if (!active) return;
      console.error('加载实时任务目标目录失败', { kind, sinkId, database }, error);
      message.error(kind === 'databases' ? '获取目标数据库失败' : '获取目标 Schema 失败');
    }).finally(() => {
      if (active) setLoading(false);
    });

    // Ignore results from a previous destination or database.
    return () => { active = false; };
  }, [kind, sinkId, database]);

  return { options, loading };
}

type SaveResponseData = {
  id?: number | string;
  state?: JobDefinitionState;
};

const getSaveResponseData = (res: any): SaveResponseData => {
  const data = res?.data;

  if (data && typeof data === "object") {
    return {
      id:
        data.id ??
        data.jobDefineId ??
        data.jobDefinitionId ??
        data.definitionId,
      state: data.state,
    };
  }

  return {
    id: data,
    state: undefined,
  };
};

const syncStreamSessionCache = (id: number | string | undefined, data: any) => {
  if (!id) return;

  try {
    sessionStorage.setItem(`stream-link-up-detail-${id}`, JSON.stringify(data));
  } catch (error) {
    console.warn("Update stream multi workflow session cache failed", error);
  }
};

export function useMultiWorkflowState({
  form,
  params,
  setParams,
  basicConfig,
  scheduleConfig,
  envConfig,
}: UseMultiWorkflowStateProps) {
  const [activeTab, setActiveTab] = useState<any>(null);

  const [loading, setLoading] = useState(false);

  const [sourceType] = useState<DbTypeValue>(
    resolveSourceType(params, DEFAULT_SOURCE_TYPE),
  );
  const [targetType] = useState<DbTypeValue>(
    resolveTargetType(params, DEFAULT_TARGET_TYPE),
  );
  const targetSchemaScoped = supportsSchemaScope(targetType?.dbType);
  const sinkId = Form.useWatch('sinkId', form);
  const targetDatabase = Form.useWatch<string>('targetDatabase', form);
  const targetSchemaName = Form.useWatch<string>('targetSchemaName', form);
  const catalogSinkId = targetSchemaScoped && sinkId ? String(sinkId) : undefined;
  const targetDatabases = useTargetCatalogOptions('databases', catalogSinkId);
  const targetSchemas = useTargetCatalogOptions('schemas', catalogSinkId, targetDatabase);

  const [sourceOption, setSourceOption] = useState<any[]>([]);
  const [targetOption, setTargetOption] = useState<any[]>([]);
  const [currentSourceId, setCurrentSourceId] = useState('');

  const [tableData, setTableData] = useState<TableItem[]>([]);
  // 保留完整表列表，schema 切换时在本地过滤，避免重复请求 catalog。
  const [allTableData, setAllTableData] = useState<TableItem[]>([]);
  const [schemaOptions, setSchemaOptions] = useState<string[]>([]);
  const [selectedSchema, setSelectedSchema] = useState('');
  const [readOnlyTables, setReadOnlyTables] = useState<TableItem[]>([]);
  const [multiTableList, setMultiTableList] = useState<string[]>([]);
  const [matchMode, setMatchMode] = useState<string>(
    DEFAULT_FORM_VALUES.matchMode,
  );
  const [tableKeyword, setTableKeyword] = useState('');

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewContent, setPreviewContent] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);

  const [publishedJobDefineId, setPublishedJobDefineId] = useState<
    number | string | undefined
  >(params?.id);

  const [publishLoading, setPublishLoading] = useState(false);
  const [runLoading, setRunLoading] = useState(false);

  const initializingRef = useRef(false);
  const baselineSignatureRef = useRef('');

  useEffect(() => {
    if (params?.id) {
      setPublishedJobDefineId(params.id);
    }
  }, [params?.id]);

  const fetchDataSourceOptionsU = useCallback(async (
    dbType: string,
    usage: "SOURCE" | "SINK",
    pluginName?: string,
  ) => {
    const res = await fetchDataSourceOptions(dbType, usage, pluginName);
    if (res?.code === 0 && Array.isArray(res?.data)) {
      return res.data;
    }
    return [];
  }, []);

  const fetchTables = useCallback(
    async (dataSourceId: string, mode?: string, preferredSchema?: string) => {
      if (!dataSourceId) return;

      try {
        setLoading(true);
        setCurrentSourceId(dataSourceId);

        const res = await dataSourceCatalogApi.listTable(dataSourceId);
        if (res?.code === 0) {
          const nextTables = buildTableItems(res.data || []);
          setAllTableData(nextTables);

          const schemas = listTableSchemas(nextTables);
          setSchemaOptions(schemas);

          // 仅整库同步对 PG/Oracle/达梦等 schema 层来源启用过滤；
          // 自定义模式仍展示全部 schema 的表，避免隐藏跨 schema 的可选表。
          const schemaScoped = supportsSchemaScope(sourceType?.dbType);
          const filterBySchema = schemaScoped && mode === '4';
          const nextSchema = filterBySchema
            ? preferredSchema && schemas.includes(preferredSchema)
              ? preferredSchema
              : schemas.includes('public')
                ? 'public'
                : schemas[0] || ''
            : '';
          setSelectedSchema(nextSchema);

          const visibleTables = filterBySchema
            ? filterTablesBySchema(nextTables, nextSchema)
            : nextTables;
          setTableData(visibleTables);

          if (mode === '4') {
            setMultiTableList(visibleTables.map((item) => item.key));
          } else if (mode === '1') {
            setMultiTableList([]);
          }
        } else {
          message.error(res?.message || '获取表列表失败');
        }
      } catch (error) {
        console.error(error);
        message.error('获取表列表失败');
      } finally {
        setLoading(false);
      }
    },
    [sourceType?.dbType],
  );

  const fetchReferenceTables = useCallback(
    async (dataSourceId: string, mode: string, keyword?: string) => {
      if (!dataSourceId) return;

      try {
        setLoading(true);

        const res = await dataSourceCatalogApi.listTableReference(
          dataSourceId,
          mode,
          keyword,
        );

        if (res?.code === 0) {
          setReadOnlyTables(buildTableItems(res.data || []));
        } else {
          message.error(res?.message || '获取参考表失败');
        }
      } catch (error) {
        console.error(error);
        message.error('获取参考表失败');
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  const debouncedFetchReferenceTables = useMemo(
    () =>
      debounce((dataSourceId: string, mode: string, keyword: string) => {
        fetchReferenceTables(dataSourceId, mode, keyword);
      }, 400),
    [fetchReferenceTables],
  );

  const buildWorkflowData = useCallback(() => {
    const formValues = form.getFieldsValue();
    const mysqlCdc = isMySqlCdc(sourceType);
    const postgreSqlCdc = isPostgreSqlCdc(sourceType);
    const mysqlSource =
      String(sourceType?.dbType || '').toUpperCase() === 'MYSQL';
    // PostgreSQL 兼容目标把 tinyint(1) 映射为 boolean，而运行期值可能是数值；
    // 对 MySQL 来源显式关闭窄化，保证源 schema 与目标 DDL 都保持数值类型。
    const postgresCompatibleTarget = [
      'POSTGRE_SQL',
      'VASTBASE',
      'KINGBASE',
      'ZEONEDB',
    ].includes(String(targetType?.dbType || '').toUpperCase());
    const needsNumericTinyint = mysqlSource && postgresCompatibleTarget;

    return {
      type: 'GUIDE_MULTI',
      source: {
        dbType: sourceType?.dbType,
        connectorType: sourceType?.connectorType,
        datasourceId: formValues.sourceId,
        pluginName: sourceType?.pluginName,
        schemaName: selectedSchema || undefined,
        fetchSize: formValues.fetchSize,
        splitSize: formValues.splitSize,
        ...(needsNumericTinyint ? { intTypeNarrowing: false } : {}),
        ...(mysqlCdc ? { serverIdMode: formValues.serverIdMode } : {}),
        ...(mysqlCdc && formValues.serverId
          ? { 'server-id': String(formValues.serverId).trim() }
          : {}),
        ...(postgreSqlCdc && formValues.slotName
          ? { 'slot.name': String(formValues.slotName).trim() }
          : {}),
        ...(postgreSqlCdc && formValues.publicationName
          ? { publicationName: String(formValues.publicationName).trim() }
          : {}),
        ...(mysqlCdc || postgreSqlCdc
          ? {
              'startup.mode': formValues.startupMode || 'initial',
              serverTimeZone:
                String(formValues.serverTimeZone || '').trim() || undefined,
            }
          : {}),
      },
      target: {
        dbType: targetType?.dbType,
        connectorType: targetType?.connectorType,
        datasourceId: formValues.sinkId,
        database: targetSchemaScoped ? targetDatabase : undefined,
        schemaName: targetSchemaScoped ? targetSchemaName : undefined,
        pluginName: targetType?.pluginName,
        dataSaveMode: formValues.dataSaveMode,
        batchSize: formValues.batchSize,
        schemaSaveMode: formValues.schemaSaveMode,
        enableUpsert: formValues.enableUpsert,
        fieldIde: formValues.fieldIde,
      },
      tableMatch: {
        mode: matchMode,
        tables:
          matchMode === '1' || matchMode === '4' ? multiTableList : undefined,
        keyword:
          matchMode === '2' || matchMode === '3' ? tableKeyword : undefined,
      },
    };
  }, [form, sourceType, targetType, matchMode, multiTableList, tableKeyword, selectedSchema,
    sinkId, targetSchemaScoped, targetDatabase, targetSchemaName]);

  const buildFinalPayload = useCallback(() => {
    return {
      id: params?.id ?? publishedJobDefineId,
      basic: {
        ...basicConfig,
        mode: 'GUIDE_MULTI',
      },
      content: buildWorkflowData(),
      schedule: {
        ...scheduleConfig,
      },
      env: {
        ...envConfig,
      },
    };
  }, [
    params?.id,
    publishedJobDefineId,
    basicConfig,
    scheduleConfig,
    envConfig,
    buildWorkflowData,
  ]);

  const currentSignature = useMemo(() => {
    return stableStringify({
      basic: {
        ...basicConfig,
        mode: 'GUIDE_MULTI',
      },
      content: buildWorkflowData(),
      schedule: scheduleConfig,
      env: envConfig,
    });
  }, [basicConfig, scheduleConfig, envConfig, buildWorkflowData]);

  const isDirty =
    !!publishedJobDefineId &&
    !!baselineSignatureRef.current &&
    currentSignature !== baselineSignatureRef.current;

  useEffect(() => {
    let mounted = true;

    const init = async () => {
      try {
        initializingRef.current = true;

        const workflow = resolveWorkflow(params);

        const nextSourceType = resolveSourceType(params, sourceType);
        const nextTargetType = resolveTargetType(params, targetType);

        const sourceDbType = nextSourceType?.dbType || 'MYSQL';
        const targetDbType = nextTargetType?.dbType || 'MYSQL';

        const [sourceOptions, targetOptions] = await Promise.all([
          fetchDataSourceOptionsU(sourceDbType, "SOURCE", nextSourceType?.pluginName),
          fetchDataSourceOptionsU(targetDbType, "SINK", nextTargetType?.pluginName),
        ]);

        if (!mounted) return;

        setSourceOption(sourceOptions);
        setTargetOption(targetOptions);

        const sourceId = Number(
          params?.sourceDataSourceId ||
            workflow?.sourceDataSourceId ||
            workflow?.sourceId ||
            workflow?.source?.datasourceId,
        );

        const sinkId = Number(
          params?.targetDataSourceId ||
            workflow?.targetDataSourceId ||
            workflow?.targetId ||
            workflow?.target?.datasourceId,
        );

        const nextMatchMode =
          workflow?.tableMatch?.mode || DEFAULT_FORM_VALUES.matchMode;

        const nextKeyword = workflow?.tableMatch?.keyword || '';
        const nextMultiTableList = workflow?.tableMatch?.tables || [];

        setMatchMode(nextMatchMode);
        setTableKeyword(nextKeyword);
        setCurrentSourceId(String(sourceId || ''));

        form.setFieldsValue({
          sourceId,
          sinkId,
          targetDatabase: workflow?.target?.database || undefined,
          targetSchemaName: workflow?.target?.schemaName || workflow?.target?.schema || undefined,
          matchMode: nextMatchMode,
          sourceTable: nextKeyword,

          fetchSize:
            workflow?.source?.fetchSize ?? DEFAULT_FORM_VALUES.fetchSize,

          serverIdMode:
            workflow?.source?.serverIdMode ?? DEFAULT_FORM_VALUES.serverIdMode,

          serverId:
            workflow?.source?.['server-id'] ??
            workflow?.source?.serverId ??
            DEFAULT_FORM_VALUES.serverId,

          slotName: workflow?.source?.['slot.name'] ?? workflow?.source?.slotName,
          publicationName: workflow?.source?.publicationName,
          startupMode:
            workflow?.source?.['startup.mode'] ??
            workflow?.source?.startupMode ??
            'initial',
          serverTimeZone:
            workflow?.source?.serverTimeZone ??
            workflow?.source?.['server-time-zone'] ??
            '',

          splitSize:
            workflow?.source?.splitSize ?? DEFAULT_FORM_VALUES.splitSize,

          schemaSaveMode:
            workflow?.target?.schemaSaveMode ??
            DEFAULT_FORM_VALUES.schemaSaveMode,

          dataSaveMode:
            workflow?.target?.dataSaveMode ?? DEFAULT_FORM_VALUES.dataSaveMode,

          batchSize:
            workflow?.target?.batchSize ?? DEFAULT_FORM_VALUES.batchSize,

          enableUpsert:
            workflow?.target?.enableUpsert ?? DEFAULT_FORM_VALUES.enableUpsert,

          fieldIde: workflow?.target?.fieldIde ?? DEFAULT_FORM_VALUES.fieldIde,
        });

        if (sourceId) {
          if (nextMatchMode === '1') {
            await fetchTables(
              String(sourceId),
              '1',
              resolveTableSchema(nextMultiTableList[0]),
            );
            if (mounted) {
              setMultiTableList(nextMultiTableList);
            }
          } else if (nextMatchMode === '4') {
            await fetchTables(
              String(sourceId),
              '4',
              resolveTableSchema(nextMultiTableList[0]),
            );
          } else if (nextMatchMode === '2' || nextMatchMode === '3') {
            if (nextKeyword) {
              await fetchReferenceTables(
                String(sourceId),
                nextMatchMode,
                nextKeyword,
              );
            }
          }
        }
      } finally {
        if (mounted) {
          initializingRef.current = false;
        }
      }
    };

    init();

    return () => {
      mounted = false;
      debouncedFetchReferenceTables.cancel();
    };
  }, [
    params?.id,
    form,
    sourceType,
    targetType,
    fetchDataSourceOptionsU,
    fetchTables,
    fetchReferenceTables,
    debouncedFetchReferenceTables,
  ]);

  useEffect(() => {
    if (!params?.id && !publishedJobDefineId) {
      baselineSignatureRef.current = '';
      return;
    }

    if (!initializingRef.current && !baselineSignatureRef.current) {
      baselineSignatureRef.current = currentSignature;
    }
  }, [params?.id, publishedJobDefineId, currentSignature]);

  const resetBaseline = useCallback(() => {
    baselineSignatureRef.current = stableStringify({
      basic: {
        ...basicConfig,
        mode: 'GUIDE_MULTI',
      },
      content: buildWorkflowData(),
      schedule: scheduleConfig,
      env: envConfig,
    });
  }, [basicConfig, scheduleConfig, envConfig, buildWorkflowData]);

  const handleSourceIdChange = async (value: string) => {
    setCurrentSourceId(value);

    if (matchMode === '1' || matchMode === '4') {
      await fetchTables(value, matchMode);
      setReadOnlyTables([]);
      return;
    }

    setAllTableData([]);
    setSchemaOptions([]);
    setSelectedSchema('');
    if (tableKeyword) {
      await fetchReferenceTables(value, matchMode, tableKeyword);
    }
  };

  /** 整库同步切换 schema：本地过滤表列表，并同步全选结果。 */
  const handleSchemaChange = (schema: string) => {
    setSelectedSchema(schema);
    const visibleTables = filterTablesBySchema(allTableData, schema);
    setTableData(visibleTables);

    if (matchMode === '4') {
      setMultiTableList(visibleTables.map((item) => item.key));
      return;
    }

    const visibleKeys = new Set(visibleTables.map((item) => item.key));
    setMultiTableList((prev) => prev.filter((key) => visibleKeys.has(key)));
  };

  const handleMatchModeChange = async (value: string) => {
    setMatchMode(value);
    form.setFieldValue('matchMode', value);

    if (!currentSourceId) return;

    if (value === '1' || value === '4') {
      setReadOnlyTables([]);
      await fetchTables(currentSourceId, value);
      return;
    }

    setTableData([]);
    setAllTableData([]);
    setSchemaOptions([]);
    setSelectedSchema('');
    setMultiTableList([]);

    if (tableKeyword) {
      await fetchReferenceTables(currentSourceId, value, tableKeyword);
    } else {
      setReadOnlyTables([]);
    }
  };

  const handleKeywordChange = (value: string) => {
    const keyword = value.trim();

    setTableKeyword(keyword);

    if (!keyword) {
      setReadOnlyTables([]);
      return;
    }

    if (!currentSourceId) return;

    debouncedFetchReferenceTables(currentSourceId, matchMode, keyword);
  };

  const validateBeforeSubmit = async () => {
    await form.validateFields();

    if (matchMode === '1' && (!multiTableList || multiTableList.length === 0)) {
      message.warning('请选择至少一个表');
      return false;
    }

    const sourceId = form.getFieldValue('sourceId');
    const sinkId = form.getFieldValue('sinkId');
    const serverIdMode = form.getFieldValue('serverIdMode');
    const serverId = form.getFieldValue('serverId');

    if (isMySqlCdc(sourceType) && serverIdMode === 'MANUAL') {
      const result = validateServerIdRange(serverId);
      if (!result.valid) {
        message.warning(result.message);
        return false;
      }
    }

    if (sourceId && sinkId && sourceId === sinkId) {
      message.warning('来源和目标数据源不能相同');
      return false;
    }

    return true;
  };

 const handleSave = async () => {
  try {
    const pass = await validateBeforeSubmit();
    if (!pass) return;

    setPublishLoading(true);

    const workflowData = buildWorkflowData();
    const finalPayload = {
      ...buildFinalPayload(),
      content: workflowData,
    };

    const res =
      await seatunnelStremJobDefinitionApi.saveOrUpdateGuideMulti(
        finalPayload
      );

    if (res?.code !== 0) {
      return;
    }

    const saveData = getSaveResponseData(res);
    const jobDefineId = saveData.id ?? finalPayload.id;

    if (!jobDefineId) {
      message.error("发布成功但未返回任务定义ID");
      return;
    }

    setPublishedJobDefineId(jobDefineId);

    setParams((prev: any) => {
      const nextState = saveData.state
        ? normalizeJobDefinitionState(saveData.state)
        : markJobDefinitionSynced(prev?.state);

      const nextParams = {
        ...(prev || {}),
        id: jobDefineId,
        state: nextState,

        workflow: workflowData,
        content: workflowData,

        sourceDataSourceId: workflowData.source.datasourceId,
        targetDataSourceId: workflowData.target.datasourceId,

        scheduleConfig,
        schedule: finalPayload.schedule,

        env: envConfig,
      };

      syncStreamSessionCache(prev?.id, nextParams);
      syncStreamSessionCache(jobDefineId, nextParams);

      return nextParams;
    });

    baselineSignatureRef.current = stableStringify({
      basic: finalPayload.basic,
      content: workflowData,
      schedule: finalPayload.schedule,
      env: finalPayload.env,
    });

    message.success("配置已保存，请返回任务列表上线并运行。", 5);
  } catch (error: any) {
    console.error(error);
    message.error(error?.message || "发布失败");
  } finally {
    setPublishLoading(false);
  }
};

  const handlePreview = async () => {
    try {
      const pass = await validateBeforeSubmit();
      if (!pass) return;

      setPreviewLoading(true);

      const finalPayload = buildFinalPayload();
      const res =
        await seatunnelStremJobDefinitionApi.buildGuideMultiConfig(
          finalPayload,
        );

      setPreviewContent(res?.data || '');
      setPreviewOpen(true);
    } catch (error: any) {
      console.error(error);
      message.error(error?.message || '预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  const canRun =
    !!publishedJobDefineId && !isDirty && !publishLoading && !runLoading;

  const runDisabledReason = !publishedJobDefineId
    ? '请先发布任务，再执行'
    : isDirty
      ? '当前内容已变更，请重新发布后再执行'
      : '';

  const handleRun = async () => {
    const pass = await validateBeforeSubmit();
    if (!pass) return;

    if (!publishedJobDefineId) {
      message.warning('请先发布任务，再执行');
      return;
    }

    if (isDirty) {
      message.warning('当前内容已变更，请重新发布后再执行');
      return;
    }

    try {
      setRunLoading(true);

      // TODO: 后续接入真正执行接口 / RunLog。
      // await seatunnelJobDefinitionApi.execute(publishedJobDefineId);

      // message.success("运行校验通过，可继续接入执行逻辑");
    } catch (error: any) {
      console.error(error);
      message.error(error?.message || '运行失败');
    } finally {
      setRunLoading(false);
    }
  };

  return {
    activeTab,
    setActiveTab,

    loading,

    sourceOption,
    targetOption,
    sourceType,
    targetSchemaScoped,
    targetDatabases,
    targetSchemas,

    tableData,
    schemaOptions,
    selectedSchema,
    readOnlyTables,
    multiTableList,
    setMultiTableList,

    matchMode,
    tableKeyword,

    previewOpen,
    setPreviewOpen,
    previewContent,
    previewLoading,

    publishedJobDefineId,
    publishLoading,
    runLoading,
    isDirty,
    canRun,
    runDisabledReason,

    handleSourceIdChange,
    handleSchemaChange,
    handleMatchModeChange,
    handleKeywordChange,
    handleSave,
    handlePreview,
    handleRun,

    buildFinalPayload,
    resetBaseline,
  };
}
