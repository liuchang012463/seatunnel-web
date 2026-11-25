import { history, useLocation, useParams } from "@umijs/max";
import { useCallback, useState } from "react";
import TaskConfigStatusPanel from "@/pages/common/components/TaskConfigStatusPanel";
import useTaskConfig from "@/pages/common/hooks/useTaskConfig";
import { seatunnelJobDefinitionApi } from "../../api";
import Workflow from "../../workflow";
import {
  resolveConfigPageScene,
  type ConfigPageScene,
} from "@/pages/common/utils/configPageScene";
import { physicalTablePath } from "@/pages/lake/physical/paths";
import {
  BasicConfig,
  defaultEnvConfig,
  EnvConfig,
  resolveExecutionMode,
  ScheduleConfig,
} from "../../workflow/components/ScheduleConfigContent/types";

type PageScene = ConfigPageScene;

type EditorSyncState = "UNPUBLISHED" | "SYNCED" | "DIRTY";

type JobDefinitionState = {
  editorSyncState: EditorSyncState;
  releaseState?: "ONLINE" | "OFFLINE" | string;
  jobVersion?: number | null;
  contentVersion?: number | null;
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
  minuteValue: {
    intervalMinute: 5,
  },
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

const DEFAULT_AUTO_CRON = "0 17 0 * * ?";

const normalizeInitialScheduleConfig = (
  result: ScheduleConfig,
  modeOverride?: string,
  rawSchedule?: any,
): ScheduleConfig => {
  const forceAuto = modeOverride === "GUIDE_SINGLE_INCREMENTAL";
  const executionMode = resolveExecutionMode(rawSchedule || result, forceAuto);
  return {
    ...result,
    executionMode,
    cronExpression:
      executionMode === "MANUAL"
        ? undefined
        : result.cronExpression || DEFAULT_AUTO_CRON,
  };
};

const defaultBasicConfig: BasicConfig = {
  jobName: "",
  jobDesc: "",
  clientId: "",
  mode: "GUIDE_SINGLE",
  sourceType: "SOURCE",
  targetType: "SINK",
  sourceDataSourceId: "",
  targetDataSourceId: "",
};

const buildUnpublishedState = (): JobDefinitionState => {
  return {
    editorSyncState: "UNPUBLISHED",
    releaseState: "OFFLINE",
    jobVersion: null,
    contentVersion: null,
  };
};

const buildSyncedState = (rawState?: any): JobDefinitionState => {
  return {
    editorSyncState: "SYNCED",
    releaseState: rawState?.releaseState || "OFFLINE",
    jobVersion: rawState?.jobVersion ?? null,
    contentVersion: rawState?.contentVersion ?? null,
  };
};

const buildInitialScheduleConfigForCreate = (
  rawData?: any,
  modeOverride?: string,
): ScheduleConfig => {
  const result = {
    ...defaultScheduleConfig,
    ...(rawData?.scheduleConfig || {}),
    hourlyRangeValue: {
      ...defaultScheduleConfig.hourlyRangeValue,
      ...(rawData?.scheduleConfig?.hourlyRangeValue || {}),
    },
    hourlyAppointValue: {
      ...defaultScheduleConfig.hourlyAppointValue,
      ...(rawData?.scheduleConfig?.hourlyAppointValue || {}),
    },
    dailyValue: {
      ...defaultScheduleConfig.dailyValue,
      ...(rawData?.scheduleConfig?.dailyValue || {}),
    },
    weeklyValue: {
      ...defaultScheduleConfig.weeklyValue,
      ...(rawData?.scheduleConfig?.weeklyValue || {}),
    },
  } as ScheduleConfig;
  return normalizeInitialScheduleConfig(
    result,
    modeOverride,
    rawData?.scheduleConfig,
  );
};

const buildInitialBasicConfigForCreate = (rawData?: any, modeOverride?: string): BasicConfig => {
  return {
    ...defaultBasicConfig,
    jobName: rawData?.jobName || "",
    jobDesc: rawData?.jobDesc || "",
    clientId: rawData?.clientId || "",
    mode: modeOverride || rawData?.mode || "GUIDE_SINGLE",
    sourceType: rawData?.sourceType?.dbType || "SOURCE",
    targetType: rawData?.targetType?.dbType || "SINK",
    sourceDataSourceId: rawData?.sourceDataSourceId || rawData?.sourceId || "",
    targetDataSourceId: rawData?.targetDataSourceId || rawData?.targetId || "",
  };
};

const buildInitialScheduleConfigForEdit = (editData?: any, modeOverride?: string): ScheduleConfig => {
  const schedule = editData?.schedule || {};

  const result = {
    ...defaultScheduleConfig,
    ...schedule,
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
  return normalizeInitialScheduleConfig(result, modeOverride, schedule);
};

const buildInitialBasicConfigForEdit = (editData?: any, modeOverride?: string): BasicConfig => {
  const basic = editData?.basic || {};
  const workflow = editData?.workflow || {};

  return {
    ...defaultBasicConfig,
    jobName: basic?.jobName || "",
    jobDesc: basic?.jobDesc || "",
    clientId: basic?.clientId ? String(basic.clientId) : "",
    mode: modeOverride || basic?.mode || editData?.mode || "GUIDE_SINGLE",
    sourceType: workflow?.sourceType?.dbType || "SOURCE",
    targetType: workflow?.targetType?.dbType || "SINK",
    sourceDataSourceId:
      workflow?.sourceDataSourceId || workflow?.sourceId || "",
    targetDataSourceId:
      workflow?.targetDataSourceId || workflow?.targetId || "",
  };
};

const buildPageParamsForCreate = (rawData: any, routeId?: string) => {
  return {
    ...rawData,
    id: rawData?.id || routeId,
    __pageScene: "create",
    state: buildUnpublishedState(),
  };
};

const buildPageParamsForEdit = (editData?: any) => {
  const basic = editData?.basic || {};
  const workflow = editData?.workflow || {};
  const schedule = editData?.schedule || {};
  const env = editData?.env || {};

  return {
    id: editData?.id,
    mode: editData?.mode,
    runtimeType: editData?.runtimeType,
    odsDatabaseBindingId: editData?.odsDatabaseBindingId,

    jobName: basic?.jobName || "",
    jobDesc: basic?.jobDesc || "",
    clientId: basic?.clientId || "",

    sourceType: workflow?.sourceType || null,
    targetType: workflow?.targetType || null,
    sourceDataSourceId:
      workflow?.sourceDataSourceId || workflow?.sourceId || "",
    targetDataSourceId:
      workflow?.targetDataSourceId || workflow?.targetId || "",

    scheduleConfig: schedule,
    workflow,
    basic,
    schedule,
    env,

    __pageScene: "edit",
    state: editData?.state
      ? buildSyncedState(editData.state)
      : buildSyncedState({
          releaseState: editData?.releaseState,
          jobVersion: editData?.jobVersion,
          contentVersion: editData?.contentVersion,
        }),
  };
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

export function SingleConfigPage({ modeOverride }: { modeOverride?: string } = {}) {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const searchParams = new URLSearchParams(location.search);
  const scene = searchParams.get("scene");
  const viewing = scene === "view";
  const lakeMappingId = searchParams.get("lakeMappingId");
  const lakeTablePath = scene !== "create" && lakeMappingId && /^[1-9]\d*$/.test(lakeMappingId)
    ? physicalTablePath(lakeMappingId) : undefined;
  const backLabel = lakeTablePath ? "返回入湖表" : "返回任务列表";

  const [pageScene, setPageScene] = useState<PageScene>("create");
  const [params, setParams] = useState<any>(null);
  const [sourceType, setSourceType] = useState<any>(null);
  const [targetType, setTargetType] = useState<any>(null);
  const [scheduleConfig, setScheduleConfig] = useState<ScheduleConfig>(
    defaultScheduleConfig
  );
  const [envConfig, setEnvConfig] = useState<EnvConfig>(defaultEnvConfig);
  const [basicConfig, setBasicConfig] =
    useState<BasicConfig>(defaultBasicConfig);
  const initialize = useCallback((data: any, source: ConfigPageScene, taskId: string) => {
    setPageScene(source);
    if (source === "create") {
      setParams(buildPageParamsForCreate(data, taskId));
      setSourceType(data?.sourceType || null);
      setTargetType(data?.targetType || null);
      setBasicConfig(buildInitialBasicConfigForCreate(data, modeOverride));
      setScheduleConfig(buildInitialScheduleConfigForCreate(data, modeOverride));
      setEnvConfig(buildInitialEnvConfigForCreate(data));
    } else {
      setParams(buildPageParamsForEdit(data));
      setSourceType(data?.workflow?.sourceType || null);
      setTargetType(data?.workflow?.targetType || null);
      setBasicConfig(buildInitialBasicConfigForEdit(data, modeOverride));
      setScheduleConfig(buildInitialScheduleConfigForEdit(data, modeOverride));
      setEnvConfig(buildInitialEnvConfigForEdit(data));
    }
  }, [modeOverride]);
  const { status, retry } = useTaskConfig({
    id,
    scene: viewing ? "view" : resolveConfigPageScene(scene),
    draftKey: `batch-link-up-detail-${id}`,
    readSaved: viewing ? seatunnelJobDefinitionApi.selectSingleConfigDetail : seatunnelJobDefinitionApi.selectEditDetail,
    initialize,
  });

  const goBack = () => {
    history.push(lakeTablePath || "/sync/batch-link-up");
  };

  /** 回到引接链路配置步骤（保留当前任务草稿） */
  const goPreviousStep = () => {
    if (id) {
      // 回到第二步「引接链路配置」，而不是基础配置
      history.push(`/sync/batch-link-up/${id}/detail?step=client`);
      return;
    }
    history.push("/sync/batch-link-up");
  };

  if (status !== "ready") {
    const viewConfig = () => {
      const query = new URLSearchParams(location.search);
      query.set("scene", "view");
      history.push({ pathname: location.pathname, search: `?${query}` });
    };
    return (
      <TaskConfigStatusPanel
        status={status}
        onBack={goBack}
        backLabel={backLabel}
        onRetry={retry}
        onView={viewing ? undefined : viewConfig}
      />
    );
  }

  const actualPageScene = (params?.__pageScene || pageScene) as PageScene;

  const workflowContextKey = [
    actualPageScene,
    params?.id || id || "unknown",
    params?.state?.editorSyncState ?? "none",
    params?.state?.jobVersion ?? "none",
    params?.state?.contentVersion ?? "none",
  ].join("-");

  return (
    <div className="min-h-screen bg-[#ffffff]">
      <Workflow
        pageScene={actualPageScene}
        contextKey={workflowContextKey}
        params={params}
        goBack={goBack}
        backLabel={backLabel}
        goPreviousStep={viewing || lakeTablePath ? undefined : goPreviousStep}
        readOnly={viewing}
        sourceType={sourceType}
        setSourceType={setSourceType}
        targetType={targetType}
        setTargetType={setTargetType}
        setParams={setParams}
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

export default SingleConfigPage;
