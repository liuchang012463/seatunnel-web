import React from "react";
import { Button } from "antd";
import { CloudServerOutlined, PlusOutlined } from "@ant-design/icons";
import { BLUE, TEXT_SECONDARY } from "../constants";

interface Props {
  onAdd: () => void;
}

const ClientPageHeader: React.FC<Props> = ({ onAdd }) => {
  return (
    <div className="mb-1 flex items-start justify-between gap-4">
      <div className="mb-4 flex items-center gap-4">
        <div
          className="flex items-center justify-center text-[color:var(--st-color-accent)]"
          style={{
            backgroundColor: "var(--st-color-selected)",
            height: 44,
            width: 44,
            fontSize: 20,
            borderRadius: 14,
          }}
        >
          <CloudServerOutlined />
        </div>

        <div>
          <h1
            className="m-0 font-bold tracking-tight text-[color:var(--st-color-text-primary)]"
            style={{ fontSize: 18, lineHeight: "26px" }}
          >
            引接引擎管理
          </h1>
          <p className="mt-1 text-[color:var(--st-color-text-muted)]" style={{ fontSize: 13 }}>
            管理 SeaTunnel / Zeta Client，查看节点健康状态与核心资源指标，让任务提交与运行监控更清晰。
          </p>
        </div>
      </div>

      <Button
        type="primary"
        icon={<PlusOutlined />}
        size="large"
        onClick={onAdd}
        className="h-10 rounded-full px-5 shadow-[0_6px_16px_rgba(63,92,214,0.18)]"
        style={{ background: BLUE, borderColor: BLUE }}
      >
        新建 Client
      </Button>
    </div>
  );
};

export default ClientPageHeader;
