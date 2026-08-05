import { CaretRightOutlined } from "@ant-design/icons";
import type { CollapseProps } from "antd";
import { Collapse } from "antd";
import "./index.less";

import ScheduleParamsSection from "./components/ScheduleParamsSection";
import ScheduleStrategySection from "./components/ScheduleStrategySection";
import ScheduleTimeSection from "./components/ScheduleTimeSection";
import SectionLabel from "./components/SectionLabel";
import { resolveExecutionMode } from "./types";

interface Props {
  value?: any;
  onChange?: (value: any) => void;
  forceAuto?: boolean;
}

export default function ScheduleConfigContent({
  value,
  onChange,
  forceAuto = false,
}: Props) {
  const executionMode = resolveExecutionMode(value, forceAuto);
  const isAuto = executionMode === "AUTO";

  const updateSchedule = (patch: Record<string, any>) => {
    onChange?.((prev: any) => ({
      ...prev,
      ...patch,
      executionMode: forceAuto ? "AUTO" : patch.executionMode || prev?.executionMode,
      ...(patch.executionMode === "MANUAL"
        ? { scheduleRunType: "pause", cronExpression: undefined }
        : {}),
    }));
  };

  const items: CollapseProps["items"] = [
    {
      key: "scheduleParams",
      label: (
        <SectionLabel title="调度参数" tooltip="配置任务运行时使用的调度参数" />
      ),
      children: (
        <ScheduleParamsSection
          value={value}
          onChange={updateSchedule}
        />
      ),
    },
    {
      key: "scheduleStrategy",
      label: (
        <SectionLabel
          title="调度策略"
          tooltip="配置实例生成、资源组和重跑策略"
        />
      ),
      children: (
        <ScheduleStrategySection
          value={value}
          onChange={updateSchedule}
          forceAuto={forceAuto}
        />
      ),
    },
    ...(isAuto
      ? [
          {
            key: "scheduleTime",
            label: (
              <SectionLabel title="调度时间" tooltip="配置周期、时间和生效规则" />
            ),
            children: (
              <ScheduleTimeSection value={value} onChange={updateSchedule} />
            ),
          },
        ]
      : []),
  ];

  return (
    <div className="schedule-config-content">
      <div className="schedule-config-content__scroll">
        <Collapse
          ghost
          defaultActiveKey={[
            "scheduleParams",
            "scheduleStrategy",
            "scheduleTime",
          ]}
          expandIconPosition="start"
          className="schedule-config-collapse"
          expandIcon={({ isActive }) => (
            <CaretRightOutlined
              className={`schedule-config-collapse__arrow ${
                isActive ? "is-active" : ""
              }`}
            />
          )}
          items={items}
        />
      </div>
    </div>
  );
}
