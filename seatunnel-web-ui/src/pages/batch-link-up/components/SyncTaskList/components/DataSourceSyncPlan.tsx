import { DATA_SOURCE_REGISTRY } from "@/pages/data-source/dataSourceRegistry";
import { DoubleRightOutlined, FileOutlined } from "@ant-design/icons";
import { Tooltip } from "antd";
import DatabaseIcons from "../../../../data-source/icon/DatabaseIcons";

interface DataSourceSyncPlanProps {
  record: any;
}

/** 单元格只展示「源数据源类型 → 目标数据源类型」，数据源名与表清单收敛进悬浮提示。 */
const DataSourceSyncPlan: React.FC<DataSourceSyncPlanProps> = ({ record }) => {
  const isFileSync = record?.mode === "FILE_SYNC";
  const isManagedFileSource =
    isFileSync && String(record?.sourceType || "").toUpperCase() === "WEB_UPLOAD";

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

  const getTypeLabel = (dbType: any) => {
    const normalized = String(dbType || "").trim().toUpperCase();

    if (!normalized) return "-";

    const registryItem = DATA_SOURCE_REGISTRY.find(
      (item) => item.dbType.toUpperCase() === normalized
    );

    return registryItem?.label || String(dbType);
  };

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

  const getTableSummary = (tableValue: any) => {
    const count = getTableCount(tableValue);

    if (!count) return "未配置表";

    if (count === 1) {
      const value = safeParse(tableValue) ?? String(tableValue || "").trim();
      const firstTable =
        typeof value === "object"
          ? Object.values(value)
              .flat()
              .find((item) => String(item || "").trim())
          : value;

      return String(firstTable || "未配置表");
    }

    return `共 ${count} 张表`;
  };

  const sourceSummary = isManagedFileSource
    ? "本地文件"
    : `${record?.sourceDatasourceName || "-"} · ${getTableSummary(record?.sourceTable)}`;
  const sinkSummary = `${record?.sinkDatasourceName || "-"} · ${getTableSummary(
    record?.sinkTable
  )}`;

  return (
    <Tooltip
      title={
        <div className="sync-plan-tooltip">
          <div>
            来源：{sourceSummary}
          </div>
          <div>
            目标：{sinkSummary}
          </div>
        </div>
      }
    >
      <div className="sync-plan-compact">
        <span className="sync-plan-compact__badge">{getPlanTitle()}</span>
        <span className="sync-plan-compact__flow">
          {isManagedFileSource ? (
            <FileOutlined className="sync-plan-compact__icon" />
          ) : (
            <DatabaseIcons
              dbType={record?.sourceType}
              width="16"
              height="16"
            />
          )}
          <span className="sync-plan-compact__type">
            {isManagedFileSource ? "本地文件" : getTypeLabel(record?.sourceType)}
          </span>
          <DoubleRightOutlined className="sync-plan-compact__arrow" />
          <DatabaseIcons dbType={record?.sinkType} width="16" height="16" />
          <span className="sync-plan-compact__type">
            {getTypeLabel(record?.sinkType)}
          </span>
        </span>
      </div>
    </Tooltip>
  );
};

export default DataSourceSyncPlan;
