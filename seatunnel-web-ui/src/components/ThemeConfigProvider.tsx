import { ConfigProvider, theme as antdTheme } from "antd";
import { useSyncExternalStore } from "react";
import React from "react";
import {
  getNavThemeSnapshot,
  isDarkNavTheme,
  subscribeNavTheme,
} from "@/theme";

/**
 * DESIGN.md §6.2 antd 算法正规化：
 * 运行时 ConfigProvider 统一走 theme.algorithm 派生映射令牌，
 * 种子值与 config.ts 静态主题、design-system.less §3 v2 保持一致。
 * 主题状态来自 theme.ts 的外部 store（rootContainer 在 ModelProvider 外）。
 */

const ST_FONT_FAMILY =
  '"MiSans", "HarmonyOS Sans SC", "Source Han Sans SC", "Microsoft YaHei", "微软雅黑", Arial, sans-serif';

const DARK_TOKEN = {
  colorPrimary: "#1B87A8",
  colorInfo: "#3FC6FF",
  colorLink: "#3FC6FF",
  colorSuccess: "#3DD68C",
  colorWarning: "#F5B83D",
  colorError: "#FF6B5E",
  colorBgBase: "#01151D",
  colorBgLayout: "#02222D",
  colorBgContainer: "#052F3F",
  colorBgElevated: "#0A3D52",
  colorText: "#EDF4F7",
  colorTextSecondary: "#AFC4CD",
  colorTextTertiary: "#6C8792",
  colorBorder: "rgba(126, 183, 208, 0.30)",
  colorBorderSecondary: "rgba(126, 183, 208, 0.14)",
  colorSplit: "rgba(126, 183, 208, 0.14)",
  borderRadius: 3,
  controlHeight: 32,
  fontFamily: ST_FONT_FAMILY,
  fontSize: 14,
};

const DARK_COMPONENTS = {
  Layout: {
    bodyBg: "#01151D",
    headerBg: "#052F3F",
    siderBg: "#01151D",
  },
  Table: {
    colorBgContainer: "#052F3F",
    headerBg: "#2187A8",
    headerColor: "#FFFFFF",
    headerSplitColor: "transparent",
    rowHoverBg: "rgba(63, 198, 255, 0.08)",
    rowSelectedBg: "rgba(63, 198, 255, 0.14)",
  },
  Menu: {
    darkItemBg: "#01151D",
    darkSubMenuItemBg: "#01151D",
    darkItemColor: "#AFC4CD",
    darkItemHoverColor: "#EDF4F7",
    darkItemHoverBg: "rgba(63, 198, 255, 0.08)",
    darkItemSelectedColor: "#3FC6FF",
    darkItemSelectedBg: "rgba(63, 198, 255, 0.14)",
  },
};

interface ThemeConfigProviderProps {
  children: React.ReactNode;
}

const ThemeConfigProvider: React.FC<ThemeConfigProviderProps> = ({
  children,
}) => {
  const navTheme = useSyncExternalStore(
    subscribeNavTheme,
    getNavThemeSnapshot,
    getNavThemeSnapshot
  );
  const isDark = isDarkNavTheme(navTheme);

  return (
    <ConfigProvider
      theme={{
        algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: DARK_TOKEN,
        components: DARK_COMPONENTS,
      }}
    >
      {children}
    </ConfigProvider>
  );
};

export default ThemeConfigProvider;
