import { InfoCircleOutlined, ReloadOutlined } from "@ant-design/icons";
import { Button } from "antd";
import React, { memo } from "react";

interface FieldMapperInitGuideProps {
  onRefresh?: () => void;
}

const steps = [
  "打开上游 Source 节点",
  "选择表或填写 Query",
  "点击字段解析",
];

function FieldMapperInitGuide({ onRefresh }: FieldMapperInitGuideProps) {
  return (
    <div className="rounded-[18px] border border-[var(--st-color-border)] bg-[var(--st-color-bg-elevated)] p-3 shadow-[0_10px_28px_rgba(0,25,34,0.16)]">
      <div className="rounded-[14px] border border-[var(--st-color-divider)] bg-[var(--st-color-bg-panel)] px-3.5 py-3.5">
        <div className="flex items-start gap-3">
          <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border border-[var(--st-color-border)] bg-[var(--st-color-primary)] text-sm text-[var(--st-color-bg-primary)] shadow-[0_6px_16px_rgba(33,135,168,0.22)]">
            <InfoCircleOutlined />
          </div>

          <div className="min-w-0 flex-1">
            <div className="text-[15px] font-semibold leading-5 text-[var(--st-color-text-primary)]">
              先解析上游字段
            </div>

            <div className="mt-1 text-xs leading-5 text-[var(--st-color-text-secondary)]">
              在上游节点完成字段解析后，这里会自动显示字段映射。
            </div>
          </div>
        </div>

        <div className="mt-4 space-y-2">
          {steps.map((step, index) => (
            <div
              key={step}
              className="flex items-center gap-3 rounded-xl border border-[var(--st-color-border)] bg-[var(--st-color-bg-control)] px-3 py-2.5 transition-colors duration-200 hover:border-[var(--st-color-accent)] hover:bg-[var(--st-color-hover)]"
            >
              <div className="flex h-6 w-6 shrink-0 items-center justify-center rounded-lg border border-[var(--st-color-divider)] bg-[var(--st-color-selected)] text-[11px] font-semibold text-[var(--st-color-accent)]">
                {index + 1}
              </div>

              <div className="text-[13px] font-medium leading-5 text-[var(--st-color-text-primary)]">
                {step}
              </div>
            </div>
          ))}
        </div>

        <div className="mt-4 flex justify-start">
          <Button
            icon={<ReloadOutlined />}
            onClick={onRefresh}
            className="!h-8 !rounded-full !border-[var(--st-color-border)] !bg-[var(--st-color-bg-control)] !px-3.5 !text-xs !font-semibold !text-[var(--st-color-text-primary)] shadow-sm hover:!border-[var(--st-color-accent)] hover:!bg-[var(--st-color-hover)] hover:!text-[var(--st-color-accent)]"
          >
            重新检测
          </Button>
        </div>
      </div>
    </div>
  );
}

export default memo(FieldMapperInitGuide);
