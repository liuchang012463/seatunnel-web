import React from "react";
import { BulbOutlined } from "@ant-design/icons";
import { Typography } from "antd";
import { BLUE } from "../constants/ui";

const { Title } = Typography;

const PageHeader: React.FC = () => {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        gap: 16,
        marginBottom: 16,
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
        <div
          style={{
            width: 48,
            height: 48,
            borderRadius: 16,
            background: "var(--st-color-selected)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            color: BLUE,
            fontSize: 20,
            flexShrink: 0,
          }}
        >
          <BulbOutlined aria-hidden />
        </div>

        <div>
          <Title
            level={1}
            style={{
              margin: 0,
              fontSize: 28,
              lineHeight: "40px",
              color: "var(--st-color-text-primary)",
            }}
          >
            知识管理
          </Title>
        </div>
      </div>
    </div>
  );
};

export default PageHeader;
