import { Tag } from "antd";
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

export {
  buildDefaultJobName,
  getDbLabel,
  getFriendlyDbName,
} from "@/pages/batch-link-up/detail/constants";