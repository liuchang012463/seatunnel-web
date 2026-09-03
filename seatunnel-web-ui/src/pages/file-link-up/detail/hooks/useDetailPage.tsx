import { history, useParams } from '@umijs/max';
import { Form } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { SourceTargetType, StepKey, SyncMode } from '@/pages/batch-link-up/detail/types';

const defaultSourceType: SourceTargetType = {
  dbType: 'WEB_UPLOAD',
  connectorType: 'S3File',
  pluginName: 'S3File',
  sourceManaged: true,
};

const defaultTargetType: SourceTargetType = {
  dbType: 'FTP',
  connectorType: 'FtpFile',
  pluginName: 'FtpFile',
};

/** 文件引接任务固定走 FILE_SYNC 模式。 */
const FILE_SYNC_MODE: SyncMode = 'FILE_SYNC';

export default function useDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [form] = Form.useForm();

  const [params, setParams] = useState<any>(null);
  const [sourceType, setSourceType] = useState<SourceTargetType>(defaultSourceType);
  const [targetType, setTargetType] = useState<SourceTargetType>(defaultTargetType);
  const [activeStep, setActiveStep] = useState<StepKey>('base');

  const [clientId, setClientId] = useState<string>();

  const scrollRef = useRef<HTMLDivElement>(null);
  const clientSectionRef = useRef<HTMLDivElement>(null);

  const [sourceTestStatus, setSourceTestStatus] = useState<any>('idle');
  const [targetTestStatus, setTargetTestStatus] = useState<any>('idle');

  const [sourceDataSourceId, setSourceDataSourceId] = useState<string>();
  const [targetDataSourceId, setTargetDataSourceId] = useState<string>();

  useEffect(() => {
    if (!id) return;

    const cache = sessionStorage.getItem(`batch-link-up-detail-${id}`);
    if (!cache) return;

    try {
      const data = JSON.parse(cache);
      setParams(data);

      if (data?.sourceType) setSourceType(data.sourceType);
      if (data?.targetType) setTargetType(data.targetType);

      form.setFieldsValue({
        jobName: data?.jobName || `${data?.sourceType?.dbType?.toLowerCase()}2${data?.targetType?.dbType?.toLowerCase()}`,
        jobDesc: data?.jobDesc || '',
        mode: FILE_SYNC_MODE,
      });

      setClientId(data?.clientId);
    } catch (error) {
      console.log(error);
    }
  }, [id, form]);

  const sourceLabel = useMemo(() => '来源', []);
  const targetLabel = useMemo(() => '去向', []);

  const goBack = () => {
    history.push('/sync/file-link-up');
  };

  const handleSourceChange = (value: string, option: any) => {
    setSourceType({
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
      sourceManaged: option?.sourceManaged,
    });
    setSourceDataSourceId(undefined);
    setSourceTestStatus('idle');
  };

  const handleTargetChange = (value: string, option: any) => {
    setTargetType({
      dbType: value,
      connectorType: option?.connectorType,
      pluginName: option?.pluginName,
    });
  };

  const goStep = async (step: StepKey) => {
    if (step === 'base') {
      setActiveStep('base');
      scrollRef.current?.scrollTo?.({ top: 0, behavior: 'smooth' });
      return;
    }

    try {
      await form.validateFields(['jobName']);
      setActiveStep('client');
      scrollRef.current?.scrollTo?.({ top: 0, behavior: 'smooth' });
    } catch (error) {
      console.log(error);
    }
  };

  const handleNext = async () => {
    if (activeStep === 'base') {
      try {
        await form.validateFields(['jobName']);
        setActiveStep('client');
        scrollRef.current?.scrollTo?.({ top: 0, behavior: 'smooth' });
      } catch (error) {
        console.log(error);
      }
      return;
    }

    try {
      const values = form.getFieldsValue(true);

      const merged = {
        ...params,
        ...values,
        mode: FILE_SYNC_MODE,
        sourceType,
        targetType,
        clientId,
        sourceDataSourceId,
        targetDataSourceId,
        sourceTestStatus,
        targetTestStatus,
      };

      if (id) {
        sessionStorage.setItem(`batch-link-up-detail-${id}`, JSON.stringify(merged));
        history.push(`/sync/file-link-up/${id}/config/file-sync?scene=create`);
        return;
      }
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
    clientId,
    setClientId,
    sourceLabel,
    targetLabel,
    mode: FILE_SYNC_MODE,
    scrollRef,
    clientSectionRef,
    setActiveStep,
    handleSourceChange,
    handleTargetChange,
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
