import { FormInstance, message } from "antd";
import { debounce } from "lodash";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  dataSourceCatalogApi,
  fetchDataSourceOptions,
} from "@/pages/data-source/service";
import {
  supportsDatabaseScope,
  supportsSchemaScope,
} from "@/pages/data-source/dataSourceRegistry";
import { fetchReadyOdsDatabases } from "@/pages/lake/physical/service";

import { seatunnelJobDefinitionApi } from "@/pages/batch-link-up/api";
import {
  buildSchedulePayload,
  getScheduleValidationMessage,
} from "@/pages/batch-link-up/workflow/components/ScheduleConfigContent/types";
import {
  buildTableItems,
  DEFAULT_DB_TYPE,
  DEFAULT_FORM_VALUES,
} from "../config";
import { DbTypeValue, RightPanelTab, TableItem } from "../types";
import { JobDefinitionState, markJobDefinitionSynced, normalizeJobDefinitionState } from "../jobDefinitionState";

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

  return fallback || DEFAULT_DB_TYPE;
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

  return fallback || DEFAULT_DB_TYPE;
};

const stableStringify = (value: any) => {
  try {
    return JSON.stringify(value ?? {});
  } catch (error) {
    return "";
  }
};

type SaveResponseData = {
  id?: number | string;
  state?: JobDefinitionState;
};

const getSaveResponseData = (res: any): SaveResponseData => {
  const data = res?.data;

  /**
   * 兼容新版后端返回：
   * data = {
   *   id: 123,
   *   state: {
   *     editorSyncState: "SYNCED",
   *     releaseState: "OFFLINE",
   *     jobVersion: 1,
   *     contentVersion: 1
   *   }
   * }
   */
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

  /**
   * 兼容旧版后端返回：
   * data = 123
   */
  return {
    id: data,
    state: undefined,
  };
};

const syncBatchSessionCache = (id: number | string | undefined, data: any) => {
  if (!id) return;

  try {
    sessionStorage.setItem(`batch-link-up-detail-${id}`, JSON.stringify(data));
  } catch (error) {
    console.warn("Update batch multi workflow session cache failed", error);
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
    resolveSourceType(params, DEFAULT_DB_TYPE)
  );
  const [targetType] = useState<DbTypeValue>(
    resolveTargetType(params, DEFAULT_DB_TYPE)
  );

  const [sourceOption, setSourceOption] = useState<any[]>([]);
  const [targetOption, setTargetOption] = useState<any[]>([]);
  const [currentSourceId, setCurrentSourceId] = useState("");
  const [currentTargetId, setCurrentTargetId] = useState("");
  const [sourceDatabaseOptions, setSourceDatabaseOptions] = useState<any[]>([]);
  const [targetDatabaseOptions, setTargetDatabaseOptions] = useState<any[]>([]);
  const [sourceSchemaOptions, setSourceSchemaOptions] = useState<any[]>([]);
  const [targetSchemaOptions, setTargetSchemaOptions] = useState<any[]>([]);
  const [sourceDatabase, setSourceDatabase] = useState("");
  const [targetDatabase, setTargetDatabase] = useState("");
  const [sourceSchemaName, setSourceSchemaName] = useState("");
  const [targetSchemaName, setTargetSchemaName] = useState("");
  const [targetOdsDatabaseBindingId, setTargetOdsDatabaseBindingId] = useState<
    number | undefined
  >();
  const [databaseLoading, setDatabaseLoading] = useState(false);
  const [schemaLoading, setSchemaLoading] = useState(false);

  const [tableData, setTableData] = useState<TableItem[]>([]);
  const [readOnlyTables, setReadOnlyTables] = useState<TableItem[]>([]);
  const [multiTableList, setMultiTableList] = useState<string[]>([]);
  const [matchMode, setMatchMode] = useState<string>(
    DEFAULT_FORM_VALUES.matchMode
  );
  const [tableKeyword, setTableKeyword] = useState("");

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewContent, setPreviewContent] = useState("");
  const [previewLoading, setPreviewLoading] = useState(false);

  const [publishedJobDefineId, setPublishedJobDefineId] = useState<
    number | string | undefined
  >(params?.id);

  const [publishLoading, setPublishLoading] = useState(false);
  const [runLoading, setRunLoading] = useState(false);

  const initializingRef = useRef(false);
  const baselineSignatureRef = useRef("");

  useEffect(() => {
    if (params?.id) {
      setPublishedJobDefineId(params.id);
    }
  }, [params?.id]);

  const fetchDataSourceOptionsU = useCallback(async (dbType: string) => {
    const res = await fetchDataSourceOptions(dbType);
    if (res?.code === 0 && Array.isArray(res?.data)) {
      return res.data;
    }
    return [];
  }, []);

  const fetchDatabaseOptions = useCallback(
    async (
      dataSourceId: string,
      target = false,
      sourceId?: string,
      systemManaged = false,
    ) => {
      if (!dataSourceId) return [];

      if (target && systemManaged && !sourceId) {
        return [];
      }

      const response = target && systemManaged && sourceId
        ? await fetchReadyOdsDatabases(Number(sourceId))
        : await dataSourceCatalogApi.listDatabases(dataSourceId);
      if (response?.code !== 0) {
        throw new Error(response?.message || "获取数据库失败");
      }

      const list = Array.isArray(response.data) ? response.data : [];
      return systemManaged && target
        ? list.map((item: any) => ({
            label: item?.databaseName,
            value: String(item?.databaseName ?? ""),
            description: item?.resourceStatus,
            odsDatabaseBindingId: item?.id,
          }))
        : list.map((item: any) => ({
            label: item?.label ?? item?.value,
            value: String(item?.value ?? ""),
            description: item?.description,
          }));
    },
    [],
  );

  const fetchSchemaOptions = useCallback(async (dataSourceId: string, databaseName: string) => {
    if (!dataSourceId || !databaseName) return [];

    const response = await dataSourceCatalogApi.listSchemas(dataSourceId, databaseName);
    if (response?.code !== 0) {
      throw new Error(response?.message || "获取 Schema 失败");
    }

    const list = Array.isArray(response.data) ? response.data : [];
    return list.map((item: any) => ({
      label: item?.label ?? item?.value,
      value: String(item?.value ?? ""),
      description: item?.description,
    }));
  }, []);

  const fetchScopedTables = useCallback(async (
    dataSourceId: string,
    databaseName?: string,
    schemaName?: string,
  ) => {
    if (!databaseName) return [];
    const tableResponse = await dataSourceCatalogApi.listTablesByDatabase(
      dataSourceId,
      databaseName,
      schemaName,
    );
    if (tableResponse?.code !== 0) return [];

    const tables = Array.isArray(tableResponse.data) ? tableResponse.data : [];
    return tables.map((table: any) => {
      const tableName = String(table?.value ?? "");
      return {
        value: tableName,
        label: tableName,
        description: table?.description,
      };
    });
  }, []);

  const fetchTables = useCallback(
    async (
      dataSourceId: string,
      mode?: string,
      databaseOverride?: string,
      schemaOverride?: string,
    ) => {
      if (!dataSourceId) return;

      try {
        setLoading(true);
        setCurrentSourceId(dataSourceId);

        const res = supportsDatabaseScope(sourceType?.dbType)
          ? {
              code: 0,
              data: await fetchScopedTables(
                dataSourceId,
                databaseOverride,
                schemaOverride,
              ),
            }
          : await dataSourceCatalogApi.listTable(dataSourceId);
        if (res?.code === 0) {
          const nextTables = buildTableItems(res.data || []);
          setTableData(nextTables);

          if (mode === "4") {
            setMultiTableList(nextTables.map((item) => item.key));
          } else if (mode === "1") {
            setMultiTableList([]);
          }
        } else {
          message.error(res?.message || "获取表列表失败");
        }
      } catch (error) {
        console.error(error);
        message.error("获取表列表失败");
      } finally {
        setLoading(false);
      }
    },
    [fetchScopedTables, sourceType]
  );

  const fetchReferenceTables = useCallback(
    async (
      dataSourceId: string,
      mode: string,
      keyword?: string,
      databaseOverride?: string,
      schemaOverride?: string,
    ) => {
      if (!dataSourceId) return;

      try {
        setLoading(true);

        const res = supportsDatabaseScope(sourceType?.dbType)
          ? {
              code: 0,
              data: (await fetchScopedTables(
                dataSourceId,
                databaseOverride,
                schemaOverride,
              )).filter((item: any) => {
                const value = String(item?.value || "");
                if (mode === "2") return value.match(keyword || "");
                if (mode === "3") {
                  return String(keyword || "")
                    .split(",")
                    .some((exact) => exact.trim() === value);
                }
                return true;
              }),
            }
          : await dataSourceCatalogApi.listTableReference(dataSourceId, mode, keyword);

        if (res?.code === 0) {
          setReadOnlyTables(buildTableItems(res.data || []));
        } else {
          message.error(res?.message || "获取参考表失败");
        }
      } catch (error) {
        console.error(error);
        message.error("获取参考表失败");
      } finally {
        setLoading(false);
      }
    },
    [fetchScopedTables, sourceType]
  );

  const debouncedFetchReferenceTables = useMemo(
    () =>
      debounce((
        dataSourceId: string,
        mode: string,
        keyword: string,
        database?: string,
        schema?: string,
      ) => {
        fetchReferenceTables(dataSourceId, mode, keyword, database, schema);
      }, 400),
    [fetchReferenceTables]
  );

  const buildWorkflowData = useCallback(() => {
    const formValues = form.getFieldsValue();

    return {
      type: "GUIDE_MULTI",
      source: {
        dbType: sourceType?.dbType,
        connectorType: sourceType?.connectorType,
        datasourceId: formValues.sourceId,
        pluginName: sourceType?.pluginName,
        database: String(formValues.sourceDatabase || sourceDatabase || "").trim() || undefined,
        schemaName: String(formValues.sourceSchemaName || sourceSchemaName || "").trim() || undefined,
        fetchSize: formValues.fetchSize,
        splitSize: formValues.splitSize,
        partitionColumn: String(formValues.partitionColumn || "").trim() || undefined,
        partitionLowerBound:
          String(formValues.partitionLowerBound || "").trim() || undefined,
        partitionUpperBound:
          String(formValues.partitionUpperBound || "").trim() || undefined,
        partitionNum: formValues.partitionNum,
      },
      target: {
        dbType: targetType?.dbType,
        connectorType: targetType?.connectorType,
        datasourceId: formValues.sinkId,
        pluginName: targetType?.pluginName,
        database: String(formValues.targetDatabase || targetDatabase || "").trim() || undefined,
        schemaName: String(formValues.targetSchemaName || targetSchemaName || "").trim() || undefined,
        odsDatabaseBindingId:
          formValues.odsDatabaseBindingId ?? targetOdsDatabaseBindingId,
        dataSaveMode: formValues.dataSaveMode,
        batchSize: formValues.batchSize,
        schemaSaveMode: formValues.schemaSaveMode,
        enableUpsert: formValues.enableUpsert,
        fieldIde: formValues.fieldIde,
        ...(String(targetType?.dbType || "").toUpperCase() === "DORIS"
          ? {
              dorisEnable2pc: formValues.dorisEnable2pc,
              dorisLabelPrefix:
                String(formValues.dorisLabelPrefix || "").trim() || undefined,
              dorisReplicaCount: formValues.dorisReplicaCount,
            }
          : {}),
      },
      tableMatch: {
        mode: matchMode,
        tables:
          matchMode === "1" || matchMode === "4" ? multiTableList : undefined,
        keyword:
          matchMode === "2" || matchMode === "3" ? tableKeyword : undefined,
      },
    };
  }, [
    form,
    sourceType,
    targetType,
    sourceDatabase,
    targetDatabase,
    sourceSchemaName,
    targetSchemaName,
    targetOdsDatabaseBindingId,
    matchMode,
    multiTableList,
    tableKeyword,
  ]);

  const buildFinalPayload = useCallback(() => {
    return {
      id: params?.id ?? publishedJobDefineId,
      odsDatabaseBindingId: targetOdsDatabaseBindingId,
      basic: {
        ...basicConfig,
        mode: "GUIDE_MULTI",
      },
      content: buildWorkflowData(),
      schedule: buildSchedulePayload(scheduleConfig),
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
    targetOdsDatabaseBindingId,
    buildWorkflowData,
  ]);

  const currentSignature = useMemo(() => {
    return stableStringify({
      basic: {
        ...basicConfig,
        mode: "GUIDE_MULTI",
      },
      content: buildWorkflowData(),
      schedule: buildSchedulePayload(scheduleConfig),
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

        const sourceDbType = nextSourceType?.dbType || "MYSQL";
        const targetDbType = nextTargetType?.dbType || "MYSQL";

        const [sourceOptions, targetOptions] = await Promise.all([
          fetchDataSourceOptionsU(sourceDbType),
          fetchDataSourceOptionsU(targetDbType),
        ]);

        if (!mounted) return;

        setSourceOption(sourceOptions);
        setTargetOption(targetOptions);

        const sourceId = Number(
          params?.sourceDataSourceId ||
            workflow?.sourceDataSourceId ||
            workflow?.sourceId ||
            workflow?.source?.datasourceId
        );

        const sinkId = Number(
          params?.targetDataSourceId ||
            workflow?.targetDataSourceId ||
            workflow?.targetId ||
            workflow?.target?.datasourceId
        );

        const sourceIsDoris = String(sourceDbType).toUpperCase() === "DORIS";
        const targetIsDoris = String(targetDbType).toUpperCase() === "DORIS";
        const sourceIsScoped = supportsDatabaseScope(sourceDbType);
        const targetIsScoped = supportsDatabaseScope(targetDbType);
        const sourceHasSchema = supportsSchemaScope(sourceDbType);
        const targetHasSchema = supportsSchemaScope(targetDbType);
        const targetIsSystemManaged = Boolean(
          targetOptions.find((item: any) => String(item?.value) === String(sinkId))
            ?.systemManaged,
        );

        const nextMatchMode =
          workflow?.tableMatch?.mode || DEFAULT_FORM_VALUES.matchMode;

        const nextKeyword = workflow?.tableMatch?.keyword || "";
        const nextMultiTableList = workflow?.tableMatch?.tables || [];
        const legacyTableParts = String(nextMultiTableList?.[0] || "")
          .split(".")
          .filter(Boolean);
        const legacyDatabaseFromTables = legacyTableParts.length === 2
          ? legacyTableParts[0]
          : "";
        const nextSourceDatabase = String(
          workflow?.source?.database || (sourceIsDoris ? legacyDatabaseFromTables : "") || "",
        );
        const nextTargetDatabase = String(workflow?.target?.database || "");
        const nextSourceSchema = String(
          workflow?.source?.schemaName || workflow?.source?.schema || "",
        );
        const nextTargetSchema = String(
          workflow?.target?.schemaName || workflow?.target?.schema || "",
        );
        const nextBindingId = workflow?.target?.odsDatabaseBindingId
          ?? params?.odsDatabaseBindingId;

        let nextSourceDatabaseOptions: any[] = [];
        let nextTargetDatabaseOptions: any[] = [];
        let nextSourceSchemaOptions: any[] = [];
        let nextTargetSchemaOptions: any[] = [];
        if (sourceIsScoped && sourceId) {
          nextSourceDatabaseOptions = await fetchDatabaseOptions(String(sourceId));
        }
        if (targetIsScoped && sinkId) {
          nextTargetDatabaseOptions = await fetchDatabaseOptions(
            String(sinkId),
            true,
            String(sourceId || ""),
            targetIsDoris && targetIsSystemManaged,
          );
        }
        if (sourceHasSchema && sourceId && nextSourceDatabase) {
          nextSourceSchemaOptions = await fetchSchemaOptions(
            String(sourceId),
            nextSourceDatabase,
          );
        }
        if (targetHasSchema && sinkId && nextTargetDatabase) {
          nextTargetSchemaOptions = await fetchSchemaOptions(
            String(sinkId),
            nextTargetDatabase,
          );
        }

        setMatchMode(nextMatchMode);
        setTableKeyword(nextKeyword);
        setCurrentSourceId(String(sourceId || ""));
        setCurrentTargetId(String(sinkId || ""));
        setSourceDatabaseOptions(nextSourceDatabaseOptions);
        setTargetDatabaseOptions(nextTargetDatabaseOptions);
        setSourceSchemaOptions(nextSourceSchemaOptions);
        setTargetSchemaOptions(nextTargetSchemaOptions);
        setSourceDatabase(nextSourceDatabase);
        setTargetDatabase(nextTargetDatabase);
        setSourceSchemaName(nextSourceSchema);
        setTargetSchemaName(nextTargetSchema);
        setTargetOdsDatabaseBindingId(
          nextBindingId == null ? undefined : Number(nextBindingId),
        );

        form.setFieldsValue({
          sourceId,
          sinkId,
          sourceDatabase: nextSourceDatabase || undefined,
          targetDatabase: nextTargetDatabase || undefined,
          sourceSchemaName: nextSourceSchema || undefined,
          targetSchemaName: nextTargetSchema || undefined,
          odsDatabaseBindingId:
            nextBindingId == null ? undefined : Number(nextBindingId),
          matchMode: nextMatchMode,
          sourceTable: nextKeyword,

          fetchSize:
            workflow?.source?.fetchSize ?? DEFAULT_FORM_VALUES.fetchSize,

          splitSize:
            workflow?.source?.splitSize ?? DEFAULT_FORM_VALUES.splitSize,

          partitionColumn:
            workflow?.source?.partitionColumn ??
            workflow?.source?.partition_column ??
            undefined,

          partitionLowerBound:
            workflow?.source?.partitionLowerBound ??
            workflow?.source?.partition_lower_bound ??
            undefined,

          partitionUpperBound:
            workflow?.source?.partitionUpperBound ??
            workflow?.source?.partition_upper_bound ??
            undefined,

          partitionNum:
            workflow?.source?.partitionNum ??
            workflow?.source?.partition_num ??
            undefined,

          schemaSaveMode:
            workflow?.target?.schemaSaveMode ??
            DEFAULT_FORM_VALUES.schemaSaveMode,

          dataSaveMode:
            workflow?.target?.dataSaveMode ?? DEFAULT_FORM_VALUES.dataSaveMode,

          batchSize:
            workflow?.target?.batchSize ?? DEFAULT_FORM_VALUES.batchSize,

          enableUpsert:
            workflow?.target?.enableUpsert ?? DEFAULT_FORM_VALUES.enableUpsert,

          fieldIde:
            workflow?.target?.fieldIde ?? DEFAULT_FORM_VALUES.fieldIde,

          dorisEnable2pc:
            workflow?.target?.dorisEnable2pc ??
            workflow?.target?.["sink.enable-2pc"] ??
            false,

          dorisLabelPrefix:
            workflow?.target?.dorisLabelPrefix ??
            workflow?.target?.["sink.label-prefix"] ??
            "",

          dorisReplicaCount:
            workflow?.target?.dorisReplicaCount ?? 1,
        });

        if (sourceId) {
          const sourceDatabaseRequired = sourceIsScoped;
          const sourceSchemaRequired = sourceHasSchema
            && nextSourceSchemaOptions.length > 0
            && !nextSourceSchema;
          if (sourceDatabaseRequired && (!nextSourceDatabase || sourceSchemaRequired)) {
            setTableData([]);
            setMultiTableList([]);
          } else if (nextMatchMode === "1") {
            await fetchTables(
              String(sourceId),
              "1",
              nextSourceDatabase,
              nextSourceSchema,
            );
            if (mounted) {
              setMultiTableList(nextMultiTableList.map((table: string) => {
                const parts = String(table).split(".");
                return sourceIsDoris && parts.length === 2 ? parts[1] : table;
              }));
            }
          } else if (nextMatchMode === "4") {
            await fetchTables(
              String(sourceId),
              "4",
              nextSourceDatabase,
              nextSourceSchema,
            );
          } else if (nextMatchMode === "2" || nextMatchMode === "3") {
            if (nextKeyword) {
              await fetchReferenceTables(
                String(sourceId),
                nextMatchMode,
                nextKeyword,
                nextSourceDatabase,
                nextSourceSchema,
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
    fetchDatabaseOptions,
    fetchSchemaOptions,
    fetchScopedTables,
    fetchTables,
    fetchReferenceTables,
    debouncedFetchReferenceTables,
  ]);

  useEffect(() => {
    if (!params?.id && !publishedJobDefineId) {
      baselineSignatureRef.current = "";
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
        mode: "GUIDE_MULTI",
      },
      content: buildWorkflowData(),
      schedule: buildSchedulePayload(scheduleConfig),
      env: envConfig,
    });
  }, [basicConfig, scheduleConfig, envConfig, buildWorkflowData]);

  const handleSourceIdChange = async (value: string) => {
    setCurrentSourceId(value);
    setSourceDatabase("");
    setSourceSchemaName("");
    setSourceSchemaOptions([]);
    form.setFieldsValue({
      sourceDatabase: undefined,
      sourceSchemaName: undefined,
    });

    const selectedTarget = targetOption.find(
      (item: any) => String(item?.value) === String(currentTargetId),
    );
    const targetIsSystemManagedDoris = Boolean(
      currentTargetId
      && String(targetType?.dbType || "").toUpperCase() === "DORIS"
      && selectedTarget?.systemManaged,
    );
    if (targetIsSystemManagedDoris) {
      setTargetDatabase("");
      setTargetOdsDatabaseBindingId(undefined);
      form.setFieldsValue({
        targetDatabase: undefined,
        targetSchemaName: undefined,
        odsDatabaseBindingId: undefined,
      });
    }

    if (supportsDatabaseScope(sourceType?.dbType)) {
      try {
        setDatabaseLoading(true);
        setSourceDatabaseOptions(await fetchDatabaseOptions(value));

        if (targetIsSystemManagedDoris) {
          setTargetDatabaseOptions(await fetchDatabaseOptions(
            currentTargetId,
            true,
            value,
            true,
          ));
        }
      } catch (error) {
        console.error(error);
        setSourceDatabaseOptions([]);
      } finally {
        setDatabaseLoading(false);
      }
      setTableData([]);
      setReadOnlyTables([]);
      setMultiTableList([]);
      return;
    }

    if (targetIsSystemManagedDoris) {
      try {
        setDatabaseLoading(true);
        setTargetDatabaseOptions(await fetchDatabaseOptions(
          currentTargetId,
          true,
          value,
          true,
        ));
      } catch (error) {
        console.error(error);
        setTargetDatabaseOptions([]);
      } finally {
        setDatabaseLoading(false);
      }
    }

    if (matchMode === "1" || matchMode === "4") {
      await fetchTables(value, matchMode);
      setReadOnlyTables([]);
      return;
    }

    if (tableKeyword) {
      await fetchReferenceTables(value, matchMode, tableKeyword);
    }
  };

  const handleTargetIdChange = async (value: string, option?: any) => {
    setCurrentTargetId(value);
    setTargetDatabase("");
    setTargetSchemaName("");
    setTargetSchemaOptions([]);
    setTargetOdsDatabaseBindingId(undefined);
    form.setFieldsValue({
      targetDatabase: undefined,
      targetSchemaName: undefined,
      odsDatabaseBindingId: undefined,
    });

    if (!supportsDatabaseScope(targetType?.dbType)) {
      setTargetDatabaseOptions([]);
      return;
    }

    try {
      setDatabaseLoading(true);
      const options = await fetchDatabaseOptions(
        value,
        true,
        currentSourceId,
        Boolean(option?.systemManaged),
      );
      setTargetDatabaseOptions(options);
    } catch (error) {
      console.error(error);
      setTargetDatabaseOptions([]);
    } finally {
      setDatabaseLoading(false);
    }
  };

  const handleSourceDatabaseChange = async (value: string) => {
    setSourceDatabase(value);
    setSourceSchemaName("");
    setSourceSchemaOptions([]);
    form.setFieldValue("sourceDatabase", value);
    form.setFieldValue("sourceSchemaName", undefined);
    setMultiTableList([]);
    setReadOnlyTables([]);
    if (!currentSourceId) return;

    if (supportsSchemaScope(sourceType?.dbType)) {
      try {
        setSchemaLoading(true);
        const options = await fetchSchemaOptions(currentSourceId, value);
        setSourceSchemaOptions(options);
        if (options.length > 0) {
          setTableData([]);
          return;
        }
      } catch (error) {
        console.error(error);
        setSourceSchemaOptions([]);
      } finally {
        setSchemaLoading(false);
      }
    }

    if (matchMode === "1" || matchMode === "4") {
      await fetchTables(currentSourceId, matchMode, value);
    } else if (tableKeyword) {
      await fetchReferenceTables(currentSourceId, matchMode, tableKeyword, value);
    }
  };

  const handleSourceSchemaChange = async (value: string) => {
    setSourceSchemaName(value);
    form.setFieldValue("sourceSchemaName", value);
    setMultiTableList([]);
    setReadOnlyTables([]);
    if (!currentSourceId) return;

    if (matchMode === "1" || matchMode === "4") {
      await fetchTables(currentSourceId, matchMode, sourceDatabase, value);
    } else if (tableKeyword) {
      await fetchReferenceTables(
        currentSourceId,
        matchMode,
        tableKeyword,
        sourceDatabase,
        value,
      );
    }
  };

  const handleTargetDatabaseChange = async (value: string, option?: any) => {
    setTargetDatabase(value);
    setTargetSchemaName("");
    setTargetSchemaOptions([]);
    const bindingId = option?.odsDatabaseBindingId == null
      ? undefined
      : Number(option.odsDatabaseBindingId);
    setTargetOdsDatabaseBindingId(bindingId);
    form.setFieldsValue({
      targetDatabase: value,
      targetSchemaName: undefined,
      odsDatabaseBindingId: bindingId,
    });

    if (supportsSchemaScope(targetType?.dbType) && currentTargetId) {
      try {
        setSchemaLoading(true);
        setTargetSchemaOptions(await fetchSchemaOptions(currentTargetId, value));
      } catch (error) {
        console.error(error);
        setTargetSchemaOptions([]);
      } finally {
        setSchemaLoading(false);
      }
    }
  };

  const handleTargetSchemaChange = (value: string) => {
    setTargetSchemaName(value);
    form.setFieldValue("targetSchemaName", value);
  };

  const handleMatchModeChange = async (value: string) => {
    setMatchMode(value);
    form.setFieldValue("matchMode", value);

    if (!currentSourceId) return;

    if (value === "1" || value === "4") {
      setReadOnlyTables([]);
      await fetchTables(currentSourceId, value, sourceDatabase, sourceSchemaName);
      return;
    }

    setTableData([]);
    setMultiTableList([]);

    if (tableKeyword) {
      await fetchReferenceTables(
        currentSourceId,
        value,
        tableKeyword,
        sourceDatabase,
        sourceSchemaName,
      );
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

    debouncedFetchReferenceTables(
      currentSourceId,
      matchMode,
      keyword,
      sourceDatabase,
      sourceSchemaName,
    );
  };

  const validateBeforeSubmit = async () => {
    await form.validateFields();

    if (matchMode === "1" && (!multiTableList || multiTableList.length === 0)) {
      message.warning("请选择至少一个表");
      return false;
    }

    const scheduleWarning = getScheduleValidationMessage(scheduleConfig);
    if (scheduleWarning) {
      message.warning(scheduleWarning);
      return false;
    }

    const sourceId = form.getFieldValue("sourceId");
    const sinkId = form.getFieldValue("sinkId");

    const selectedSourceDatabase = String(
      form.getFieldValue("sourceDatabase") || sourceDatabase,
    ).trim();
    const selectedTargetDatabase = String(
      form.getFieldValue("targetDatabase") || targetDatabase,
    ).trim();
    const selectedSourceSchema = String(
      form.getFieldValue("sourceSchemaName") || sourceSchemaName,
    ).trim();
    const selectedTargetSchema = String(
      form.getFieldValue("targetSchemaName") || targetSchemaName,
    ).trim();

    if (supportsDatabaseScope(sourceType?.dbType) && !selectedSourceDatabase) {
      message.warning(
        String(sourceType?.dbType || "").toUpperCase() === "DORIS"
          ? "请选择 Doris 来源数据库"
          : "请选择来源数据库",
      );
      return false;
    }
    if (
      supportsSchemaScope(sourceType?.dbType)
      && sourceSchemaOptions.length > 0
      && !selectedSourceSchema
    ) {
      message.warning("请选择来源 Schema");
      return false;
    }
    if (supportsDatabaseScope(targetType?.dbType) && !selectedTargetDatabase) {
      message.warning(
        String(targetType?.dbType || "").toUpperCase() === "DORIS"
          ? "请选择 Doris 目标数据库"
          : "请选择目标数据库",
      );
      return false;
    }
    if (
      supportsSchemaScope(targetType?.dbType)
      && targetSchemaOptions.length > 0
      && !selectedTargetSchema
    ) {
      message.warning("请选择目标 Schema");
      return false;
    }
    const selectedTarget = targetOption.find(
      (item: any) => String(item?.value) === String(sinkId),
    );
    if (selectedTarget?.systemManaged && !(
      form.getFieldValue("odsDatabaseBindingId") || targetOdsDatabaseBindingId
    )) {
      message.warning("系统 Doris 目标必须选择 READY 的 ODS 数据库");
      return false;
    }

    if (sourceId && sinkId && sourceId === sinkId) {
      message.warning("来源和目标数据源不能相同");
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

    const res = await seatunnelJobDefinitionApi.saveOrUpdateGuideMulti(
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
        odsDatabaseBindingId: workflowData.target.odsDatabaseBindingId,

        scheduleConfig,
        schedule: finalPayload.schedule,

        env: envConfig,
      };

      /**
       * create 场景是从 sessionStorage 初始化的。
       * 发布成功后同步缓存，避免刷新后又变回未发布。
       */
      syncBatchSessionCache(prev?.id, nextParams);
      syncBatchSessionCache(jobDefineId, nextParams);

      return nextParams;
    });

    baselineSignatureRef.current = stableStringify({
      basic: finalPayload.basic,
      content: workflowData,
      schedule: finalPayload.schedule,
      env: finalPayload.env,
    });

    message.success("发布成功");
  } catch (error: any) {
    console.error(error);
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
      const res = await seatunnelJobDefinitionApi.buildGuideMultiConfig(
        finalPayload
      );

      setPreviewContent(res?.data || "");
      setPreviewOpen(true);
    } catch (error: any) {
      console.error(error);
      message.error(error?.message || "预览失败");
    } finally {
      setPreviewLoading(false);
    }
  };

  const canRun =
    !!publishedJobDefineId && !isDirty && !publishLoading && !runLoading;

  const runDisabledReason = !publishedJobDefineId
    ? "请先发布任务，再执行"
    : isDirty
      ? "当前内容已变更，请重新发布后再执行"
      : "";

  const handleRun = async () => {
    const pass = await validateBeforeSubmit();
    if (!pass) return;

    if (!publishedJobDefineId) {
      message.warning("请先发布任务，再执行");
      return;
    }

    if (isDirty) {
      message.warning("当前内容已变更，请重新发布后再执行");
      return;
    }

    try {
      setRunLoading(true);

      // TODO: 后续接入真正执行接口 / RunLog。
      // await seatunnelJobDefinitionApi.execute(publishedJobDefineId);

      // message.success("运行校验通过，可继续接入执行逻辑");
    } catch (error: any) {
      console.error(error);
      message.error(error?.message || "运行失败");
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
    targetType,
    sourceDatabaseOptions,
    targetDatabaseOptions,
    sourceSchemaOptions,
    targetSchemaOptions,
    sourceDatabase,
    targetDatabase,
    sourceSchemaName,
    targetSchemaName,
    targetOdsDatabaseBindingId,
    databaseLoading,
    schemaLoading,

    tableData,
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
    handleTargetIdChange,
    handleSourceDatabaseChange,
    handleSourceSchemaChange,
    handleTargetDatabaseChange,
    handleTargetSchemaChange,
    handleMatchModeChange,
    handleKeywordChange,
    handleSave,
    handlePreview,
    handleRun,

    buildFinalPayload,
    resetBaseline,
  };
}
