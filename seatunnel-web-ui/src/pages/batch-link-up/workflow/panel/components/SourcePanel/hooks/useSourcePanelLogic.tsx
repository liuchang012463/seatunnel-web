import {
  dataSourceCatalogApi,
  fetchDataSourceOptions,
} from "@/pages/data-source/service";
import {
  supportsDatabaseScope,
  supportsSchemaScope,
} from "@/pages/data-source/dataSourceRegistry";
import { message } from "antd";
import { Table2 } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

export interface SourcePanelLogicProps {
  selectedNode: any;
  onNodeDataChange: (nodeId: string, newData: any) => void;
  qualityDetailRef: any;
  scheduleConfig: any;
  jobDefinitionId?: string | number;
}

export function useSourcePanelLogic({
  selectedNode,
  onNodeDataChange,
  qualityDetailRef,
  scheduleConfig,
}: SourcePanelLogicProps) {
  const nodeId = selectedNode?.id;
  const nodeData = selectedNode?.data || {};
  const config = nodeData?.config || {};
  const isWebUpload = String(config.sourceMode || '').toUpperCase() === 'WEB_UPLOAD';
  const meta = nodeData?.meta || {};

  const title = nodeData?.title || "来源节点";
  const dbType = nodeData?.dbType || "MYSQL";
  const description = nodeData?.description || "读取源端数据";

  const dataSourceId = config?.dataSourceId ? String(config.dataSourceId) : "";
  const database = config?.database || "";
  const schemaName = config?.schemaName || config?.schema || "";
  const readMode = config?.readMode || "table";
  const table = config?.table || undefined;
  const sql = config?.sql || "";
  const extraParams = config?.extraParams || [];
  const incrementalConfig = config?.incrementalConfig || {};

  const [dataSourceOptions, setDataSourceOptions] = useState<any[]>([]);
  const [databaseOptions, setDatabaseOptions] = useState<any[]>([]);
  const [schemaOptions, setSchemaOptions] = useState<any[]>([]);
  const [tableOptions, setTableOptions] = useState<any[]>([]);
  const [databaseLoading, setDatabaseLoading] = useState(false);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);

  const [sqlPopoverOpen, setSqlPopoverOpen] = useState(false);
  const [resolvePopoverOpen, setResolvePopoverOpen] = useState(false);
  const [selectedSqlTable, setSelectedSqlTable] = useState<string>();
  const [generateSqlLoading, setGenerateSqlLoading] = useState(false);
  const [resolveSqlLoading, setResolveSqlLoading] = useState(false);
  const [resolvedSqlPreview, setResolvedSqlPreview] = useState("");
  const [viewLoading, setViewLoading] = useState(false);

  const resetSchemaMeta = {
    outputSchema: [],
    schemaStatus: "idle",
    schemaError: "",
  };

  // Async callbacks (e.g. field recognition) capture updateNode from an older
  // render; always build on the latest node data so late updates never revert
  // newer config written in between.
  const nodeDataRef = useRef(nodeData);
  nodeDataRef.current = nodeData;

  const updateNode = useCallback(
    (
      configPatch?: Record<string, any>,
      extraNodeDataPatch?: Record<string, any>,
      metaPatch?: Record<string, any>
    ) => {
      if (!nodeId) return;
      const latestNodeData = nodeDataRef.current;

      onNodeDataChange(nodeId, {
        ...latestNodeData,
        ...(extraNodeDataPatch || {}),
        config: {
          ...(latestNodeData?.config || {}),
          ...(configPatch || {}),
        },
        meta: {
          ...(latestNodeData?.meta || {}),
          ...(metaPatch || {}),
        },
      });
    },
    [nodeId, onNodeDataChange]
  );

  const currentDataSource = useMemo(() => {
    return dataSourceOptions.find(
      (item: any) => String(item.value) === String(dataSourceId)
    );
  }, [dataSourceId, dataSourceOptions]);

  useEffect(() => {
    const loadDataSourceOptions = async () => {
      if (isWebUpload || !dbType) {
        setDataSourceOptions([]);
        return;
      }

      try {
        const res = await fetchDataSourceOptions(dbType);
        const list = Array.isArray(res?.data) ? res.data : [];
        const options = list.map((item: any) => ({
          label: item?.label,
          value: String(item?.value),
          dbType: item?.dbType,
        }));
        setDataSourceOptions(options);
      } catch (error) {
        console.error("load data source options error", error);
        setDataSourceOptions([]);
      }
    };

    loadDataSourceOptions();
  }, [dbType, isWebUpload]);

  useEffect(() => {
    if (!dataSourceId || dataSourceOptions.length === 0) return;

    const matched = dataSourceOptions.find(
      (item: any) => String(item.value) === String(dataSourceId)
    );

    if (!matched) return;

    const nextTitle = matched?.label || nodeData?.title;
    const nextDbType = matched?.dbType || nodeData?.dbType || "MYSQL";

    if (nodeData?.title === nextTitle && nodeData?.dbType === nextDbType) {
      return;
    }

    updateNode(undefined, {
      title: nextTitle,
      dbType: nextDbType,
    });
  }, [dataSourceId, dataSourceOptions, nodeData, updateNode]);

  useEffect(() => {
    const loadDatabaseOptions = async () => {
      if (
        isWebUpload ||
        !dataSourceId ||
        !supportsDatabaseScope(dbType)
      ) {
        setDatabaseOptions([]);
        return;
      }

      setDatabaseLoading(true);
      try {
        const res = await dataSourceCatalogApi.listDatabases(dataSourceId);
        const list = Array.isArray(res?.data) ? res.data : [];
        const options = list.map((item: any) => ({
          label: item?.label ?? item?.value,
          value: String(item?.value ?? ""),
          description: item?.description,
        }));
        setDatabaseOptions(options);

        if (
          database &&
          !options.some((item: any) => String(item.value) === String(database))
        ) {
          updateNode({
            database: undefined,
            schemaName: undefined,
            table: undefined,
            incrementalConfig: undefined,
          });
        }
      } catch (error) {
        console.error("load database options error", error);
        setDatabaseOptions([]);
      } finally {
        setDatabaseLoading(false);
      }
    };

    loadDatabaseOptions();
  }, [dataSourceId, database, dbType, isWebUpload, updateNode]);

  useEffect(() => {
    const loadSchemaOptions = async () => {
      if (
        isWebUpload ||
        !dataSourceId ||
        !database ||
        !supportsSchemaScope(dbType)
      ) {
        setSchemaOptions([]);
        return;
      }

      setSchemaLoading(true);
      try {
        const res = await dataSourceCatalogApi.listSchemas(dataSourceId, database);
        const list = Array.isArray(res?.data) ? res.data : [];
        const options = list.map((item: any) => ({
          label: item?.label ?? item?.value,
          value: String(item?.value ?? ""),
          description: item?.description,
        }));
        setSchemaOptions(options);

        if (
          schemaName &&
          options.length > 0 &&
          !options.some((item: any) => String(item.value) === String(schemaName))
        ) {
          updateNode({
            schemaName: undefined,
            table: undefined,
            incrementalConfig: undefined,
          });
        }
      } catch (error) {
        console.error("load schema options error", error);
        setSchemaOptions([]);
      } finally {
        setSchemaLoading(false);
      }
    };

    loadSchemaOptions();
  }, [dataSourceId, database, dbType, isWebUpload, schemaName, updateNode]);

  useEffect(() => {
    const loadTableOptions = async () => {
      const databaseScoped = supportsDatabaseScope(dbType);
      const schemaScoped = supportsSchemaScope(dbType);
      if (isWebUpload || !dataSourceId || String(dbType).toUpperCase() === "HTTP") {
        setTableOptions([]);
        return;
      }

      if (databaseScoped && !database) {
        setTableOptions([]);
        return;
      }

      if (schemaScoped && schemaOptions.length > 0 && !schemaName) {
        setTableOptions([]);
        return;
      }

      setTableLoading(true);
      try {
        const res = databaseScoped
          ? await dataSourceCatalogApi.listTablesByDatabase(
              dataSourceId,
              database,
              schemaScoped ? schemaName : undefined,
            )
          : await dataSourceCatalogApi.listTable(dataSourceId);
        const list = Array.isArray(res?.data) ? res.data : [];

        const options = list.map((item: any) => {
          const text = item?.value ?? String(item?.value ?? "");

          return {
            label: (
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    justifyContent: "center",
                    width: 20,
                    height: 20,
                    borderRadius: 6,
                    background: "rgba(148, 163, 184, 0.12)",
                    color: 'var(--st-color-text-muted)',
                    flexShrink: 0,
                  }}
                >
                  <Table2 size={12} />
                </span>
                <span style={{ color: 'var(--st-color-text-primary)' }}>{text}</span>
              </div>
            ),
            value: String(item?.value ?? ""),
            rawLabel: text,
            description: item?.description,
          };
        });

        setTableOptions(options);

        if (
          table &&
          !options.some((item: any) => String(item.value) === String(table))
        ) {
          updateNode({ table: undefined, incrementalConfig: undefined });
        }
      } catch (error) {
        console.error("load table options error", error);
        setTableOptions([]);
      } finally {
        setTableLoading(false);
      }
    };

    loadTableOptions();
  }, [
    dataSourceId,
    database,
    dbType,
    isWebUpload,
    schemaName,
    schemaOptions.length,
    table,
    updateNode,
  ]);

  const handleDataSourceChange = useCallback(
    (value: string, option: any) => {
      setSelectedSqlTable(undefined);
      setResolvedSqlPreview("");
      setSqlPopoverOpen(false);
      setResolvePopoverOpen(false);

      updateNode(
        {
          dataSourceId: value,
          database: undefined,
          schemaName: undefined,
          index: undefined,
          index_list: undefined,
          table: undefined,
          sql: "",
          incrementalConfig: undefined,
        },
        {
          title: option?.label || nodeData?.title,
          dbType: option?.dbType || nodeData?.dbType || "MYSQL",
        },
        resetSchemaMeta
      );
    },
    [nodeData, updateNode]
  );

  const handleDatabaseChange = useCallback(
    (value: string) => {
      updateNode({
        database: value,
        schemaName: undefined,
        table: undefined,
        incrementalConfig: undefined,
      }, undefined, resetSchemaMeta);
    },
    [updateNode]
  );

  const handleSchemaChange = useCallback(
    (value: string) => {
      updateNode({
        schemaName: value,
        table: undefined,
        incrementalConfig: undefined,
      }, undefined, resetSchemaMeta);
    },
    [updateNode]
  );

  const handleReadModeChange = useCallback(
    (value: string) => {
      updateNode(
        {
          readMode: value,
          ...(value === "table" ? { sql: "" } : { table: undefined }),
          incrementalConfig: undefined,
        },
        undefined,
        resetSchemaMeta
      );
    },
    [updateNode]
  );
  const scheduleParamsList = scheduleConfig?.paramsList || [];

  const resolveSourceOutputSchema = useCallback(async () => {
    const currentDataSourceId = dataSourceId;
    const currentReadMode = readMode;
    const currentTable = table;
    const sqlText = sql?.trim();

    if (!currentDataSourceId) {
      message.warning("请先选择来源数据源");
      return [];
    }

    if (currentReadMode === "table" && !currentTable) {
      message.warning("请先选择来源表");
      return [];
    }

    if (currentReadMode === "sql" && !sqlText) {
      message.warning("请先输入 SQL");
      return [];
    }

    try {
      setTableLoading(true);

      updateNode(undefined, undefined, {
        schemaStatus: "loading",
        schemaError: "",
      });

      const params = {
        read_mode: currentReadMode,
        table_path: currentReadMode === "table" ? currentTable : "",
        ...(database ? { database } : {}),
        ...(schemaName ? { schema_name: schemaName } : {}),
        query: currentReadMode === "sql" ? sqlText : "",
        paramsList: scheduleConfig?.paramsList || [],
      };

      const resp = await dataSourceCatalogApi.listColumn(
        currentDataSourceId,
        params
      );

      if (resp?.code !== 0) {
        const errorMsg = resp?.message || "字段解析失败";

        updateNode(undefined, undefined, {
          outputSchema: [],
          schemaStatus: "error",
          schemaError: errorMsg,
        });

        message.error(errorMsg);
        return [];
      }

      const rawColumns = resp?.data || [];

      const outputSchema = rawColumns.map((item: any) => ({
        type: item?.fieldType || "",
        nullable: item?.isNullable,
        comment: item?.fieldComment || "",
        originFieldName: item?.fieldName || "",
      }));

      updateNode(undefined, undefined, {
        outputSchema,
        schemaStatus: "success",
        schemaError: "",
      });

      return outputSchema;
    } catch (error: any) {
      updateNode(undefined, undefined, {
        outputSchema: [],
        schemaStatus: "error",
        schemaError: "字段解析失败",
      });

      return [];
    } finally {
      setTableLoading(false);
    }
  }, [
    dataSourceId,
    database,
    dbType,
    readMode,
    schemaName,
    table,
    sql,
    updateNode,
    scheduleParamsList,
  ]);

  const handlePreview = useCallback(async () => {
    if (!dataSourceId) {
      message.warning("请选择数据源");
      return;
    }

    console.log(scheduleConfig);

    const getRequestParams = () => ({
      read_mode: readMode,
      ...(readMode === "table" ? { table_path: table } : { query: sql }),
      ...(database ? { database } : {}),
      ...(schemaName ? { schema_name: schemaName } : {}),
      extra_params: extraParams,
      paramsList: scheduleConfig?.paramsList || [],
    });

    try {
      if (readMode === "table" && !table) {
        message.warning("请选择来源表");
        return;
      }

      if (readMode === "sql" && !sql?.trim()) {
        message.warning("请输入 SQL");
        return;
      }

      setViewLoading(true);

      const data = await dataSourceCatalogApi.getTop20Data(
        dataSourceId,
        getRequestParams()
      );

      if (data?.code === 0) {
        qualityDetailRef.current?.onOpen(true, data);
      } else {
      }
    } catch (error) {
    } finally {
      setViewLoading(false);
    }
  }, [
    dataSourceId,
    database,
    dbType,
    readMode,
    schemaName,
    table,
    sql,
    extraParams,
    qualityDetailRef,
    scheduleParamsList,
  ]);

  const handleStatistics = useCallback(() => {
    console.log("statistics source data", {
      nodeId,
      dataSourceId,
      readMode,
      table,
      sql,
      extraParams,
    });
  }, [nodeId, dataSourceId, readMode, table, sql, extraParams]);

  const handleGenerateSql = useCallback(async () => {
    if (!dataSourceId) {
      message.warning("请先选择数据源");
      return;
    }

    if (!selectedSqlTable) {
      message.warning("请选择表");
      return;
    }

    try {
      setGenerateSqlLoading(true);

      const res = await dataSourceCatalogApi.buildSqlTemplate(dataSourceId, {
        read_mode: "table",
        table_path: selectedSqlTable,
        ...(database ? { database } : {}),
        ...(schemaName ? { schema_name: schemaName } : {}),
      });

      if (res?.code !== 0) {
        return;
      }

      const nextSql = res?.data || "";
      if (!nextSql) {
        message.warning("未生成有效 SQL");
        return;
      }

      updateNode({ sql: nextSql, incrementalConfig: undefined }, undefined, resetSchemaMeta);
      setSqlPopoverOpen(false);
      message.success("SQL 生成成功");
    } catch (error) {
      console.error("generate sql error", error);
    } finally {
      setGenerateSqlLoading(false);
    }
  }, [dataSourceId, database, dbType, schemaName, selectedSqlTable, updateNode]);

  const handleResolveSqlPreview = useCallback(async () => {
    if (!dataSourceId) {
      message.warning("请先选择数据源");
      return;
    }

    if (!sql) {
      message.warning("请先输入 SQL");
      return;
    }

    try {
      setResolveSqlLoading(true);

      const res = await dataSourceCatalogApi.resolveSql(dataSourceId, {
        query: sql,
        read_mode: readMode,
        extra_params: extraParams,
        paramsList: scheduleConfig?.paramsList || [],
      });

      if (res?.code !== 0) {
        message.error(res?.message || "SQL 变量解析失败");
        return;
      }

      setResolvedSqlPreview(res?.data || "");
    } catch (error) {
      console.error("resolve sql error", error);
      message.error("SQL 变量解析失败");
    } finally {
      setResolveSqlLoading(false);
    }
  }, [dataSourceId, sql, scheduleParamsList]);

  const handleOpenResolvePopover = useCallback(
    async (open: boolean) => {
      setResolvePopoverOpen(open);
      if (open && sql) {
        await handleResolveSqlPreview();
      }
    },
    [sql, handleResolveSqlPreview]
  );

  const handleResolveColumns = useCallback(async () => {
    await resolveSourceOutputSchema();
  }, [resolveSourceOutputSchema]);

  return {
    nodeData,
    meta,
    title,
    dbType,
    description,
    dataSourceId,
    database,
    schemaName,
    readMode,
    table,
    sql,
    extraParams,
    incrementalConfig,
    currentDataSource,

    dataSourceOptions,
    databaseOptions,
    schemaOptions,
    tableOptions,
    databaseLoading,
    schemaLoading,
    tableLoading,

    sqlPopoverOpen,
    setSqlPopoverOpen,
    resolvePopoverOpen,
    selectedSqlTable,
    setSelectedSqlTable,
    generateSqlLoading,
    resolveSqlLoading,
    resolvedSqlPreview,
    viewLoading,

    updateNode,
    handleDataSourceChange,
    handleDatabaseChange,
    handleSchemaChange,
    handleReadModeChange,
    handlePreview,
    handleStatistics,
    handleGenerateSql,
    handleResolveSqlPreview,
    handleOpenResolvePopover,
    handleResolveColumns,
  };
}
