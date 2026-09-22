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
import { useCallback, useEffect, useMemo, useState } from "react";
import { fetchReadyOdsDatabases } from "@/pages/lake/physical/service";

export interface SinkPanelLogicProps {
  selectedNode: any;
  onNodeDataChange: (nodeId: string, newData: any) => void;
  sourceDataSourceId?: string;
}

export function useSinkPanelLogic({
  selectedNode,
  onNodeDataChange,
  sourceDataSourceId,
}: SinkPanelLogicProps) {
  const nodeId = selectedNode?.id;
  const nodeData = selectedNode?.data || {};
  const config = nodeData?.config || {};

  const title = nodeData?.title || "MYSQL";
  const dbType = nodeData?.dbType || "MYSQL";
  const description = nodeData?.description || "写入目标端数据";

  const dataSourceId = config?.dataSourceId
    ? String(config.dataSourceId)
    : undefined;
  const database = config?.database || "";
  const schemaName = config?.schemaName || config?.schema || "";
  const odsDatabaseBindingId = config?.odsDatabaseBindingId;

  const autoCreateTable = Boolean(config?.autoCreateTable);
  const writeMode = config?.writeMode || "append";
  const targetMode = config?.targetMode || "table";

  const table = config?.table;
  const targetTableName = config?.targetTableName || "";
  const sql = config?.sql || "";
  const primaryKey = config?.primaryKey || "";
  const batchSize = config?.batchSize || "";
  const extraParams = config?.extraParams || [];

  const [dataSourceOptions, setDataSourceOptions] = useState<any[]>([]);
  const [databaseOptions, setDatabaseOptions] = useState<any[]>([]);
  const [schemaOptions, setSchemaOptions] = useState<any[]>([]);
  const [tableOptions, setTableOptions] = useState<any[]>([]);
  const [databaseLoading, setDatabaseLoading] = useState(false);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);

  const [sqlPopoverOpen, setSqlPopoverOpen] = useState(false);
  const [selectedSqlTable, setSelectedSqlTable] = useState<string>();
  const [generateSqlLoading, setGenerateSqlLoading] = useState(false);

  const updateNode = useCallback(
    (patch: Record<string, any>, extraNodeDataPatch?: Record<string, any>) => {
      if (!nodeId) return;

      onNodeDataChange(nodeId, {
        ...nodeData,
        ...(extraNodeDataPatch || {}),
        config: {
          ...(nodeData?.config || {}),
          ...patch,
        },
      });
    },
    [nodeId, nodeData, onNodeDataChange]
  );

  const currentDataSource = useMemo(() => {
    return dataSourceOptions.find(
      (item: any) => String(item.value) === String(dataSourceId)
    );
  }, [dataSourceId, dataSourceOptions]);

  const isSystemManagedTarget = Boolean(currentDataSource?.systemManaged);

  useEffect(() => {
    const loadDataSourceOptions = async () => {
      if (!dbType) {
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
          systemManaged: item?.systemManaged,
        }));
        setDataSourceOptions(options);
      } catch (error) {
        console.error("load sink data source options error", error);
        setDataSourceOptions([]);
      }
    };

    loadDataSourceOptions();
  }, [dbType]);

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

    updateNode({}, { title: nextTitle, dbType: nextDbType });
  }, [dataSourceId, dataSourceOptions, nodeData, updateNode]);

  useEffect(() => {
    const loadDatabaseOptions = async () => {
      if (!dataSourceId || !supportsDatabaseScope(dbType)) {
        setDatabaseOptions([]);
        return;
      }

      // Wait for datasource metadata before selecting the catalog path.  A
      // system-managed Doris target must never briefly fall back to the
      // unrestricted database catalog while its option is still loading.
      if (dataSourceOptions.length === 0 || !currentDataSource) {
        setDatabaseOptions([]);
        return;
      }
      if (isSystemManagedTarget && !sourceDataSourceId) {
        setDatabaseOptions([]);
        return;
      }

      setDatabaseLoading(true);
      try {
        const res = isSystemManagedTarget
          ? await fetchReadyOdsDatabases(Number(sourceDataSourceId))
          : await dataSourceCatalogApi.listDatabases(dataSourceId);
        const list = Array.isArray(res?.data) ? res.data : [];
        const options = isSystemManagedTarget
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
        setDatabaseOptions(options);

        if (
          database &&
          !options.some((item: any) => String(item.value) === String(database))
        ) {
          updateNode({
            database: undefined,
            schemaName: undefined,
            odsDatabaseBindingId: undefined,
            table: undefined,
            targetTableName: "",
          });
        }
      } catch (error) {
        console.error("load sink database options error", error);
        setDatabaseOptions([]);
      } finally {
        setDatabaseLoading(false);
      }
    };

    loadDatabaseOptions();
  }, [
    dataSourceId,
    database,
    dbType,
    dataSourceOptions.length,
    currentDataSource,
    isSystemManagedTarget,
    sourceDataSourceId,
    updateNode,
  ]);

  useEffect(() => {
    const loadSchemaOptions = async () => {
      if (
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
            targetTableName: "",
          });
        }
      } catch (error) {
        console.error("load sink schema options error", error);
        setSchemaOptions([]);
      } finally {
        setSchemaLoading(false);
      }
    };

    loadSchemaOptions();
  }, [dataSourceId, database, dbType, schemaName, updateNode]);

  useEffect(() => {
    const loadTableOptions = async () => {
      if (!dataSourceId || autoCreateTable) {
        setTableOptions([]);
        return;
      }

      const databaseScoped = supportsDatabaseScope(dbType);
      const schemaScoped = supportsSchemaScope(dbType);
      if (
        databaseScoped
        && (
          !database
          || !currentDataSource
          || (isSystemManagedTarget && !sourceDataSourceId)
        )
      ) {
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
          updateNode({ table: undefined });
        }
      } catch (error) {
        console.error("load sink table options error", error);
        setTableOptions([]);
      } finally {
        setTableLoading(false);
      }
    };

    loadTableOptions();
  }, [
    dataSourceId,
    autoCreateTable,
    database,
    dbType,
    schemaName,
    schemaOptions.length,
    currentDataSource,
    isSystemManagedTarget,
    sourceDataSourceId,
    table,
    updateNode,
  ]);

  const handleDataSourceChange = useCallback(
    (value: string, option: any) => {
      setSelectedSqlTable(undefined);
      setSqlPopoverOpen(false);

      updateNode(
        {
          dataSourceId: value,
          database: undefined,
          schemaName: undefined,
          odsDatabaseBindingId: undefined,
          index: undefined,
          table: undefined,
          targetTableName: "",
          sql: "",
          primaryKey: "",
        },
        {
          title: option?.label || nodeData?.title,
          dbType: option?.dbType || nodeData?.dbType || "MYSQL",
        }
      );
    },
    [nodeData, updateNode]
  );

  const handleDatabaseChange = useCallback(
    (value: string, option: any) => {
      updateNode({
        database: value,
        schemaName: undefined,
        odsDatabaseBindingId: isSystemManagedTarget
          ? option?.odsDatabaseBindingId
          : undefined,
        table: undefined,
        targetTableName: "",
      });
    },
    [isSystemManagedTarget, updateNode]
  );

  const handleSchemaChange = useCallback(
    (value: string) => {
      updateNode({
        schemaName: value,
        table: undefined,
        targetTableName: "",
      });
    },
    [updateNode]
  );

  const handleAutoCreateTableChange = useCallback(
    (checked: boolean) => {
      setSelectedSqlTable(undefined);
      setSqlPopoverOpen(false);

      updateNode({
        autoCreateTable: checked,
        targetMode: "table",
        table: undefined,
        targetTableName: "",
        sql: "",
      });
    },
    [updateNode]
  );

  const handleWriteModeChange = useCallback(
    (value: string) => {
      const patch: Record<string, any> = { writeMode: value };

      if (value !== "upsert") {
        patch.primaryKey = "";
      }

      updateNode(patch);
    },
    [updateNode]
  );

  const handleTargetModeChange = useCallback(
    (value: string) => {
      updateNode({
        targetMode: value,
        ...(value === "table" ? { sql: "" } : { table: undefined }),
      });
    },
    [updateNode]
  );

  const handleGenerateSql = useCallback(async () => {
    if (!dataSourceId) {
      message.warning("请先选择目标数据源");
      return;
    }

    if (!selectedSqlTable) {
      message.warning("请选择目标表");
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

      updateNode({ sql: nextSql });
      setSqlPopoverOpen(false);
    } catch (error) {
      console.error("generate sink sql error", error);
    } finally {
      setGenerateSqlLoading(false);
    }
  }, [dataSourceId, database, dbType, schemaName, selectedSqlTable, updateNode]);

  return {
    title,
    dbType,
    description,

    dataSourceId,
    database,
    schemaName,
    autoCreateTable,
    writeMode,
    targetMode,
    table,
    targetTableName,
    sql,
    primaryKey,
    batchSize,
    extraParams,

    currentDataSource,
    dataSourceOptions,
    databaseOptions,
    schemaOptions,
    databaseLoading,
    schemaLoading,
    odsDatabaseBindingId,
    isSystemManagedTarget,
    tableOptions,
    tableLoading,

    sqlPopoverOpen,
    setSqlPopoverOpen,
    selectedSqlTable,
    setSelectedSqlTable,
    generateSqlLoading,

    updateNode,
    handleDataSourceChange,
    handleDatabaseChange,
    handleSchemaChange,
    handleAutoCreateTableChange,
    handleWriteModeChange,
    handleTargetModeChange,
    handleGenerateSql,
  };
}
