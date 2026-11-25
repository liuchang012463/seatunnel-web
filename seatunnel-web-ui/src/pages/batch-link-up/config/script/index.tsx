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
  resolveExecutionMode,
  ScheduleConfig,
} from "../../workflow/components/ScheduleConfigContent/types";
import CustomWorkflow from "./CustomWorkflow";
import {
  buildUnpublishedJobDefinitionState,
  normalizeJobDefinitionState,
} from "./jobDefinitionState";

type CustomBasicConfig = BasicConfig & {
  sourcePluginName?: string;
  targetPluginName?: string;
};

const defaultScheduleConfig: ScheduleConfig = {
  executionMode: "MANUAL",
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
  cronExpression: undefined,
};

const defaultBasicConfig: CustomBasicConfig = {
  jobName: "",
  jobDesc: "",
  clientId: "",
  mode: "SCRIPT",
  sourceType: "SOURCE",
  targetType: "SINK",
  sourceDataSourceId: "",
  targetDataSourceId: "",
  sourcePluginName: "",
  targetPluginName: "",
};

const mergeScheduleConfig = (schedule?: any): ScheduleConfig => {
  const result = {
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
  } as ScheduleConfig;
  const executionMode = resolveExecutionMode(schedule || result);
  return {
    ...result,
    executionMode,
    cronExpression: executionMode === "MANUAL" ? undefined : result.cronExpression,
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

const buildInitialBasicConfigForCreate = (
  rawData?: any
): CustomBasicConfig => {
  return {
    ...defaultBasicConfig,
    jobName: rawData?.jobName || "",
    jobDesc: rawData?.jobDesc || "",
    clientId: rawData?.clientId ? String(rawData.clientId) : "",
    mode: "SCRIPT",

    sourceType: rawData?.sourceType?.dbType || "SOURCE",
    targetType: rawData?.targetType?.dbType || "SINK",

    sourcePluginName: rawData?.sourceType?.pluginName || "",
    targetPluginName: rawData?.targetType?.pluginName || "",

    sourceDataSourceId: rawData?.sourceDataSourceId || rawData?.sourceId || "",
    targetDataSourceId: rawData?.targetDataSourceId || rawData?.targetId || "",
  };
};

const buildInitialBasicConfigForEdit = (
  editData?: any
): CustomBasicConfig => {
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
    mode: "SCRIPT",

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

    sourcePluginName:
      workflow?.sourceType?.pluginName ||
      source?.pluginName ||
      editData?.sourceType?.pluginName ||
      "",

    targetPluginName:
      workflow?.targetType?.pluginName ||
      target?.pluginName ||
      editData?.targetType?.pluginName ||
      "",

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
  const hoconContent =
    rawData?.workflow?.hoconContent ||
    rawData?.content?.hoconContent ||
    rawData?.hoconContent ||
    "";

  return {
    ...(rawData || {}),
    mode: "SCRIPT",
    state: normalizeJobDefinitionState(
      rawData?.state || buildUnpublishedJobDefinitionState()
    ),
    workflow: {
      ...(rawData?.workflow || {}),
      hoconContent,
    },
    content: {
      ...(rawData?.content || {}),
      hoconContent,
    },
    hoconContent,
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

  const hoconContent =
    workflow?.hoconContent ||
    content?.hoconContent ||
    editData?.hoconContent ||
    editData?.jobDefinitionInfo?.hoconContent ||
    "";

  return {
    id: editData?.id,
    mode: editData?.mode || basic?.mode || "SCRIPT",
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
    workflow: {
      ...workflow,
      ...content,
      hoconContent,
    },
    basic,
    content,
    env,
    hoconContent,
  };
};

export default function CustomConfigPage() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();

  const [params, setParams] = useState<any>(null);
  const [scheduleConfig, setScheduleConfig] = useState<ScheduleConfig>(
    defaultScheduleConfig
  );
  const [envConfig, setEnvConfig] = useState<EnvConfig>(defaultEnvConfig);
  const [basicConfig, setBasicConfig] =
    useState<CustomBasicConfig>(defaultBasicConfig);
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
    draftKey: `batch-link-up-detail-${id}`,
    readSaved: seatunnelJobDefinitionApi.selectEditDetail,
    initialize,
  });

  const goBack = () => {
    history.push("/sync/batch-link-up");
  };

  const goPreviousStep = () => {
    if (id) {
      history.push(`/sync/batch-link-up/${id}/detail?step=client`);
      return;
    }
    history.push("/sync/batch-link-up");
  };

  if (status !== "ready") {
    return <TaskConfigStatusPanel status={status} onBack={goBack} onRetry={retry} />;
  }

  return (
    <div className="min-h-screen bg-white">
      <CustomWorkflow
        params={params}
        setParams={setParams}
        goBack={goBack}
        goPreviousStep={goPreviousStep}
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
