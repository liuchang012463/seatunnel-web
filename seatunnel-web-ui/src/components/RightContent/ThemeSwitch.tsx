import { MoonOutlined, SunOutlined } from "@ant-design/icons";
import { useModel } from "@umijs/max";
import { Tooltip } from "antd";
import React from "react";
import { useNavTheme } from "@/components/ThemeConfigProvider";
import { isDarkNavTheme, type NavTheme } from "@/theme";

const ThemeSwitch: React.FC = () => {
  const { navTheme, toggleNavTheme } = useNavTheme();
  const { initialState, setInitialState } = useModel("@@initialState");
  const isDark = isDarkNavTheme(navTheme);

  const handleToggle = () => {
    toggleNavTheme();

    // ProLayout 侧栏（含折叠按钮）读 initialState.settings.navTheme，需同步
    const nextTheme: NavTheme = isDark ? "light" : "realDark";
    void setInitialState((prev) => ({
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
        onClick={handleToggle}
        className="theme-switch"
      >
        {isDark ? <SunOutlined /> : <MoonOutlined />}
      </button>
    </Tooltip>
  );
};

export default ThemeSwitch;
