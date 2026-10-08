import { history, useLocation, useParams } from "@umijs/max";
import { Button, Empty, Spin } from "antd";
import { useEffect, useState } from "react";
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

const ONLY_OFFLINE_JOB_EDITABLE_ERROR =
  "only offline job definition can be edited";
const EDIT_ONLINE_BLOCKED_MESSAGE = "任务已上线，请先下线任务再编辑配置";

const isOnlyOfflineJobEditableError = (rawMessage?: unknown) =>
  String(rawMessage || "")
    .toLowerCase()
    .includes(ONLY_OFFLINE_JOB_EDITABLE_ERROR);

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
  const [loading, setLoading] = useState(Boolean(id));
  const [loadError, setLoadError] = useState<string>();

  useEffect(() => {
    if (!id) return;

    const searchParams = new URLSearchParams(location.search);
    const scene = searchParams.get("scene");
    const cacheKey = `stream-link-up-detail-${id}`;

    const initCreate = () => {
      setPageScene("create");
      setLoading(false);
      setLoadError(undefined);

      const cache = sessionStorage.getItem(cacheKey);
      if (!cache) {
        setParams(null);
        setLoadError("创建草稿已失效，请返回任务详情重新进入配置。");
        return;
      }

      try {
        const data = JSON.parse(cache);
        const pageParams = buildPageParamsForCreate(data, id);

        setParams(pageParams);
        setSourceType(data?.sourceType || null);
        setTargetType(data?.targetType || null);
        setBasicConfig(buildInitialBasicConfigForCreate(data));
        setEnvConfig(buildInitialEnvConfigForCreate(data));
      } catch {
        setParams(null);
        setLoadError("创建配置读取失败，请返回任务详情重新进入配置。");
      }
    };

    const initEdit = async () => {
      try {
        setLoading(true);
        setLoadError(undefined);
        setPageScene("edit");

        const res = await seatunnelJobDefinitionApi.selectEditDetail(id);
        if (res?.code !== 0 || !res?.data) {
          const rawMessage = res?.message || res?.msg;
          setLoadError(
            isOnlyOfflineJobEditableError(rawMessage)
              ? EDIT_ONLINE_BLOCKED_MESSAGE
              : String(rawMessage || "获取编辑详情失败"),
          );
          setParams(null);
          return;
        }

        const data = res.data;
        const pageParams = buildPageParamsForEdit(data);

        setParams(pageParams);
        setSourceType(data?.workflow?.sourceType || null);
        setTargetType(data?.workflow?.targetType || null);
        setBasicConfig(buildInitialBasicConfigForEdit(data));
        setEnvConfig(buildInitialEnvConfigForEdit(data));
      } catch (error) {
        const rawMessage =
          (error as any)?.message ||
          (error as any)?.response?.msg ||
          (error as any)?.response?.message;
        setLoadError(
          isOnlyOfflineJobEditableError(rawMessage)
            ? EDIT_ONLINE_BLOCKED_MESSAGE
            : "暂时无法获取任务配置，请检查连接后返回任务列表重试。",
        );
        setParams(null);
      } finally {
        setLoading(false);
      }
    };

    if (resolveConfigPageScene(scene) === "create") {
      initCreate();
      return;
    }

    // Missing scene values are legacy edit links. Never let a stale create
    // draft in sessionStorage replace the saved definition from the server.
    initEdit();
  }, [id, location.search]);

  const goBack = () => {
    const searchParams = new URLSearchParams(location.search);
    const scene = searchParams.get("scene");

    if (resolveConfigPageScene(scene) === "edit") {
      history.push(`/sync/stream-link-up`);
      return;
    }

    history.push(`/sync/stream-link-up/${id}/detail`);
  };

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[#F8FAFC]">
        <Spin />
      </div>
    );
  }

  if (!params) {
    const isCreateScene = resolveConfigPageScene(
      new URLSearchParams(location.search).get("scene"),
    ) === "create";
    return (
      <div className="flex min-h-screen items-center justify-center bg-[#F8FAFC]">
        <Empty description={loadError || "任务配置加载失败，请返回列表重试。"}>
          <Button type="primary" onClick={goBack}>
            {isCreateScene ? "返回任务详情" : "返回任务列表"}
          </Button>
        </Empty>
      </div>
    );
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
