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
      />
    ),
  },
  // 映射区文案已合同化；mapping tab 暂未启用，启用后渲染此组件
  // {
  //   key: "mapping",
  //   label: "逻辑关系配置",
  //   content: <MappingConfigContent />,
  // },
  {
    key: "env",
    label: "环境",
    content: <EnvConfigContent value={envConfig} onChange={setEnvConfig} />,
  },
];
