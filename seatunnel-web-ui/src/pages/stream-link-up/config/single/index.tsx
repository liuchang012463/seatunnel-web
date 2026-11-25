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
import {
  BasicConfig,
  defaultEnvConfig,
  EnvConfig,
} from "../../workflow/components/ScheduleConfigContent/types";

type PageScene = ConfigPageScene;

type EditorSyncState = "UNPUBLISHED" | "SYNCED" | "DIRTY";

type JobDefinitionState = {
  editorSyncState: EditorSyncState;
  releaseState?: "ONLINE" | "OFFLINE" | string;
  jobVersion?: number | null;
  contentVersion?: number | null;
};

const defaultStreamingEnvConfig: EnvConfig = {
  ...defaultEnvConfig,
  jobMode: "STREAMING",
  parallelism: 1,
  checkpointInterval: 30000,
} as EnvConfig;

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

const buildInitialBasicConfigForCreate = (rawData?: any): BasicConfig => {
  return {
    ...defaultBasicConfig,
    jobName: rawData?.jobName || "",
    jobDesc: rawData?.jobDesc || "",
    clientId: rawData?.clientId || "",
    mode: rawData?.mode || "GUIDE_SINGLE",
    sourceType: rawData?.sourceType?.dbType || "SOURCE",
    targetType: rawData?.targetType?.dbType || "SINK",
    sourceDataSourceId: rawData?.sourceDataSourceId || rawData?.sourceId || "",
    targetDataSourceId: rawData?.targetDataSourceId || rawData?.targetId || "",
  };
};

const buildInitialBasicConfigForEdit = (editData?: any): BasicConfig => {
  const basic = editData?.basic || {};
  const workflow = editData?.workflow || {};

  return {
    ...defaultBasicConfig,
    jobName: basic?.jobName || "",
    jobDesc: basic?.jobDesc || "",
    clientId: basic?.clientId ? String(basic.clientId) : "",
    mode: basic?.mode || editData?.mode || "GUIDE_SINGLE",
    sourceType: workflow?.sourceType?.dbType || "SOURCE",
    targetType: workflow?.targetType?.dbType || "SINK",
    sourceDataSourceId:
      workflow?.sourceDataSourceId || workflow?.sourceId || "",
    targetDataSourceId:
      workflow?.targetDataSourceId || workflow?.targetId || "",
  };
};

const buildInitialEnvConfigForCreate = (rawData?: any): EnvConfig => {
  return {
    ...defaultStreamingEnvConfig,
    ...(rawData?.env || rawData?.envConfig || {}),
    jobMode: "STREAMING",
  } as EnvConfig;
};

const buildInitialEnvConfigForEdit = (editData?: any): EnvConfig => {
  return {
    ...defaultStreamingEnvConfig,
    ...(editData?.env || {}),
    jobMode: "STREAMING",
  } as EnvConfig;
};

const buildPageParamsForCreate = (rawData: any, routeId?: string) => {
  return {
    ...rawData,
    id: rawData?.id || routeId,
    runtimeType: "STREAMING",
    __pageScene: "create",
    state: buildUnpublishedState(),
  };
};

const buildPageParamsForEdit = (editData?: any) => {
  const basic = editData?.basic || {};
  const workflow = editData?.workflow || {};
  const env = editData?.env || {};

  return {
    id: editData?.id,
    mode: editData?.mode,
    runtimeType: editData?.runtimeType || "STREAMING",

    jobName: basic?.jobName || "",
    jobDesc: basic?.jobDesc || "",
    clientId: basic?.clientId || "",

    sourceType: workflow?.sourceType || null,
    targetType: workflow?.targetType || null,
    sourceDataSourceId:
      workflow?.sourceDataSourceId || workflow?.sourceId || "",
    targetDataSourceId:
      workflow?.targetDataSourceId || workflow?.targetId || "",

    workflow,
    basic,
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

export default function SingleConfigPage() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();

  const [pageScene, setPageScene] = useState<PageScene>("create");
  const [params, setParams] = useState<any>(null);
  const [sourceType, setSourceType] = useState<any>(null);
  const [targetType, setTargetType] = useState<any>(null);
  const [envConfig, setEnvConfig] =
    useState<EnvConfig>(defaultStreamingEnvConfig);
  const [basicConfig, setBasicConfig] =
    useState<BasicConfig>(defaultBasicConfig);
  const initialize = useCallback((data: any, source: ConfigPageScene, taskId: string) => {
    setPageScene(source);
    if (source === "create") {
      setParams(buildPageParamsForCreate(data, taskId));
      setSourceType(data?.sourceType || null);
      setTargetType(data?.targetType || null);
      setBasicConfig(buildInitialBasicConfigForCreate(data));
      setEnvConfig(buildInitialEnvConfigForCreate(data));
    } else {
      setParams(buildPageParamsForEdit(data));
      setSourceType(data?.workflow?.sourceType || null);
      setTargetType(data?.workflow?.targetType || null);
      setBasicConfig(buildInitialBasicConfigForEdit(data));
      setEnvConfig(buildInitialEnvConfigForEdit(data));
    }
  }, []);
  const { status, retry } = useTaskConfig({
    id,
    scene: resolveConfigPageScene(new URLSearchParams(location.search).get("scene")),
    draftKey: `stream-link-up-detail-${id}`,
    readSaved: seatunnelJobDefinitionApi.selectEditDetail,
    readReleaseState: seatunnelJobDefinitionApi.selectById,
    initialize,
  });

  const goBack = () => {
    history.push("/sync/stream-link-up");
  };

  const goPreviousStep = () => {
    if (id) {
      history.push(`/sync/stream-link-up/${id}/detail?step=client`);
      return;
    }
    history.push("/sync/stream-link-up");
  };

  if (status !== "ready") {
    return <TaskConfigStatusPanel status={status} onBack={goBack} onRetry={retry} />;
  }

  const effectivePageScene = (params?.__pageScene as PageScene) || pageScene;

  const workflowContextKey = [
    effectivePageScene,
    params?.id || id || "unknown",
    params?.state?.jobVersion ?? "none",
    params?.state?.contentVersion ?? "none",
  ].join("-");

  return (
    <div className="min-h-screen bg-[#ffffff]">
      <Workflow
        pageScene={effectivePageScene}
        contextKey={workflowContextKey}
        params={params}
        goBack={goBack}
        goPreviousStep={goPreviousStep}
        sourceType={sourceType}
        setSourceType={setSourceType}
        targetType={targetType}
        setTargetType={setTargetType}
        setParams={setParams}
        basicConfig={basicConfig}
        setBasicConfig={setBasicConfig}
        envConfig={envConfig}
        setEnvConfig={setEnvConfig}
      />
    </div>
  );
}
