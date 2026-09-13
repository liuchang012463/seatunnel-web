import { MoonOutlined, SunOutlined } from "@ant-design/icons";
import { Tooltip } from "antd";
import React, { useSyncExternalStore } from "react";
import {
  applyNavTheme,
  getNavThemeSnapshot,
  isDarkNavTheme,
  persistNavTheme,
  setNavTheme,
  subscribeNavTheme,
  type NavTheme,
} from "@/theme";

const ThemeSwitch: React.FC = () => {
  const navTheme = useSyncExternalStore(
    subscribeNavTheme,
    getNavThemeSnapshot,
    getNavThemeSnapshot
  );
  const isDark = isDarkNavTheme(navTheme);

  const toggleTheme = () => {
    const nextTheme: NavTheme = isDark ? "light" : "realDark";

    setNavTheme(nextTheme);
    applyNavTheme(nextTheme);
    persistNavTheme(nextTheme);
  };

  return (
    <Tooltip title={isDark ? "切换浅色模式" : "切换暗黑模式"}>
      <button
        type="button"
        aria-label={isDark ? "切换浅色模式" : "切换暗黑模式"}
        onClick={toggleTheme}
        className="theme-switch"
      >
        {isDark ? <SunOutlined /> : <MoonOutlined />}
      </button>
    </Tooltip>
  );
};

export default ThemeSwitch;
