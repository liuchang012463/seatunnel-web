import { Tag } from "antd";
import { DATA_SOURCE_REGISTRY } from "@/pages/data-source/dataSourceRegistry";
import type { ClientItem } from "./types";

export const STEP_THEME = {
  base: {
    pill: "bg-[color:var(--st-color-bg-selected)] text-[color:var(--st-color-accent)]",
    dot: "bg-[color:var(--st-color-accent)] text-[color:var(--st-color-bg-app)]",

    pillDone: "bg-[color:var(--st-color-bg-selected)] text-[color:var(--st-color-accent)]",
    dotDone: "bg-[color:var(--st-color-primary)] text-[color:var(--st-color-bg-app)]",

    pillInactive: "bg-[color:var(--st-color-bg-control)] text-[color:var(--st-color-text-muted)]",
    dotInactive: "bg-[color:var(--st-color-bg-elevated)] text-[color:var(--st-color-text-muted)]",
  },
  client: {
    pill: "bg-[color:var(--st-color-bg-selected)] text-[color:var(--st-color-accent)]",
    dot: "bg-[color:var(--st-color-accent)] text-[color:var(--st-color-bg-app)]",

    pillInactive: "bg-[color:var(--st-color-bg-control)] text-[color:var(--st-color-text-muted)]",
    dotInactive: "bg-[color:var(--st-color-bg-elevated)] text-[color:var(--st-color-text-muted)]",
  },
};

export const mockSourceClients: ClientItem[] = [
  { id: "s1", name: "InfluxDB-Client-01", type: "InfluxDB", status: "online" },
  { id: "s2", name: "InfluxDB-Client-02", type: "InfluxDB", status: "untested" },
];

export const mockTargetClients: ClientItem[] = [
  { id: "t1", name: "ClickHouse-Client-01", type: "ClickHouse", status: "online" },
  { id: "t2", name: "ClickHouse-Client-02", type: "ClickHouse", status: "untested" },
];

export const getStatusTag = (status?: ClientItem["status"]) => {
  switch (status) {
    case "online":
      return <Tag color="success">已连通</Tag>;
    case "offline":
      return <Tag color="error">不可用</Tag>;
    default:
      return <Tag>未测试</Tag>;
  }
};

/** 展示用短库名（达梦，不用 DAMENG） */
export const getFriendlyDbName = (item: any) => {
  if (!item) return "数据源";
  const dbType = String(item?.dbType || "").toUpperCase();
  if (dbType === "WEB_UPLOAD") return "本地文件";
  const registryLabel = DATA_SOURCE_REGISTRY.find(
    (entry) => entry.dbType === dbType,
  )?.label;
  const raw = registryLabel || item?.dbType || item?.pluginName || "数据源";
  return String(raw)
    .replace(/（[^）]*）/g, "")
    .replace(/\([^)]*\)/g, "")
    .trim();
};

/** 链路顶部等处展示库类型，统一用中文短名 */
export const getDbLabel = (item: any) => {
  if (!item) return "-";
  return getFriendlyDbName(item);
};

/** 根据来源 / 去向生成默认任务名 */
export const buildDefaultJobName = (sourceType: any, targetType: any) =>
  `${getFriendlyDbName(sourceType)} → ${getFriendlyDbName(targetType)}`;
