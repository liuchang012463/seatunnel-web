import type { ThemeConfig } from "antd";

// 借鉴参考项目的「语义令牌 → 官方组件变量」分层，颜色仍由本项目 --st-* 管理。
// 仅映射直接用于绘制的组件令牌；参与颜色派生的 Seed Token 保留在 ConfigProvider。
const fieldTokens = {
  activeBorderColor: "var(--st-color-accent)",
  hoverBorderColor: "var(--st-color-accent)",
  activeShadow: "0 0 0 2px var(--st-color-focus)",
};

export const sharedComponentTheme: ThemeConfig["components"] = {
  Button: {
    defaultColor: "var(--st-color-text-primary)",
    defaultBorderColor: "var(--st-color-border)",
    defaultHoverColor: "var(--st-color-text-primary)",
    defaultHoverBg: "var(--st-color-hover)",
    defaultHoverBorderColor: "var(--st-color-accent)",
    defaultActiveColor: "var(--st-color-text-primary)",
    defaultActiveBg: "var(--st-color-selected)",
    defaultActiveBorderColor: "var(--st-color-accent)",
    primaryShadow: "none",
    dangerShadow: "none",
  },
  Input: fieldTokens,
  InputNumber: fieldTokens,
  DatePicker: fieldTokens,
  Select: {
    activeBorderColor: fieldTokens.activeBorderColor,
    hoverBorderColor: fieldTokens.hoverBorderColor,
    activeOutlineColor: "var(--st-color-focus)",
    optionActiveBg: "var(--st-color-hover)",
    optionSelectedBg: "var(--st-color-selected)",
    optionSelectedColor: "var(--st-color-text-primary)",
    optionFontSize: 14,
  },
  Table: {
    headerBg: "var(--st-color-bg-elevated)",
    headerColor: "var(--st-color-text-primary)",
    headerSplitColor: "transparent",
    borderColor: "var(--st-color-divider)",
    rowHoverBg: "var(--st-color-hover)",
    rowSelectedBg: "var(--st-color-selected)",
    rowSelectedHoverBg: "var(--st-color-selected)",
    cellFontSize: 14,
    cellFontSizeMD: 14,
    cellFontSizeSM: 14,
  },
  Modal: {
    contentBg: "var(--st-color-bg-panel)",
    headerBg: "var(--st-color-bg-panel)",
    footerBg: "var(--st-color-bg-panel)",
    titleColor: "var(--st-color-text-primary)",
    titleFontSize: 16,
  },
};
