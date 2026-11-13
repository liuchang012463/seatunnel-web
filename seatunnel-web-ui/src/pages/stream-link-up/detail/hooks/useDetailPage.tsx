import { history, useLocation, useParams } from "@umijs/max";
import { Form, message } from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  DEFAULT_TASK_DATASOURCE_TYPE,
  DEFAULT_TASK_TARGET_DATASOURCE_TYPE,
} from "../../../data-source/dataSourceRegistry";
import { buildDefaultJobName, getDbLabel } from "../constants";
import type { DetailFormValues, SourceTargetType, StepKey } from "../types";

const defaultSourceType: SourceTargetType = { ...DEFAULT_TASK_DATASOURCE_TYPE };
const defaultTargetType: SourceTargetType = { ...DEFAULT_TASK_TARGET_DATASOURCE_TYPE };

const readStepFromSearch = (search?: string): StepKey => {
  const step = new URLSearchParams(search || "").get("step");
  return step === "client" ? "client" : "base";
};

export default function useDetailPage() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [form] = Form.useForm<DetailFormValues>();

  const [params, setParams] = useState<any>(null);
  const [sourceType, setSourceType] =
    useState<SourceTargetType>(defaultSourceType);
  const [targetType, setTargetType] =
    useState<SourceTargetType>(defaultTargetType);
  const [activeStep, setActiveStep] = useState<StepKey>(() =>
    readStepFromSearch(location.search),
  );

  const [sourceClientId, setSourceClientId] = useState<string>();
  const [targetClientId, setTargetClientId] = useState<string>();
  const [clientId, setClientId] = useState<string>();

  const [mode, setMode] = useState<any>("GUIDE_SINGLE");

  const scrollRef = useRef<HTMLDivElement>(null);
  const baseSectionRef = useRef<HTMLDivElement>(null);
  const clientSectionRef = useRef<HTMLDivElement>(null);

  const [sourceTestStatus, setSourceTestStatus] = useState<any>("idle");
  const [targetTestStatus, setTargetTestStatus] = useState<any>("idle");

  const [sourceDataSourceId, setSourceDataSourceId] = useState<string>();
  const [targetDataSourceId, setTargetDataSourceId] = useState<string>();

  const autoJobNameRef = useRef(
    buildDefaultJobName(defaultSourceType, defaultTargetType),
  );

  const syncAutoJobName = useCallback(
    (nextSource: SourceTargetType, nextTarget: SourceTargetType) => {
      const nextName = buildDefaultJobName(nextSource, nextTarget);
      const currentName = String(form.getFieldValue("jobName") || "").trim();
      if (!currentName || currentName === autoJobNameRef.current) {
        form.setFieldValue("jobName", nextName);
        autoJobNameRef.current = nextName;
      }
    },
    [form],
  );

  useEffect(() => {
    setActiveStep(readStepFromSearch(location.search));
  }, [location.search]);

  useEffect(() => {
    if (!id) return;

    const cache = sessionStorage.getItem(`stream-link-up-detail-${id}`);
    if (!cache) return;

    const data = JSON.parse(cache);
    setParams(data);

    const nextSource = data?.sourceType || defaultSourceType;
    const nextTarget = data?.targetType || defaultTargetType;
    if (data?.sourceType) setSourceType(data.sourceType);
    if (data?.targetType) setTargetType(data.targetType);

    const currentMode = data?.mode || "GUIDE_SINGLE";
    const defaultName = buildDefaultJobName(nextSource, nextTarget);
    const jobName = data?.jobName || defaultName;
    autoJobNameRef.current = data?.jobName ? jobName : defaultName;

    form.setFieldsValue({
      jobName,
      description: data?.description || "",
      mode: currentMode,
    });

    setMode(currentMode);

    setSourceClientId(data?.sourceClientId);
    setTargetClientId(data?.targetClientId);
    setClientId(data?.clientId);
  }, [id, form]);

  const sourceLabel = useMemo(() => getDbLabel(sourceType), [sourceType]);
  const targetLabel = useMemo(() => getDbLabel(targetType), [targetType]);

  const goBack = () => {
    history.push("/sync/stream-link-up");
  };

  const handleSourceChange = (value: string, option: any) => {
    const nextSourceType = {
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
    };
    setSourceType(nextSourceType);
    setSourceDataSourceId(undefined);
    setSourceTestStatus("idle");
    syncAutoJobName(nextSourceType, targetType);
  };

  const handleTargetChange = (value: string, option: any) => {
    const nextTargetType = {
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
    };
    setTargetType(nextTargetType);
    setTargetDataSourceId(undefined);
    setTargetTestStatus("idle");
    syncAutoJobName(sourceType, nextTargetType);
  };

  const handleModeChange = (value: string) => {
    setMode(value);
    form.setFieldValue("mode", value);
  };

  const goStep = async (step: StepKey) => {
    if (step === "base") {
      setActiveStep("base");
      scrollRef.current?.scrollTo?.({ top: 0, behavior: "smooth" });
      return;
    }

    try {
      await form.validateFields(["jobName", "mode"]);
      const currentMode = form.getFieldValue("mode");
      if (currentMode) {
        setMode(currentMode);
      }

      setActiveStep("client");
      scrollRef.current?.scrollTo?.({ top: 0, behavior: "smooth" });
    } catch (error) {
      console.log(error);
    }
  };

  const handleNext = async () => {
    if (activeStep === "base") {
      try {
        await form.validateFields(["jobName", "mode"]);

        const currentMode = form.getFieldValue("mode");
        if (currentMode) {
          setMode(currentMode);
        }

        setActiveStep("client");
        scrollRef.current?.scrollTo?.({ top: 0, behavior: "smooth" });
      } catch (error) {
        console.log(error);
      }
      return;
    }

    try {
      await form.validateFields();

      const currentMode = form.getFieldValue("mode") || mode;
      const values = form.getFieldsValue(true);

      const merged = {
        ...params,
        ...values,
        mode: currentMode,
        sourceType,
        targetType,
        sourceClientId,
        targetClientId,
        clientId,
        sourceDataSourceId,
        targetDataSourceId,
        sourceTestStatus,
        targetTestStatus,
      };

      if (id) {
        sessionStorage.setItem(
          `stream-link-up-detail-${id}`,
          JSON.stringify(merged)
        );

        if (currentMode === "GUIDE_SINGLE") {
          history.push(`/sync/stream-link-up/${id}/config/single?scene=create`);
          return;
        }

        if (currentMode === "GUIDE_MULTI") {
          history.push(`/sync/stream-link-up/${id}/config/multi?scene=create`);
          return;
        }

        if (currentMode === "SCRIPT") {
          history.push(`/sync/stream-link-up/${id}/config/script?scene=create`);
          return;
        }
      }

      message.success("配置已保存");
    } catch (error) {
      console.log(error);
    }
  };

  return {
    form,
    params,
    sourceType,
    targetType,
    activeStep,
    sourceClientId,
    targetClientId,
    clientId,
    sourceLabel,
    targetLabel,
    mode,
    scrollRef,
    baseSectionRef,
    clientSectionRef,
    setActiveStep,
    setSourceClientId,
    setTargetClientId,
    setClientId,
    handleSourceChange,
    handleTargetChange,
    handleModeChange,
    goBack,
    goStep,
    handleNext,
    sourceTestStatus,
    targetTestStatus,
    setSourceTestStatus,
    setTargetTestStatus,
    sourceDataSourceId,
    targetDataSourceId,
    setSourceDataSourceId,
    setTargetDataSourceId,
  };
}
