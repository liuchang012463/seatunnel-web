import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Col, Form, message, Popover, Row, Space, Tooltip } from 'antd';
import { Blocks, Eye, FolderSync, PlayCircle, Upload } from 'lucide-react';
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type Dispatch,
  type SetStateAction,
} from 'react';
import { applyNodeChanges } from 'reactflow';
import { fetchDataSourceAll } from '@/pages/data-source/service';
import { seatunnelJobDefinitionApi } from '../../api';
import RightConfigPanel from '../../workflow/RightConfigPanel';
import { CheckListPopover } from '../../workflow/components/CheckListPopover';
import {
  buildSchedulePayload,
  EnvConfig,
  getScheduleValidationMessage,
  ScheduleConfig,
} from '../../workflow/components/ScheduleConfigContent/types';
import CodeBlockWithCopy from '../../workflow/operator/CodeBlockWithCopy';
import RunLog from '../../workflow/run';
import {
  classifyFileSyncCheckResult,
  generateFileSyncCheckList,
  groupFileSyncCheckListByNode,
} from './checks';
import FileSyncCanvas from './FileSyncCanvas';

type PageScene = 'create' | 'edit';

type EditorSyncState = 'UNPUBLISHED' | 'SYNCED' | 'DIRTY';

type JobDefinitionState = {
  editorSyncState?: EditorSyncState | string;
  releaseState?: 'ONLINE' | 'OFFLINE' | string;
  jobVersion?: number | null;
  contentVersion?: number | null;
};

interface FileWorkflowProps {
  pageScene: PageScene;
  contextKey: string;
  params: any;
  goBack: () => void;
  basicConfig: any;
  setBasicConfig: Dispatch<SetStateAction<any>>;
  scheduleConfig: ScheduleConfig;
  setScheduleConfig: Dispatch<SetStateAction<ScheduleConfig>>;
  envConfig: EnvConfig;
  setEnvConfig: Dispatch<SetStateAction<EnvConfig>>;
}

const buildInitialGraph = (params?: any) => {
  const workflow = params?.workflow || {};
  if (Array.isArray(workflow?.nodes) && workflow.nodes.length > 0) {
    return {
      nodes: workflow.nodes,
      edges: workflow.edges,
    };
  }
  return { nodes: [], edges: [] };
};

const defaultFileSourceConfig = (sourceType: any, sourceDataSourceId?: string) => ({
  dataSourceId: sourceDataSourceId || undefined,
  dbType: sourceType?.dbType || 'LOCAL_FILE',
  pluginName: sourceType?.pluginName || 'LocalFile',
  connectorType: sourceType?.connectorType || 'LocalFile',
  readMode: sourceType?.dbType === 'LOCAL_FILE' ? 'upload' : 'remote',
  path: undefined,
  targetPath: undefined,
  syncType: 'FULL',
  fileFilterPattern: '.*',
  filenameExtension: undefined,
  binaryChunkSize: 1048576,
  binaryCompleteFileMode: true,
  updateStrategy: 'only_add',
  compareMode: 'len_mtime',
});

const defaultFileSinkConfig = (targetType: any, targetDataSourceId?: string) => ({
  dataSourceId: targetDataSourceId || undefined,
  dbType: targetType?.dbType || 'FTP',
  pluginName: targetType?.pluginName || 'FtpFile',
  connectorType: targetType?.connectorType || 'FtpFile',
  targetPath: undefined,
});

const buildGraph = (params: any) => {
  const existing = buildInitialGraph(params);
  if (existing.nodes.length > 0) return existing;

  const timestamp = Date.now();
  const sourceId = 'file-source';
  const sinkId = 'file-sink';

  const sourceDbType = params?.sourceType?.dbType || 'LOCAL_FILE';
  const sinkDbType = params?.targetType?.dbType || 'FTP';

  const nodes = [
    {
      id: sourceId,
      type: 'custom',
      position: { x: 80, y: 160 },
      data: {
        nodeType: 'source',
        title: sourceDbType,
        description: '读取来源文件',
        dbType: sourceDbType,
        config: defaultFileSourceConfig(
          params?.sourceType,
          params?.sourceDataSourceId,
        ),
      },
    },
    {
      id: sinkId,
      type: 'custom',
      position: { x: 480, y: 160 },
      data: {
        nodeType: 'sink',
        title: sinkDbType,
        description: '写入目标端文件',
        dbType: sinkDbType,
        config: defaultFileSinkConfig(params?.targetType, params?.targetDataSourceId),
      },
    },
  ];

  const edges = [
    {
      id: `${sourceId}-${sinkId}`,
      source: sourceId,
      target: sinkId,
      type: 'custom',
      data: {},
    },
  ];

  return { nodes, edges };
};

const normalizeInitialState = (
  state: JobDefinitionState | undefined,
  pageScene: PageScene,
): JobDefinitionState => {
  if (state?.editorSyncState === 'SYNCED') {
    return {
      editorSyncState: 'SYNCED',
      releaseState: state?.releaseState || 'OFFLINE',
      jobVersion: state?.jobVersion ?? null,
      contentVersion: state?.contentVersion ?? null,
    };
  }

  if (pageScene === 'create') {
    return {
      editorSyncState: 'UNPUBLISHED',
      releaseState: 'OFFLINE',
      jobVersion: null,
      contentVersion: null,
    };
  }

  return {
    editorSyncState: 'SYNCED',
    releaseState: state?.releaseState || 'OFFLINE',
    jobVersion: state?.jobVersion ?? null,
    contentVersion: state?.contentVersion ?? null,
  };
};

const normalizeSavedState = (
  state: JobDefinitionState | undefined,
  currentState: JobDefinitionState,
): JobDefinitionState => ({
  editorSyncState: 'SYNCED',
  releaseState: state?.releaseState || currentState?.releaseState || 'OFFLINE',
  jobVersion: state?.jobVersion ?? currentState?.jobVersion ?? null,
  contentVersion: state?.contentVersion ?? currentState?.contentVersion ?? null,
});

const getSaveResponseData = (res: any) => {
  const data = res?.data;
  if (data && typeof data === 'object') {
    return { id: data?.id, state: data?.state };
  }
  return { id: data, state: undefined };
};

const buildDirtySignature = (data: {
  basicConfig: any;
  scheduleConfig: Partial<ScheduleConfig>;
  envConfig: EnvConfig;
  nodes: any[];
  edges: any[];
}) =>
  JSON.stringify({
    basic: data.basicConfig,
    schedule: buildSchedulePayload(data.scheduleConfig),
    env: data.envConfig,
    workflow: {
      nodes: data.nodes,
      edges: data.edges,
    },
  });

export default function FileWorkflow({
  pageScene,
  contextKey,
  params,
  goBack,
  basicConfig,
  setBasicConfig,
  scheduleConfig,
  setScheduleConfig,
  envConfig,
  setEnvConfig,
}: FileWorkflowProps) {
  const [form] = Form.useForm();

  const [rightWidth, setRightWidth] = useState(540);
  const [activeTab, setActiveTab] = useState<'basic' | 'schedule' | 'env' | null>(null);
  const draggingRef = useRef(false);
  const contextRef = useRef<string>('');

  const initialGraph = useMemo(() => buildGraph(params), []);

  const [graph, setGraph] = useState<{ nodes: any[]; edges: any[] }>(initialGraph);

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewContent, setPreviewContent] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);

  const [runVisible, setRunVisible] = useState(false);

  const [definitionState, setDefinitionState] = useState<JobDefinitionState>(() =>
    normalizeInitialState(params?.state, pageScene),
  );
  const [baselineSignature, setBaselineSignature] = useState<string>(() =>
    buildDirtySignature({
      basicConfig,
      scheduleConfig,
      envConfig,
      nodes: initialGraph.nodes,
      edges: initialGraph.edges,
    }),
  );

  const [publishLoading, setPublishLoading] = useState(false);

  const [datasourceOptions, setDatasourceOptions] = useState<
    Array<{ label: string; value: string; dbType: string; connectorType?: string }>
  >([]);

  const jobDefinitionId = params?.id;

  useEffect(() => {
    if (contextRef.current === contextKey) return;
    contextRef.current = contextKey;

    const nextGraph = buildGraph(params);
    setGraph(nextGraph);
    const nextState = normalizeInitialState(params?.state, pageScene);
    setDefinitionState(nextState);
    setBaselineSignature(
      buildDirtySignature({
        basicConfig,
        scheduleConfig,
        envConfig,
        nodes: nextGraph.nodes,
        edges: nextGraph.edges,
      }),
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contextKey]);

  useEffect(() => {
    fetchDataSourceAll().then((res: any) => {
      const raw = res?.data?.bizData || res?.data || [];
      setDatasourceOptions(
        (Array.isArray(raw) ? raw : []).map((item: any) => ({
          label: item.name,
          value: String(item.id),
          dbType: item.dbType,
          connectorType: item.connectorType,
        })),
      );
    });
  }, []);

  const hasPersistedDefinition =
    !!jobDefinitionId && definitionState?.editorSyncState === 'SYNCED';

  const currentSignature = useMemo(
    () =>
      buildDirtySignature({
        basicConfig,
        scheduleConfig,
        envConfig,
        nodes: graph.nodes,
        edges: graph.edges,
      }),
    [basicConfig, scheduleConfig, envConfig, graph],
  );

  const isDirty =
    hasPersistedDefinition && !!baselineSignature && currentSignature !== baselineSignature;

  const editorSyncState: EditorSyncState = !hasPersistedDefinition
    ? 'UNPUBLISHED'
    : isDirty
      ? 'DIRTY'
      : 'SYNCED';

  const checkList = useMemo(() => generateFileSyncCheckList(graph.nodes || []), [graph.nodes]);
  const checkStat = useMemo(() => classifyFileSyncCheckResult(checkList), [checkList]);
  const checkGroups = useMemo(() => groupFileSyncCheckListByNode(checkList), [checkList]);

  const canRun = editorSyncState === 'SYNCED' && !publishLoading;

  const runDisabledReason =
    editorSyncState === 'UNPUBLISHED'
      ? '请先发布任务，再执行'
      : editorSyncState === 'DIRTY'
        ? '当前内容已修改，请发布后再执行'
        : '';

  const publishStatusView = {
    UNPUBLISHED: {
      text: '未发布',
      tooltip: '当前任务还没有发布到数据库，暂时不能运行',
      className: 'border-amber-200 bg-amber-50 text-amber-600',
    },
    SYNCED: {
      text: '已发布',
      tooltip: '当前内容已同步到数据库，可以运行',
      className: 'border-emerald-200 bg-emerald-50 text-emerald-600',
    },
    DIRTY: {
      text: '已修改，未发布',
      tooltip: '当前页面内容已变更，需要重新发布后才能运行',
      className: 'border-blue-200 bg-blue-50 text-blue-600',
    },
  }[editorSyncState];

  useEffect(() => {
    const handleMouseMove = (event: MouseEvent) => {
      if (!draggingRef.current) return;
      const viewportWidth = window.innerWidth;
      const nextWidth = viewportWidth - event.clientX - 18;
      setRightWidth(Math.max(320, Math.min(520, nextWidth)));
    };

    const handleMouseUp = () => {
      draggingRef.current = false;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };

    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);

    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, []);

  const handleNodesChange = useCallback((changes: any) => {
    setGraph((prev) => ({
      nodes: applyNodeChanges(changes, prev.nodes),
      edges: prev.edges,
    }));
  }, []);

  const handleNodeDataChange = useCallback((nodeId: string, data: any) => {
    setGraph((prev) => ({
      nodes: prev.nodes.map((node) => (node.id === nodeId ? { ...node, data } : node)),
      edges: prev.edges,
    }));
  }, []);

  const validateBeforeAction = () => {
    if (checkStat.total !== 0) {
      message.warning('请先完成检验项检查后，再进行预览或发布');
      return false;
    }
    const scheduleWarning = getScheduleValidationMessage(scheduleConfig);
    if (scheduleWarning) {
      message.warning(scheduleWarning);
      return false;
    }
    return true;
  };

  const buildSavePayload = () => {
    const sourceNode = graph.nodes.find((node) => node?.data?.nodeType === 'source');
    const sinkNode = graph.nodes.find((node) => node?.data?.nodeType === 'sink');
    const sourceConfig = sourceNode?.data?.config || {};
    const sinkConfig = sinkNode?.data?.config || {};

    return {
      id: jobDefinitionId,
      basic: {
        mode: 'FILE_SYNC',
        runtimeType: 'BATCH',
        jobName: basicConfig?.jobName,
        jobDesc: basicConfig?.jobDesc,
        clientId: basicConfig?.clientId,
      },
      workflow: {
        nodes: [
          {
            id: sourceNode?.id || 'file-source',
            type: 'source',
            data: { nodeType: 'source', config: sourceConfig },
          },
          {
            id: sinkNode?.id || 'file-sink',
            type: 'sink',
            data: { nodeType: 'sink', config: sinkConfig },
          },
        ],
        edges: [
          {
            id: 'file-transfer',
            source: sourceNode?.id || 'file-source',
            target: sinkNode?.id || 'file-sink',
          },
        ],
      },
      schedule: buildSchedulePayload(scheduleConfig),
      env: { ...envConfig, jobMode: 'BATCH' },
    };
  };

  const handlePreview = async () => {
    if (!validateBeforeAction()) return;

    setPreviewLoading(true);
    try {
      const res = await seatunnelJobDefinitionApi.buildFileSyncConfig(buildSavePayload());
      if (res?.code !== 0) throw new Error(res?.message || '预览失败');
      setPreviewContent(String(res?.data || ''));
      setPreviewOpen(true);
    } catch (error: any) {
      message.error(error?.message || '预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  const handleSave = async () => {
    if (!validateBeforeAction()) return;

    setPublishLoading(true);
    try {
      const finalPayload = buildSavePayload();
      const res = await seatunnelJobDefinitionApi.saveOrUpdateFileSync(finalPayload);
      if (res?.code !== 0) throw new Error(res?.message || '发布失败');

      const saveData = getSaveResponseData(res);
      if (!saveData.id && !jobDefinitionId) {
        message.error('发布失败：未获取到任务定义ID');
        return;
      }

      const nextState = normalizeSavedState(saveData.state, definitionState);
      setDefinitionState(nextState);
      setBaselineSignature(currentSignature);

      message.success('发布成功');
    } catch (error: any) {
      message.error(error?.message || '发布失败');
    } finally {
      setPublishLoading(false);
    }
  };

  const actionChipClass =
    'inline-flex h-[34px] cursor-pointer select-none items-center justify-center rounded-full border border-slate-200 bg-slate-50 px-3.5 text-[13px] font-medium leading-none text-slate-500 transition-colors duration-200 hover:border-slate-300 hover:bg-white/80 hover:text-slate-700 hover:shadow-[0_4px_12px_rgba(15,23,42,0.05)] active:translate-y-0';

  return (
    <div className="workflow-editor-page flex h-screen flex-col overflow-hidden bg-white">
      <div className="shrink-0 border-b border-slate-100 bg-white px-6 pb-4 pt-5">
        <div className="flex items-start justify-between gap-4">
          <div className="flex min-w-0 items-start gap-3.5">
            <div className="mt-0.5 flex h-11 w-11 shrink-0 items-center justify-center rounded-[14px] bg-indigo-50 text-indigo-600">
              <FolderSync size={18} />
            </div>

            <div>
              <div className="mb-0 text-[20px] font-bold leading-[1.2] text-slate-900">
                逻辑关系配置（文件引接任务）
              </div>
              <div className="text-[14px] leading-6 text-slate-500">
                配置文件同步链路与运行参数，在一个页面完成创建、上传与调试。
              </div>
            </div>
          </div>

          <div>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={goBack}
              className="!h-10 !rounded-full !border !border-slate-200 !bg-white !px-4 !text-slate-700 !shadow-sm hover:!border-slate-300 hover:!bg-slate-50 hover:!text-slate-800"
            >
              返回上一步
            </Button>
          </div>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-hidden p-[18px]">
        <div className="h-full overflow-hidden rounded-xl border border-slate-200 bg-gradient-to-b from-white via-white to-slate-50 shadow-[0_10px_30px_rgba(15,23,42,0.04)]">
          <div className="flex h-full min-w-0 items-stretch">
            <div className="flex h-full min-w-0 flex-1 overflow-hidden">
              <div className="flex h-full flex-col overflow-hidden rounded-lg bg-white shadow-[0_4px_18px_rgba(15,23,42,0.03)]">
                <div className="flex h-14 shrink-0 items-center justify-between border-b border-slate-100 bg-gradient-to-b from-white to-slate-50 px-[18px]">
                  <div className="text-[15px] font-semibold text-slate-800">同步编排</div>

                  <Space size={10}>
                    <Tooltip title={runDisabledReason || undefined}>
                      <span className="inline-flex">
                        <Button
                          type="default"
                          icon={<PlayCircle size={15} strokeWidth={1.9} />}
                          onClick={() => {
                            if (editorSyncState !== 'SYNCED') {
                              message.warning(runDisabledReason || '请先发布任务');
                              return;
                            }
                            setRunVisible(true);
                          }}
                          disabled={!canRun}
                          className="!inline-flex !h-[34px] !items-center !justify-center !rounded-full !border !border-[var(--st-color-primary)] !bg-[var(--st-color-primary)] !px-3.5 !text-[13px] !font-medium !text-white shadow-[0_6px_16px_rgba(33,135,168,0.2)] transition-all duration-200 hover:!border-[var(--st-color-accent)] hover:!bg-[var(--st-color-accent)] hover:!text-[var(--st-color-bg-primary)] hover:shadow-[0_8px_20px_rgba(77,210,255,0.24)] active:translate-y-px disabled:!cursor-not-allowed disabled:!border-[var(--st-color-border)] disabled:!bg-[rgba(102,111,117,0.18)] disabled:!text-[var(--st-color-text-muted)] disabled:!shadow-none"
                        >
                          运行
                        </Button>
                      </span>
                    </Tooltip>

                    <CheckListPopover
                      checkStat={checkStat}
                      checkGroups={checkGroups}
                      triggerClassName={actionChipClass}
                    />

                    <Popover
                      open={previewOpen}
                      placement="leftTop"
                      trigger="click"
                      overlayClassName="st-hocon-popover"
                      content={
                        <div className="w-[700px]">
                          <CodeBlockWithCopy
                            content={previewContent}
                            height={670}
                            title="HOCON Preview"
                            onClose={() => setPreviewOpen(false)}
                          />
                        </div>
                      }
                    >
                      <div
                        className={actionChipClass}
                        onClick={handlePreview}
                        role="button"
                        tabIndex={0}
                      >
                        <Eye
                          size={15}
                          strokeWidth={1.9}
                          className={previewLoading ? 'animate-spin' : ''}
                        />
                        <span className="ml-1">预览</span>
                      </div>
                    </Popover>

                    <Tooltip title={publishStatusView.tooltip}>
                      <span
                        className={[
                          'inline-flex h-[34px] select-none items-center justify-center rounded-full border px-3 text-[13px] font-medium leading-none',
                          publishStatusView.className,
                        ].join(' ')}
                      >
                        {publishStatusView.text}
                      </span>
                    </Tooltip>

                    <Button
                      type="default"
                      icon={<Upload size={15} strokeWidth={1.9} />}
                      onClick={handleSave}
                      loading={publishLoading}
                      className="!inline-flex !h-[34px] !items-center !justify-center !rounded-full !border !border-[var(--st-color-primary)] !bg-[var(--st-color-primary)] !px-3.5 !text-[13px] !font-medium !text-white shadow-[0_6px_16px_rgba(33,135,168,0.2)] transition-all duration-200 hover:!border-[var(--st-color-accent)] hover:!bg-[var(--st-color-accent)] hover:!text-[var(--st-color-bg-primary)] hover:shadow-[0_8px_20px_rgba(77,210,255,0.24)] active:translate-y-px disabled:!cursor-not-allowed disabled:!border-[var(--st-color-border)] disabled:!bg-[rgba(102,111,117,0.18)] disabled:!text-[var(--st-color-text-muted)] disabled:!shadow-none"
                    >
                      发布
                    </Button>
                  </Space>
                </div>

                <div className="min-h-0 flex-1 bg-white p-[18px] [background:radial-gradient(circle_at_top_left,rgba(78,116,248,0.04),transparent_22%),#ffffff]">
                  <Row gutter={24} style={{ height: '100%' }}>
                    <Col span={4}>
                      <div className="flex h-full flex-col gap-3 overflow-auto border-r border-slate-100 p-3">
                        <div className="px-0.5 pb-2 pt-1 text-[13px] font-semibold text-slate-700">
                          节点组件
                        </div>

                        <div className="flex select-none items-start gap-3 rounded-[14px] border border-dashed border-slate-200 bg-slate-50/60 p-3">
                          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-b from-sky-50 to-sky-100 text-sky-600">
                            <Blocks size={16} />
                          </div>
                          <div className="min-w-0">
                            <div className="text-[13px] font-semibold leading-[1.2] text-slate-900">
                              文件链路
                            </div>
                            <div className="mt-1 text-[12px] leading-[1.4] text-slate-500">
                              文件引接任务为固定的 来源 → 去向
                              链路。点击画布节点配置本地上传或远程读取，不支持插入转换节点。
                            </div>
                          </div>
                        </div>
                      </div>
                    </Col>

                    <Col span={20}>
                      <div className="h-full overflow-hidden rounded-2xl border border-dashed border-slate-200 bg-slate-50 p-4 text-[14px] text-slate-400">
                        <FileSyncCanvas
                          nodes={graph.nodes}
                          edges={graph.edges}
                          onNodesChange={handleNodesChange}
                          onNodeDataChange={handleNodeDataChange}
                          datasourceOptions={datasourceOptions}
                        />
                      </div>
                    </Col>
                  </Row>
                </div>
              </div>

              {runVisible && (
                <RunLog
                  runVisible={runVisible}
                  setRunVisible={setRunVisible}
                  baseForm={form}
                  params={params}
                />
              )}
            </div>

            {activeTab && (
              <div
                className="relative flex w-[20px] shrink-0 cursor-col-resize items-center justify-center bg-transparent transition-colors duration-100 hover:bg-[rgba(49,94,251,0.04)]"
                onMouseDown={() => {
                  draggingRef.current = true;
                  document.body.style.cursor = 'col-resize';
                  document.body.style.userSelect = 'none';
                }}
                role="separator"
                aria-orientation="vertical"
                aria-label="调整左右面板宽度"
              >
                <div className="h-full w-px bg-slate-200 transition-colors duration-100" />
                <div className="absolute left-1/2 top-1/2 flex h-[46px] w-5 -translate-x-1/2 -translate-y-1/2 flex-col items-center justify-center gap-1 rounded-full border border-slate-200 bg-white opacity-90 shadow-sm">
                  <span className="block h-1 w-1 rounded-full bg-slate-400" />
                  <span className="block h-1 w-1 rounded-full bg-slate-400" />
                </div>
              </div>
            )}

            <div
              className="h-full shrink-0 overflow-hidden"
              style={{ width: activeTab ? rightWidth : 58 }}
            >
              <RightConfigPanel
                activeTab={activeTab}
                onTabChange={setActiveTab}
                params={params}
                basicConfig={basicConfig}
                setBasicConfig={setBasicConfig}
                scheduleConfig={scheduleConfig}
                setScheduleConfig={setScheduleConfig}
                envConfig={envConfig}
                setEnvConfig={setEnvConfig}
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
