import { selectDataSourceById } from "@/pages/data-source/service";
import { DoubleRightOutlined, FileOutlined } from "@ant-design/icons";
import { Empty, Popover } from "antd";
import { CSSProperties, useState } from "react";
import DatabaseIcons from "../../../../data-source/icon/DatabaseIcons";

interface DataSourceSyncPlanProps {
  record: any;
}

const dataSourcePopoverInnerStyle: CSSProperties = {
  borderRadius: 12,
  boxShadow: "0 14px 36px rgba(15, 23, 42, 0.16)",
};

const jsonPopoverContentStyle: CSSProperties = {
  width: 420,
  maxWidth: "calc(100vw - 96px)",
  maxHeight: 360,
  overflow: "auto",
  padding: 12,
  border: "1px solid #2187a8",
  borderRadius: 10,
  background: "#f8fafc",
  color: "#334155",
  fontFamily:
    "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace",
  fontSize: 12,
  lineHeight: "20px",
  wordBreak: "break-word",
};

const DataSourceSyncPlan: React.FC<DataSourceSyncPlanProps> = ({ record }) => {
  const isFileSync = record?.mode === "FILE_SYNC";
  const isManagedFileSource =
    isFileSync && String(record?.sourceType || "").toUpperCase() === "WEB_UPLOAD";
  const [sourcePopoverVisible, setSourcePopoverVisible] = useState(false);
  const [sinkPopoverVisible, setSinkPopoverVisible] = useState(false);
  const [jsonData, setJsonData] = useState<any>(null); // Store the JSON data

  const safeParse = (value: any) => {
    if (!value) return null;

    if (typeof value === "object") {
      return value;
    }

    if (typeof value === "string") {
      try {
        return JSON.parse(value);
      } catch {
        return null;
      }
    }

    return null;
  };

  const renderJsonPopoverContent = () => {
    if (!jsonData) {
      return (
        <div style={{ width: 320, padding: "12px 0" }}>
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
        </div>
      );
    }

    const renderValue = (value: any): React.ReactNode => {
      if (value === null) {
        return <span className="text-gray-400">null</span>;
      }

      if (typeof value === "string") {
        return <span className="text-emerald-600">"{value}"</span>;
      }

      if (typeof value === "number") {
        return <span className="text-amber-500">{value}</span>;
      }

      if (typeof value === "boolean") {
        return (
          <span className={value ? "text-blue-600" : "text-red-500"}>
            {String(value)}
          </span>
        );
      }

      return <span className="text-gray-700">{String(value)}</span>;
    };

    const renderObject = (obj: any, level = 0): React.ReactNode => {
      if (typeof obj !== "object" || obj === null) {
        return renderValue(obj);
      }

      const isArray = Array.isArray(obj);
      const indent = level * 14;

      return (
        <div>
          <div style={{ paddingLeft: indent }} className="text-gray-400">
            {isArray ? "[" : "{"}
          </div>

          <div>
            {Object.entries(obj).map(([key, value]) => (
              <div
                key={key}
                style={{ paddingLeft: indent + 14 }}
                className="leading-5"
              >
                {!isArray && (
                  <>
                    <span className="text-purple-600">"{key}"</span>
                    <span className="text-gray-400">: </span>
                  </>
                )}

                {typeof value === "object" && value !== null ? (
                  renderObject(value, level + 1)
                ) : (
                  renderValue(value)
                )}
              </div>
            ))}
          </div>

          <div style={{ paddingLeft: indent }} className="text-gray-400">
            {isArray ? "]" : "}"}
          </div>
        </div>
      );
    };

    return <div style={jsonPopoverContentStyle}>{renderObject(jsonData)}</div>;
  };

  const formatTables = (tableValue: any, fallback?: string) => {
    if (!tableValue) return fallback || "-";

    if (typeof tableValue === "string") {
      const trimmed = tableValue.trim();

      if (!trimmed) return fallback || "-";

      // 普通单表名，直接返回
      if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
        return trimmed;
      }

      const parsed = safeParse(trimmed);
      if (!parsed) return fallback || trimmed;
      tableValue = parsed;
    }

    // 数组格式：["t1", "t2"]
    if (Array.isArray(tableValue)) {
      if (tableValue.length === 0) return fallback || "-";
      if (tableValue.length === 1) return tableValue[0];
      return `${tableValue[0]} +${tableValue.length - 1} more`;
    }

    // 对象格式：{schema1:["t1","t2"], schema2:["t3"]}
    if (typeof tableValue === "object") {
      const allTables: string[] = [];

      Object.values(tableValue).forEach((value: any) => {
        if (Array.isArray(value)) {
          allTables.push(...value);
        } else if (typeof value === "string" && value.trim()) {
          allTables.push(value.trim());
        }
      });

      if (allTables.length === 0) return fallback || "-";
      if (allTables.length === 1) return allTables[0];
      return `${allTables[0]} +${allTables.length - 1} more`;
    }

    return fallback || "-";
  };

  const getPlanTitle = () => {
    if (isFileSync) return "文件引接";
    if (record?.jobType === "BATCH") {
      if (record?.mode === "GUIDE_SINGLE") return "单表同步";
      if (record?.mode === "GUIDE_SINGLE_INCREMENTAL") return "单表增量微批";
      if (record?.mode === "GUIDE_MULTI") return "多表同步";
      if (record?.mode === "SCRIPT") return "脚本模式";
      return "Batch Sync";
    }

    return "数据同步";
  };

  const sourceTableText = formatTables(
    record?.sourceTable,
    ["GUIDE_SINGLE", "GUIDE_SINGLE_INCREMENTAL"].includes(record?.mode)
      ? "Single Table"
      : "Not Configured"
  );

  const sinkTableText = formatTables(
    record?.sinkTable,
    ["GUIDE_SINGLE", "GUIDE_SINGLE_INCREMENTAL"].includes(record?.mode)
      ? "Single Table"
      : "Not Configured"
  );

  const getTableCount = (tableValue: any) => {
    if (!tableValue) return 0;

    let value = tableValue;

    if (typeof value === "string") {
      const trimmed = value.trim();

      if (!trimmed) return 0;

      if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
        return 1;
      }

      const parsed = safeParse(trimmed);
      if (!parsed) return 1;

      value = parsed;
    }

    if (Array.isArray(value)) {
      return value.filter((item) => String(item || "").trim()).length;
    }

    if (typeof value === "object") {
      return Object.values(value).reduce((total: number, item: any) => {
        if (Array.isArray(item)) {
          return total + item.filter((v) => String(v || "").trim()).length;
        }

        if (typeof item === "string" && item.trim()) {
          return total + 1;
        }

        return total;
      }, 0);
    }

    return 0;
  };

  const sourceTableCount = getTableCount(record?.sourceTable);
  const sinkTableCount = getTableCount(record?.sinkTable);

  const getTableList = (tableValue: any): string[] => {
    if (!tableValue) return [];

    let value = tableValue;

    if (typeof value === "string") {
      const trimmed = value.trim();

      if (!trimmed) return [];

      if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
        return [trimmed];
      }

      const parsed = safeParse(trimmed);
      if (!parsed) return [trimmed];

      value = parsed;
    }

    if (Array.isArray(value)) {
      return value
        .filter((item) => String(item || "").trim())
        .map((item) => String(item).trim());
    }

    if (typeof value === "object") {
      const tables: string[] = [];

      Object.entries(value).forEach(([schemaName, item]: any) => {
        if (Array.isArray(item)) {
          item.forEach((tableName) => {
            if (String(tableName || "").trim()) {
              tables.push(
                schemaName
                  ? `${schemaName}.${String(tableName).trim()}`
                  : String(tableName).trim()
              );
            }
          });
        }

        if (typeof item === "string" && item.trim()) {
          tables.push(
            schemaName ? `${schemaName}.${item.trim()}` : item.trim()
          );
        }
      });

      return tables;
    }

    return [];
  };

  const sourceTableList = getTableList(record?.sourceTable);
  const sinkTableList = getTableList(record?.sinkTable);

  const renderTablePopoverContent = (tables: string[]) => {
    if (!tables.length) {
      return (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无表信息" />
      );
    }

    return (
      <div style={{ width: 300, maxHeight: 280, overflowY: "auto" }}>
        <div
          style={{
            marginBottom: 8,
            color: "rgba(0,0,0,0.45)",
            fontSize: 12,
          }}
        >
          共 {tables.length} 张表
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          {tables.map((tableName, index) => (
            <div
              key={`${tableName}-${index}`}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 8,
                padding: "6px 8px",
                borderRadius: 8,
                background: "#f5f7ff",
                color: "rgba(0,0,0,0.74)",
                fontSize: 12,
                lineHeight: "18px",
              }}
            >
              <span
                style={{
                  width: 4,
                  height: 4,
                  borderRadius: "50%",
                  background: "rgba(0,0,0,0.7)",
                  flexShrink: 0,
                }}
              />
              <span
                style={{
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
                title={tableName}
              >
                {tableName}
              </span>
            </div>
          ))}
        </div>
      </div>
    );
  };

  const planRowCount = Math.max(sourceTableCount, sinkTableCount, 1);
  const sourceName = isManagedFileSource
    ? "本地文件"
    : record?.sourceDatasourceName || "-";
  const sinkName = record?.sinkDatasourceName || "-";

  const renderSourceName = () => (
    <Popover
      open={sourcePopoverVisible}
      onVisibleChange={(visible) => setSourcePopoverVisible(visible)}
      title="数据源信息"
      content={renderJsonPopoverContent()}
      trigger="click"
      placement="rightTop"
      autoAdjustOverflow
      overlayInnerStyle={dataSourcePopoverInnerStyle}
    >
      <a
        href="#"
        className="sync-task-plan-source-link"
        title={String(sourceTableText)}
        onClick={(event) => {
          event.preventDefault();
          selectDataSourceById(record?.sourceDatasourceId).then((data) => {
            if (data?.code === 0) {
              setJsonData(safeParse(data?.data?.connectionParams || {}));
              setSourcePopoverVisible(true);
            }
          });
        }}
      >
        {sourceName}
      </a>
    </Popover>
  );

  const renderSinkName = () => (
    <Popover
      open={sinkPopoverVisible}
      onVisibleChange={(visible) => setSinkPopoverVisible(visible)}
      title="数据源信息"
      content={renderJsonPopoverContent()}
      trigger="click"
      placement="rightTop"
      autoAdjustOverflow
      overlayInnerStyle={dataSourcePopoverInnerStyle}
    >
      <span className="sync-task-plan-sink-link" title={String(sinkTableText)}>
        {sinkName}
      </span>
    </Popover>
  );

  return (
    <div className="sync-task-plan">
      <div className="sync-task-plan__badge-wrap">
        <span className="sync-task-plan__badge">
          <span className="sync-task-plan__badge-dot" />
          {getPlanTitle()}
        </span>
      </div>

      <div className="sync-task-plan__rows">
        {Array.from({ length: planRowCount }).map((_, index) => (
          <div key={`sync-plan-row-${index}`}>
            <div className="sync-task-plan__pair">
              <div className="sync-task-plan__source">
                {isManagedFileSource ? (
                  <FileOutlined className="sync-task-plan__source-icon" />
                ) : (
                  <DatabaseIcons dbType={record?.sourceType} width="20" height="20" />
                )}
                {renderSourceName()}
              </div>
              <div className="sync-task-plan__sink">{renderSinkName()}</div>
            </div>
            {index < planRowCount - 1 && (
              <div className="sync-task-plan__arrow" aria-hidden="true">
                <DoubleRightOutlined />
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};

export default DataSourceSyncPlan;
