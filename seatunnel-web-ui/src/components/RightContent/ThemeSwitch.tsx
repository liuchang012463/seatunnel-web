import { MoonOutlined, SunOutlined } from "@ant-design/icons";
import { Tooltip } from "antd";
import React from "react";
import { useNavTheme } from "@/components/ThemeConfigProvider";
import { isDarkNavTheme } from "@/theme";

const ThemeSwitch: React.FC = () => {
  const { navTheme, toggleNavTheme } = useNavTheme();
  const isDark = isDarkNavTheme(navTheme);

  return (
    <Tooltip title={isDark ? "切换浅色模式" : "切换暗黑模式"}>
      <button
        type="button"
        aria-label={isDark ? "切换浅色模式" : "切换暗黑模式"}
        onClick={toggleNavTheme}
        className="theme-switch"
      >
        {isDark ? <SunOutlined /> : <MoonOutlined />}
      </button>
    </Tooltip>
  );
};

export default ThemeSwitch;
