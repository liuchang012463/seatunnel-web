import BasicConfigContent from "./BasicConfigContent";
import ScheduleConfigContent from "./components/ScheduleConfigContent";
import { BasicConfig } from "./components/ScheduleConfigContent/types";
import EnvConfigContent from "./EnvConfigContent";
import type { TabDefinition } from "./types";

export const getTabDefinitions = (
  params?: any,
  basicConfig?: BasicConfig,
  setBasicConfig?: React.Dispatch<React.SetStateAction<BasicConfig>>,
  scheduleConfig?: any,
  setScheduleConfig?: React.Dispatch<React.SetStateAction<any>>,
  envConfig?: any,
  setEnvConfig?: any
): TabDefinition[] => [
  {
    key: "basic",
    label: "基础",
    content: (
      <BasicConfigContent value={basicConfig} onChange={setBasicConfig} />
    ),
  },
  {
    key: "schedule",
    label: "调度",
    content: (
      <ScheduleConfigContent
        value={scheduleConfig}
        onChange={setScheduleConfig}
        isIncremental={basicConfig?.mode === "GUIDE_SINGLE_INCREMENTAL"}
      />
    ),
  },
  {
    key: "env",
    label: "环境",
    content: <EnvConfigContent value={envConfig} onChange={setEnvConfig} />,
  },
];
