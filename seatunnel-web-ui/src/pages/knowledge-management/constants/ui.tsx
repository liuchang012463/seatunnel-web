import { AppstoreAddOutlined, ClockCircleOutlined } from "@ant-design/icons";
import type { MenuItemConfig, MenuKey } from "../types";

export const PAGE_BG = "var(--st-color-bg-page)";
export const CARD_BG = "var(--st-color-bg-panel)";
export const BORDER_COLOR = "var(--st-color-border)";
export const TEXT_SECONDARY = "var(--st-color-text-secondary)";
export const BLUE = "var(--st-color-accent)";
export const BLUE_LIGHT = "var(--st-color-selected)";

export const menuList: MenuItemConfig[] = [
  {
    key: "connector",
    label: "连接器参数",
    desc: "维护参数名称、类型与使用规则",
    icon: <AppstoreAddOutlined />,
  },
  {
    key: "time",
    label: "时间变量",
    desc: "维护时间变量与替换表达式规则",
    icon: <ClockCircleOutlined />,
  },
];

export const pageTitleMap: Record<MenuKey, string> = {
  connector: "Connector 参数知识",
  time: "时间变量知识",
};

export const infoTextMap: Record<MenuKey, string> = {
  connector:
    "例如：Jdbc / parallelism / 任务并行度 / number / 非必填 / 默认值 1",
  time: "例如：start_time / 开始时间 / yyyy-MM-dd HH:mm:ss / 默认值 ${now-1d}",
};
