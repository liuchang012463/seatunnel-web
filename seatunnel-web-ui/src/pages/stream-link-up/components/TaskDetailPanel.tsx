import { useIntl } from "@umijs/max";
import { Tabs } from "antd";
import React, { useState } from "react";

import BasicInfoSection from "@/pages/batch-link-up/BasicInfoSection";
import TaskLogObservability from "@/pages/batch-link-up/components/TaskLogObservability";
import TaskHeader from "@/pages/batch-link-up/TaskHeader";
import HoconTab from "./tabs/HoconTab";
import MetricsTab from "./tabs/MetricsTab";
import TableTab from "./tabs/TableTab";

interface TaskDetailPanelProps {
  instanceItem: any;
}

const TaskDetailPanel: React.FC<TaskDetailPanelProps> = ({ instanceItem }) => {
  const intl = useIntl();

  const [activeKey, setActiveKey] = useState<string>("operation");

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

  const showTableTab = ["GUIDE_SINGLE", "GUIDE_MULTI"].includes(
    instanceItem?.definitionMode
  );

  const observabilityTabs = [
    {
      key: "operation",
      view: "operation" as const,
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.operationRecords",
        defaultMessage: "操作行为记录",
      }),
    },
    {
      key: "snapshot",
      view: "snapshot" as const,
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.dataSnapshots",
        defaultMessage: "数据读取快照",
      }),
    },
    {
      key: "execution",
      view: "execution" as const,
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.executionFlow",
        defaultMessage: "执行流程日志",
      }),
    },
    {
      key: "timeline",
      view: "timeline" as const,
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.operationTimeline",
        defaultMessage: "操作时序记录",
      }),
    },
    {
      key: "replay",
      view: "replay" as const,
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.operationReplay",
        defaultMessage: "操作可视化回放",
      }),
    },
    {
      key: "diagnosis",
      view: "diagnosis" as const,
      label: intl.formatMessage({
        id: "pages.job.detail.tabs.aiDiagnosis",
        defaultMessage: "AI故障定位",
      }),
    },
  ];

  const tabs = [
    ...observabilityTabs.map((tab) => ({
      key: tab.key,
      label: tab.label,
      children: <TaskLogObservability instanceItem={instanceItem} jobMode="STREAMING" view={tab.view} />,
    })),
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
