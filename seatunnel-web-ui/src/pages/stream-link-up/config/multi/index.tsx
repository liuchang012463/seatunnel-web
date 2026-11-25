import { history, useLocation, useParams } from "@umijs/max";
import { useCallback, useState } from "react";
import TaskConfigStatusPanel from "@/pages/common/components/TaskConfigStatusPanel";
import useTaskConfig, { cachedOrSavedScene } from "@/pages/common/hooks/useTaskConfig";
import type { ConfigPageScene } from "@/pages/common/utils/configPageScene";
import { seatunnelJobDefinitionApi } from "../../api";
import {
  BasicConfig,
  defaultEnvConfig,
  EnvConfig,
  ScheduleConfig,
} from "../../workflow/components/ScheduleConfigContent/types";
import MultiWorkflow from "./MultiWorkflow";
import {
  buildUnpublishedJobDefinitionState,
  normalizeJobDefinitionState,
} from "./jobDefinitionState";

const defaultScheduleConfig: ScheduleConfig = {
  paramsList: [],
  instanceGenerateMode: "nextDay",
  scheduleRunType: "pause",
  timeoutMode: "system",
  timeoutValue: 1,
  timeoutUnit: "hour",
  rerunPolicy: "success_or_fail",
  autoRetry: true,
  retryTimes: 1,
  retryInterval: 1,
  scheduleType: "day",
  hourMode: "range",
  hourlyRangeValue: {
    startTime: "00:00",
    intervalHour: 1,
    endTime: "23:59",
  },
  hourlyAppointValue: {
    hours: [0],
    minute: "00",
  },
  dailyValue: {
    time: "00:17",
  },
  weeklyValue: {
    weekdays: ["MON"],
    time: "00:17",
  },
  effectType: "forever",
  cronExpression: "0 17 0 * * ?",
};

const defaultBasicConfig: BasicConfig = {
  jobName: "",
  jobDesc: "",
  clientId: "",
  mode: "GUIDE_MULTI",
  sourceType: "SOURCE",
  targetType: "SINK",
  sourceDataSourceId: "",
  targetDataSourceId: "",
};

const mergeScheduleConfig = (schedule?: any): ScheduleConfig => {
  return {
    ...defaultScheduleConfig,
    ...(schedule || {}),
    hourlyRangeValue: {
      ...defaultScheduleConfig.hourlyRangeValue,
      ...(schedule?.hourlyRangeValue || {}),
    },
    hourlyAppointValue: {
      ...defaultScheduleConfig.hourlyAppointValue,
      ...(schedule?.hourlyAppointValue || {}),
    },
    dailyValue: {
      ...defaultScheduleConfig.dailyValue,
      ...(schedule?.dailyValue || {}),
    },
    weeklyValue: {
      ...defaultScheduleConfig.weeklyValue,
      ...(schedule?.weeklyValue || {}),
    },
  };
};

const buildInitialScheduleConfigForCreate = (
  rawData?: any
): ScheduleConfig => {
  return mergeScheduleConfig(rawData?.scheduleConfig || rawData?.schedule);
};

const buildInitialScheduleConfigForEdit = (
  editData?: any
): ScheduleConfig => {
  return mergeScheduleConfig(editData?.schedule);
};

const buildInitialEnvConfigForCreate = (rawData?: any): EnvConfig => {
  return {
    ...defaultEnvConfig,
    ...(rawData?.env || rawData?.envConfig || {}),
  };
};

const buildInitialEnvConfigForEdit = (editData?: any): EnvConfig => {
  return {
    ...defaultEnvConfig,
    ...(editData?.env || {}),
  };
};

const buildInitialBasicConfigForCreate = (rawData?: any): BasicConfig => {
  return {
    ...defaultBasicConfig,
    jobName: rawData?.jobName || "",
    jobDesc: rawData?.jobDesc || "",
    clientId: rawData?.clientId || "",
    mode: rawData?.mode || "GUIDE_MULTI",
    sourceType: rawData?.sourceType?.dbType || "SOURCE",
    targetType: rawData?.targetType?.dbType || "SINK",
    sourceDataSourceId: rawData?.sourceDataSourceId || rawData?.sourceId || "",
    targetDataSourceId: rawData?.targetDataSourceId || rawData?.targetId || "",
  };
};

const buildInitialBasicConfigForEdit = (editData?: any): BasicConfig => {
  const basic = editData?.basic || {};
  const workflow = editData?.workflow || {};
  const content = editData?.content || {};

  const source = workflow?.source || content?.source || {};
  const target = workflow?.target || content?.target || {};

  return {
    ...defaultBasicConfig,
    jobName: basic?.jobName || "",
    jobDesc: basic?.jobDesc || "",
    clientId: basic?.clientId ? String(basic.clientId) : "",
    mode: basic?.mode || editData?.mode || "GUIDE_MULTI",

    sourceType:
      workflow?.sourceType?.dbType ||
      source?.dbType ||
      editData?.sourceType?.dbType ||
      "SOURCE",

    targetType:
      workflow?.targetType?.dbType ||
      target?.dbType ||
      editData?.targetType?.dbType ||
      "SINK",

    sourceDataSourceId:
      workflow?.sourceDataSourceId ||
      workflow?.sourceId ||
      source?.datasourceId ||
      "",

    targetDataSourceId:
      workflow?.targetDataSourceId ||
      workflow?.targetId ||
      target?.datasourceId ||
      "",
  };
};

const buildPageParamsForCreate = (rawData?: any) => {
  return {
    ...(rawData || {}),
    mode: rawData?.mode || "GUIDE_MULTI",
    state: normalizeJobDefinitionState(
      rawData?.state || buildUnpublishedJobDefinitionState()
    ),
    workflow: {
      ...(rawData?.workflow || rawData?.content || {}),
    },
    basic: {
      ...(rawData?.basic || {}),
    },
    schedule: rawData?.schedule || rawData?.scheduleConfig,
    scheduleConfig: rawData?.scheduleConfig || rawData?.schedule,
    env: rawData?.env || rawData?.envConfig,
  };
};

const buildPageParamsForEdit = (editData?: any) => {
  const basic = editData?.basic || {};
  const workflow = editData?.workflow || {};
  const content = editData?.content || {};
  const schedule = editData?.schedule || {};
  const env = editData?.env || {};

  const source = workflow?.source || content?.source || {};
  const target = workflow?.target || content?.target || {};

  return {
    id: editData?.id,
    mode: editData?.mode || basic?.mode || "GUIDE_MULTI",
    jobName: basic?.jobName || "",
    jobDesc: basic?.jobDesc || "",
    clientId: basic?.clientId || "",

    state: normalizeJobDefinitionState(editData?.state),

    sourceType:
      workflow?.sourceType ||
      editData?.sourceType || {
        dbType: source?.dbType,
        connectorType: source?.connectorType,
        pluginName: source?.pluginName,
      },

    targetType:
      workflow?.targetType ||
      editData?.targetType || {
        dbType: target?.dbType,
        connectorType: target?.connectorType,
        pluginName: target?.pluginName,
      },

    sourceDataSourceId:
      workflow?.sourceDataSourceId ||
      workflow?.sourceId ||
      source?.datasourceId ||
      "",

    targetDataSourceId:
      workflow?.targetDataSourceId ||
      workflow?.targetId ||
      target?.datasourceId ||
      "",

    scheduleConfig: schedule,
    schedule,
    workflow: {
      ...workflow,
      ...content,
    },
    basic,
    content,
    env,
  };
};

export default function MultiConfigPage() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();

  const [params, setParams] = useState<any>(null);
  const [scheduleConfig, setScheduleConfig] = useState<ScheduleConfig>(
    defaultScheduleConfig
  );
  const [envConfig, setEnvConfig] = useState<EnvConfig>(defaultEnvConfig);
  const [basicConfig, setBasicConfig] =
    useState<BasicConfig>(defaultBasicConfig);
  const initialize = useCallback((data: any, source: ConfigPageScene) => {
    if (source === "create") {
      setParams(buildPageParamsForCreate(data));
      setBasicConfig(buildInitialBasicConfigForCreate(data));
      setScheduleConfig(buildInitialScheduleConfigForCreate(data));
      setEnvConfig(buildInitialEnvConfigForCreate(data));
    } else {
      setParams(buildPageParamsForEdit(data));
      setBasicConfig(buildInitialBasicConfigForEdit(data));
      setScheduleConfig(buildInitialScheduleConfigForEdit(data));
      setEnvConfig(buildInitialEnvConfigForEdit(data));
    }
  }, []);
  const { status, retry } = useTaskConfig({
    id,
    scene: cachedOrSavedScene(new URLSearchParams(location.search).get("scene")),
    draftKey: `stream-link-up-detail-${id}`,
    readSaved: seatunnelJobDefinitionApi.selectEditDetail,
    readReleaseState: seatunnelJobDefinitionApi.selectById,
    initialize,
  });

  const goBack = () => {
    history.push("/sync/stream-link-up");
  };

  if (status !== "ready") {
    return <TaskConfigStatusPanel status={status} onBack={goBack} onRetry={retry} />;
  }

  return (
    <div className="min-h-screen bg-white">
      <MultiWorkflow
        params={params}
        setParams={setParams}
        goBack={goBack}
        basicConfig={basicConfig}
        setBasicConfig={setBasicConfig}
        scheduleConfig={scheduleConfig}
        setScheduleConfig={setScheduleConfig}
        envConfig={envConfig}
        setEnvConfig={setEnvConfig}
      />
    </div>
  );
}
