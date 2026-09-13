import { ConfigProvider, theme as antdTheme } from "antd";
import {
  createContext,
  useContext,
  useState,
  type ReactNode,
} from "react";
import {
  applyNavTheme,
  getNavThemeSnapshot,
  isDarkNavTheme,
  persistNavTheme,
  setNavTheme,
  type NavTheme,
} from "@/theme";

/**
 * DESIGN.md §6.2 antd 算法正规化 + §6.4 浅色 v2：
 * 运行时 ConfigProvider 统一走 theme.algorithm 派生映射令牌；
 * 主题状态挂在 rootContainer 顶部的 Context 上（getInitialState 只负责
 * 写入初始值，避免打包分包导致模块级 store 出现多份实例）。
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

const LIGHT_TOKEN = {
  colorPrimary: "#1B87A8",
  colorInfo: "#0E7FA6",
  colorLink: "#0E7FA6",
  colorSuccess: "#2F9E44",
  colorWarning: "#B88700",
  colorError: "#C23B3B",
  colorBgBase: "#F7F9FC",
  colorBgLayout: "#F7F9FC",
  colorBgContainer: "#FFFFFF",
  colorBgElevated: "#FFFFFF",
  colorText: "#1F2329",
  colorTextSecondary: "#5B6B82",
  colorTextTertiary: "#8A94A6",
  colorBorder: "rgba(31, 35, 41, 0.16)",
  colorBorderSecondary: "rgba(31, 35, 41, 0.08)",
  colorSplit: "rgba(31, 35, 41, 0.06)",
  borderRadius: 3,
  controlHeight: 32,
  fontFamily: ST_FONT_FAMILY,
  fontSize: 14,
};

const LIGHT_COMPONENTS = {
  Layout: {
    bodyBg: "#F7F9FC",
    headerBg: "#FFFFFF",
    siderBg: "#FFFFFF",
  },
  Table: {
    colorBgContainer: "#FFFFFF",
    headerBg: "#F8FAFC",
    headerColor: "#344054",
    headerSplitColor: "transparent",
    rowHoverBg: "rgba(27, 135, 168, 0.06)",
    rowSelectedBg: "rgba(27, 135, 168, 0.10)",
  },
  Menu: {
    itemBg: "#FFFFFF",
    subMenuItemBg: "#FFFFFF",
    itemColor: "#5B6B82",
    itemHoverColor: "#0E7FA6",
    itemHoverBg: "rgba(27, 135, 168, 0.06)",
    itemSelectedColor: "#0E7FA6",
    itemSelectedBg: "rgba(27, 135, 168, 0.10)",
  },
};

interface NavThemeContextValue {
  navTheme: NavTheme;
  toggleNavTheme: () => void;
}

const NavThemeContext = createContext<NavThemeContextValue>({
  navTheme: "realDark",
  toggleNavTheme: () => {},
});

export const useNavTheme = (): NavThemeContextValue =>
  useContext(NavThemeContext);

interface ThemeConfigProviderProps {
  children: ReactNode;
}

const ThemeConfigProvider: React.FC<ThemeConfigProviderProps> = ({
  children,
}) => {
  const [navTheme, setNavThemeState] = useState<NavTheme>(
    () => getNavThemeSnapshot()
  );

  const toggleNavTheme = () => {
    const next: NavTheme = isDarkNavTheme(navTheme) ? "light" : "realDark";

    setNavThemeState(next);
    setNavTheme(next);
    applyNavTheme(next);
    persistNavTheme(next);
  };

  const isDark = isDarkNavTheme(navTheme);

  return (
    <NavThemeContext.Provider value={{ navTheme, toggleNavTheme }}>
      <ConfigProvider
        theme={{
          algorithm: isDark
            ? antdTheme.darkAlgorithm
            : antdTheme.defaultAlgorithm,
          token: isDark ? DARK_TOKEN : LIGHT_TOKEN,
          components: isDark ? DARK_COMPONENTS : LIGHT_COMPONENTS,
        }}
      >
        {children}
      </ConfigProvider>
    </NavThemeContext.Provider>
  );
};

export default ThemeConfigProvider;
