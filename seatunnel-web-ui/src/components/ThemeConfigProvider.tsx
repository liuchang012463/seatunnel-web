import { App as AntdApp, ConfigProvider, theme as antdTheme } from "antd";
import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import {
  applyNavTheme,
  isDarkNavTheme,
  persistNavTheme,
  setNavTheme,
  type NavTheme,
} from "@/theme";
import { registerAntdFeedback } from "@/utils/antdFeedback";
import { darkToken, lightToken } from "@/styles/theme-tokens";
import { sharedComponentTheme } from "@/styles/antd-theme";

/**
 * DESIGN.md §6.2 antd 算法正规化 + §6.4 浅色 v2：
 * 运行时 ConfigProvider 统一走 theme.algorithm 派生映射令牌；
 * 主题状态挂在 rootContainer 顶部的 Context 上（getInitialState 只负责
 * 写入初始值，避免打包分包导致模块级 store 出现多份实例）。
 */

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

const getInitialNavTheme = (): NavTheme => {
  // 浅色 v2 尚未完成前，所有入口都从深色值班台开始，避免读取旧存储值造成主题闪烁。
  const initialTheme: NavTheme = "realDark";
  setNavTheme(initialTheme);
  applyNavTheme(initialTheme);
  return initialTheme;
};

interface ThemeConfigProviderProps {
  children: ReactNode;
}

const AntdFeedbackBridge: React.FC<{ children: ReactNode }> = ({ children }) => {
  const { message, notification } = AntdApp.useApp();
  useEffect(() => {
    registerAntdFeedback({ message, notification });
  }, [message, notification]);
  return <>{children}</>;
};

const ThemeConfigProvider: React.FC<ThemeConfigProviderProps> = ({
  children,
}) => {
  const [navTheme, setNavThemeState] = useState<NavTheme>(getInitialNavTheme);

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
          token: {
            ...(isDark ? darkToken : lightToken),
            fontSizeSM: 14,
            fontSizeHeading5: 14,
          },
          components: {
            ...sharedComponentTheme,
          },
        }}
      >
        <AntdApp>
          <AntdFeedbackBridge>{children}</AntdFeedbackBridge>
        </AntdApp>
      </ConfigProvider>
    </NavThemeContext.Provider>
  );
};

export default ThemeConfigProvider;
