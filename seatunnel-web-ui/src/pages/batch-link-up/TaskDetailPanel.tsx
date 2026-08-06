import { useIntl } from "@umijs/max";
import { Tabs } from "antd";
import React, { useState } from "react";

import BasicInfoSection from "./BasicInfoSection";
import TaskLogObservability from "./components/TaskLogObservability";
import HoconTab from "./tabs/HoconTab";
import MetricsTab from "./tabs/MetricsTab";
import ScheduleTab from "./tabs/ScheduleTab";
import TableTab from "./tabs/TableTab";
import TaskHeader from "./TaskHeader";

interface TaskDetailPanelProps {
  instanceItem: any;
}

const TaskDetailPanel: React.FC<TaskDetailPanelProps> = ({ instanceItem }) => {
  const intl = useIntl();

  const [activeKey, setActiveKey] = useState<string>("log");

  if (!instanceItem?.jobStatus) {
    return (
      <div className="flex h-full items-center justify-center text-base text-slate-400">
        {intl.formatMessage({
          id: "pages.job.detail.empty",
          defaultMessage:
            "Please select a run record on the left to view details",
        })}{" "}
        😊
      </div>
    );
  }

  const showTableTab = ["GUIDE_SINGLE", "GUIDE_SINGLE_INCREMENTAL", "GUIDE_MULTI"].includes(
    instanceItem?.definitionMode
  );

  const tabs = [
    {
      key: "log",
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.log",
        defaultMessage: "Log",
      }),
      children: <TaskLogObservability instanceItem={instanceItem} jobMode="BATCH" />,
    },
    {
      key: "hocon",
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.hocon",
        defaultMessage: "Hocon",
      }),
      children: <HoconTab config={instanceItem.runtimeConfig} />,
    },
    {
      key: "metrics",
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.metrics",
        defaultMessage: "Metrics",
      }),
      children: <MetricsTab instanceItem={instanceItem} />,
    },
    {
      key: "schedule",
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.schedule",
        defaultMessage: "Scheduled",
      }),
      children: <ScheduleTab instanceItem={instanceItem} />,
    },
    ...(showTableTab
      ? [
          {
            key: "table",
            label: intl.formatMessage({
              id: "pages.job.detail.tabs.table",
              defaultMessage: "Table",
            }),
            children: <TableTab instanceItem={instanceItem} />,
          },
        ]
      : []),
  ];

  return (
    <div className="h-full bg-slate-50">
      <TaskHeader item={instanceItem} />

      <div className="h-[calc(100vh-46px)] overflow-y-auto bg-slate-50">
        <BasicInfoSection item={instanceItem} />

        <div className="m-4 rounded-lg bg-white p-4 shadow-[0_1px_3px_rgba(15,23,42,0.04)]">
          <Tabs
            activeKey={activeKey}
            items={tabs}
            onChange={(key) => setActiveKey(key)}
          />
        </div>
      </div>
    </div>
  );
};

export default TaskDetailPanel;
