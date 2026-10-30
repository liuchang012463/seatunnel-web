import React from "react";
import { MenuKey } from "../types";
import { BLUE, BLUE_LIGHT, BORDER_COLOR, menuList } from "../constants/ui";

interface Props {
  activeMenu: MenuKey;
  onChange: (key: MenuKey) => void;
  onResetSelection: () => void;
}

const SideNav: React.FC<Props> = ({
  activeMenu,
  onChange,
  onResetSelection,
}) => {
  return (
    <div
      style={{
        borderRight: `1px solid ${BORDER_COLOR}`,
        background: "var(--st-color-bg-control)",
        padding: 16,
        boxSizing: "border-box",
        minHeight: 0,
        overflow: "auto",
      }}
    >
      <div
        style={{
          fontSize: 14,
          fontWeight: 600,
          color: "var(--st-color-text-primary)",
          marginBottom: 14,
        }}
      >
        知识分类
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        {menuList.map((item) => {
          const active = item.key === activeMenu;

          return (
            <div
              key={item.key}
              onClick={() => {
                onChange(item.key);
                onResetSelection();
              }}
              style={{
                borderRadius: 8,
                padding: "12px 14px",
                cursor: "pointer",
                border: active
                  ? "1px solid var(--st-color-accent)"
                  : `1px solid ${BORDER_COLOR}`,
                background: active ? BLUE_LIGHT : "var(--st-color-bg-panel)",
                transition: "all 0.2s ease",
              }}
            >
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                }}
              >
                <span
                  style={{
                    width: 28,
                    height: 28,
                    borderRadius: 8,
                    display: "inline-flex",
                    alignItems: "center",
                    justifyContent: "center",
                    background: active
                      ? "var(--st-color-selected)"
                      : "var(--st-color-bg-elevated)",
                    color: active ? BLUE : "var(--st-color-text-secondary)",
                    fontSize: 14,
                  }}
                >
                  {item.icon}
                </span>

                <span
                  style={{
                    fontSize: 14,
                    fontWeight: 600,
                    color: active
                      ? "var(--st-color-accent)"
                      : "var(--st-color-text-primary)",
                  }}
                >
                  {item.label}
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default SideNav;
