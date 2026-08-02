import { MoonOutlined, SunOutlined } from "@ant-design/icons";
import { useModel } from "@umijs/max";
import { Tooltip } from "antd";
import React, { useEffect } from "react";
import {
  applyNavTheme,
  isDarkNavTheme,
  persistNavTheme,
  type NavTheme,
} from "@/theme";

const ThemeSwitch: React.FC = () => {
  const { initialState, setInitialState } = useModel("@@initialState");

  const isDark = isDarkNavTheme(initialState?.settings?.navTheme);

  useEffect(() => {
    applyNavTheme(isDark ? "realDark" : "light");
  }, [isDark]);

  const toggleTheme = async () => {
    const nextTheme: NavTheme = isDark ? "light" : "realDark";

    applyNavTheme(nextTheme);
    persistNavTheme(nextTheme);

    await setInitialState((prev) => ({
      ...prev,
      settings: {
        ...prev?.settings,
        navTheme: nextTheme,
      },
    }));
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
