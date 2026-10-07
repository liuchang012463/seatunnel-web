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
            background: "#eef2ff",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            color: BLUE,
            fontSize: 20,
            flexShrink: 0,
          }}
        >
          <BulbOutlined />
        </div>

        <div>
          <Title
            level={2}
            style={{
              margin: 0,
              fontSize: 20,
              lineHeight: "32px",
              color: "#101828",
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
