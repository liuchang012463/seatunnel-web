import { history, useParams } from '@umijs/max';
import { Form, message } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { seatunnelJobDefinitionApi } from '../../api';
import { getDbLabel } from '../constants';
import type { DetailFormValues, SourceTargetType, StepKey, SyncMode } from '../types';
import { isWebUploadSource, normalizeOfflineMode } from '../modeUtils';

const defaultSourceType: SourceTargetType = {
  dbType: 'MYSQL',
  connectorType: 'Jdbc',
  pluginName: 'JDBC-MYSQL',
};

const defaultTargetType: SourceTargetType = {
  dbType: 'MYSQL',
  connectorType: 'Jdbc',
  pluginName: 'Jdbc-MYSQL',
};

export default function useDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [form] = Form.useForm<DetailFormValues>();

  const [params, setParams] = useState<any>(null);
  const [loading, setLoading] = useState(Boolean(id));
  const [loadError, setLoadError] = useState<string>();
  const [sourceType, setSourceType] = useState<SourceTargetType>(defaultSourceType);
  const [targetType, setTargetType] = useState<SourceTargetType>(defaultTargetType);
  const [activeStep, setActiveStep] = useState<StepKey>('base');

  const [sourceClientId, setSourceClientId] = useState<string>();
  const [targetClientId, setTargetClientId] = useState<string>();
  const [clientId, setClientId] = useState<string>();

  const [mode, setMode] = useState<any>('GUIDE_SINGLE');

  const scrollRef = useRef<HTMLDivElement>(null);
  const baseSectionRef = useRef<HTMLDivElement>(null);
  const clientSectionRef = useRef<HTMLDivElement>(null);

  const [sourceTestStatus, setSourceTestStatus] = useState<any>('idle');
  const [targetTestStatus, setTargetTestStatus] = useState<any>('idle');

  const [sourceDataSourceId, setSourceDataSourceId] = useState<string>();
  const [targetDataSourceId, setTargetDataSourceId] = useState<string>();

  const applyDetail = useCallback(
    (data: any) => {
      setParams(data);

      if (data?.sourceType) setSourceType(data.sourceType);
      if (data?.targetType) setTargetType(data.targetType);

      const currentMode = normalizeOfflineMode(data?.sourceType, data?.mode) as SyncMode;

      form.setFieldsValue({
        jobName: data?.jobName || '',
        jobDesc: data?.jobDesc || '',
        mode: currentMode,
      });

      setMode(currentMode);

      setSourceClientId(data?.sourceClientId);
      setTargetClientId(data?.targetClientId);
      setClientId(data?.clientId);
      // Lake table detail navigation already knows both endpoint ids. Keep them
      // in the normal wizard state so the client-link step opens with the
      // correct source/target selected; connectivity still has to be tested by
      // the operator before continuing.
      setSourceDataSourceId(data?.sourceDataSourceId || data?.sourceId
        ? String(data?.sourceDataSourceId || data?.sourceId) : undefined);
      setTargetDataSourceId(data?.targetDataSourceId || data?.targetId
        ? String(data?.targetDataSourceId || data?.targetId) : undefined);
    },
    [form],
  );

  const loadFromServer = useCallback(async () => {
    if (!id) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setLoadError(undefined);
    try {
      // The read-only definition works for both online and offline jobs and is the primary
      // source for deep links. The edit detail is only a richer fallback for saved drafts.
      let definition: any;
      try {
        const response = await seatunnelJobDefinitionApi.selectById(id, {
          skipErrorHandler: true,
        });
        if (response?.code === 0 && response?.data) {
          definition = response.data;
        }
      } catch {
        // Fall through to the edit-detail endpoint.
      }

      if (!definition) {
        try {
          const editResponse = await seatunnelJobDefinitionApi.selectEditDetail(id, {
            skipErrorHandler: true,
          });
          if (editResponse?.code === 0 && editResponse?.data) {
            definition = editResponse.data;
          } else {
            setLoadError(
              editResponse?.message || editResponse?.msg || '任务详情读取失败',
            );
          }
        } catch (error) {
          setLoadError(
            error instanceof Error && error.message
              ? error.message
              : '任务详情读取失败，请稍后重试',
          );
        }
      }

      if (!definition) {
        return;
      }

      const basic = definition.basic || definition;
      const workflow = definition.workflow || {};
      const data = {
        id: definition.id || id,
        jobName: basic.jobName,
        jobDesc: basic.jobDesc,
        mode: definition.mode,
        sourceType: workflow.sourceType
          || (definition.sourceType ? { dbType: definition.sourceType } : undefined),
        targetType: workflow.targetType
          || (definition.sinkType ? { dbType: definition.sinkType } : undefined),
        clientId: basic.clientId || definition.clientId,
        sourceClientId: workflow.sourceClientId || workflow.source?.clientId,
        targetClientId: workflow.targetClientId || workflow.target?.clientId,
        sourceDataSourceId:
          workflow.sourceDataSourceId || workflow.sourceId || definition.sourceDatasourceId,
        targetDataSourceId:
          workflow.targetDataSourceId || workflow.targetId || definition.sinkDatasourceId,
      };
      // The cache is an accelerator for the create flow, never the only source.
      sessionStorage.setItem(`batch-link-up-detail-${id}`, JSON.stringify(data));
      applyDetail(data);
    } catch (error) {
      setLoadError(
        error instanceof Error && error.message
          ? error.message
          : '任务详情读取失败，请稍后重试',
      );
    } finally {
      setLoading(false);
    }
  }, [applyDetail, id]);

  useEffect(() => {
    if (!id) {
      setLoading(false);
      return;
    }

    const cache = sessionStorage.getItem(`batch-link-up-detail-${id}`);
    if (cache) {
      try {
        applyDetail(JSON.parse(cache));
        setLoading(false);
        return;
      } catch {
        sessionStorage.removeItem(`batch-link-up-detail-${id}`);
      }
    }

    // Deep link or refresh: fetch the persisted definition by id instead of
    // relying on a cache that only exists in the tab that opened the page.
    void loadFromServer();
  }, [applyDetail, id, loadFromServer]);

  const sourceLabel = useMemo(() => getDbLabel(sourceType), [sourceType]);
  const targetLabel = useMemo(() => getDbLabel(targetType), [targetType]);

  const goBack = () => {
    history.push('/sync/batch-link-up');
  };

  const handleSourceChange = (value: string, option: any) => {
    const nextSourceType = {
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
      sourceManaged: option?.sourceManaged,
    };
    setSourceType(nextSourceType);
    setSourceDataSourceId(undefined);
    setSourceTestStatus('idle');
    if (isWebUploadSource(nextSourceType)) {
      setMode('GUIDE_SINGLE');
      form.setFieldValue('mode', 'GUIDE_SINGLE');
    }
  };

  const handleTargetChange = (value: string, option: any) => {
    setTargetType({
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
    });
    setTargetDataSourceId(undefined);
    setTargetTestStatus('idle');
  };

  const handleModeChange = (value: string) => {
    const nextMode = normalizeOfflineMode(sourceType, value);
    setMode(nextMode);
    form.setFieldValue('mode', nextMode);
  };

  const goStep = async (step: StepKey) => {
    if (step === 'base') {
      setActiveStep('base');
      scrollRef.current?.scrollTo?.({ top: 0, behavior: 'smooth' });
      return;
    }

    try {
      await form.validateFields(['jobName', 'mode']);
      const currentMode = form.getFieldValue('mode');
      if (currentMode) {
        setMode(currentMode);
      }

      setActiveStep('client');
      scrollRef.current?.scrollTo?.({ top: 0, behavior: 'smooth' });
    } catch (error) {
      console.log(error);
    }
  };

  const handleNext = async () => {
    if (activeStep === 'base') {
      try {
        await form.validateFields(['jobName', 'mode']);

        const currentMode = form.getFieldValue('mode');
        if (currentMode) {
          setMode(currentMode);
        }

        setActiveStep('client');
        scrollRef.current?.scrollTo?.({ top: 0, behavior: 'smooth' });
      } catch (error) {
        console.log(error);
      }
      return;
    }

    try {
      await form.validateFields();

      const currentMode = form.getFieldValue('mode') || mode;
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
        sessionStorage.setItem(`batch-link-up-detail-${id}`, JSON.stringify(merged));

        if (currentMode === 'GUIDE_SINGLE') {
          history.push(`/sync/batch-link-up/${id}/config/single?scene=create`);
          return;
        }

        if (currentMode === 'GUIDE_SINGLE_INCREMENTAL') {
          history.push(`/sync/batch-link-up/${id}/config/single-incremental?scene=create`);
          return;
        }

        if (currentMode === 'FILE_SYNC') {
          history.push(`/sync/batch-link-up/${id}/config/file-sync?scene=create`);
          return;
        }

        if (currentMode === 'GUIDE_MULTI') {
          history.push(`/sync/batch-link-up/${id}/config/multi?scene=create`);
          return;
        }

        if (currentMode === 'SCRIPT') {
          history.push(`/sync/batch-link-up/${id}/config/script?scene=create`);
          return;
        }
      }

      message.success('配置已保存');
    } catch (error) {
      console.log(error);
    }
  };

  return {
    form,
    params,
    loading,
    loadError,
    reload: loadFromServer,
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
