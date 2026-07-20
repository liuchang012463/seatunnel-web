import BasicConfigContent from "./BasicConfigContent";
import ScheduleConfigContent from "./components/ScheduleConfigContent";
import { BasicConfig } from "./components/ScheduleConfigContent/types";
import EnvConfigContent from "./EnvConfigContent";
import MappingConfigContent from "./MappingConfigContent";
import type { TabDefinition } from "./types";

export const getTabDefinitions = (
  params?: any,
  basicConfig?: BasicConfig,
  setBasicConfig?: React.Dispatch<React.SetStateAction<BasicConfig>>,
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
  // 已启用 — 合同化「逻辑关系配置」tab
  {
    key: "mapping",
    label: "逻辑关系配置",
    content: <MappingConfigContent />,
  },
  // {
  //   key: "schedule",
  //   label: "调度",
  //   content: (
  //     <ScheduleConfigContent
  //       value={scheduleConfig}
  //       onChange={setScheduleConfig}
  //     />
  //   ),
  // },
  {
    key: "env",
    label: "环境",
    content: <EnvConfigContent value={envConfig} onChange={setEnvConfig} />,
  },
];
