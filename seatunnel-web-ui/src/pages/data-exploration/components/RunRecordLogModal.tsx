import { ClockCircleOutlined, CopyOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Modal, Space, Spin, Tabs, Tag, message } from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  fetchDataSourceMetadataAgentLogs,
  fetchDataSourceMetadataAgents,
} from '@/pages/data-source/service';
import type { MetadataAgentLogs, MetadataAgentRuns } from '@/pages/data-source/types';

export interface RunRecordLogModalProps {
  open: boolean;
  dataSourceId?: string;
  dataSourceName?: string;
  onClose: () => void;
}

const RUN_LIMIT = 20;

function runStatusLabel(status?: string) {
  if (status === 'SUCCESS') return { label: '成功', color: 'success' as const };
  if (status === 'FAILED') return { label: '失败', color: 'error' as const };
  if (status === 'RUNNING' || status === 'QUEUED') return { label: '执行中', color: 'processing' as const };
  return { label: status || '-', color: 'default' as const };
}

/**
 * Run history of the OpenMetadata agents of one data source, in the shape of the
 * OpenMetadata log page: one tab per agent, the runs of that agent on the left and the
 * log of its last run on the right.
 */
const RunRecordLogModal: React.FC<RunRecordLogModalProps> = ({
  open,
  dataSourceId,
  dataSourceName,
  onClose,
}) => {
  const [agents, setAgents] = useState<MetadataAgentRuns[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [activeAgent, setActiveAgent] = useState<string>();
  const [logs, setLogs] = useState<MetadataAgentLogs>();
  const [logsLoading, setLogsLoading] = useState(false);
  const [logsError, setLogsError] = useState<string>();
  // The log request of the currently selected tab; a slower response for a previous tab must
  // not overwrite the log that belongs to the visible run.
  const logsRequest = useRef(0);
  const activeAgentRef = useRef<string | undefined>(activeAgent);
  useEffect(() => {
    activeAgentRef.current = activeAgent;
  }, [activeAgent]);

  const loadAgents = useCallback(async () => {
    if (!dataSourceId) return;
    setLoading(true);
    setError(undefined);
    try {
      const response = await fetchDataSourceMetadataAgents(dataSourceId, RUN_LIMIT);
      if (response.code !== 0) {
        setError(response.message || '无法读取运行记录');
        setAgents([]);
        return;
      }
      const nextAgents = response.data || [];
      setAgents(nextAgents);
      setActiveAgent((current) => (
        current && nextAgents.some((agent) => agent.agentType === current)
          ? current
          : nextAgents[0]?.agentType
      ));
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || '无法读取运行记录');
      setAgents([]);
    } finally {
      setLoading(false);
    }
  }, [dataSourceId]);

  const loadLogs = useCallback(async (agentType?: string) => {
    if (!dataSourceId || !agentType) return;
    const requestId = logsRequest.current + 1;
    logsRequest.current = requestId;
    setLogsLoading(true);
    setLogsError(undefined);
    setLogs(undefined);
    try {
      const response = await fetchDataSourceMetadataAgentLogs(dataSourceId, agentType);
      if (requestId !== logsRequest.current) return;
      if (response.code !== 0) {
        setLogsError(response.message || '无法读取运行日志');
        setLogs(undefined);
        return;
      }
      setLogs(response.data);
    } catch (err: any) {
      if (requestId !== logsRequest.current) return;
      setLogsError(err?.response?.data?.message || err?.message || '无法读取运行日志');
      setLogs(undefined);
    } finally {
      if (requestId === logsRequest.current) {
        setLogsLoading(false);
      }
    }
  }, [dataSourceId]);

  const refreshRunsAndLogs = useCallback(async () => {
    const currentAgent = activeAgentRef.current;
    await loadAgents();
    if (currentAgent && activeAgentRef.current === currentAgent) {
      await loadLogs(currentAgent);
    }
  }, [loadAgents, loadLogs]);

  useEffect(() => {
    if (!open) return;
    setAgents([]);
    setActiveAgent(undefined);
    setLogs(undefined);
    setLogsError(undefined);
    setError(undefined);
    void loadAgents();
  }, [loadAgents, open]);

  useEffect(() => {
    if (!open || !activeAgent) return;
    void loadLogs(activeAgent);
  }, [activeAgent, loadLogs, open]);

  // OpenMetadata adds a "total" entry with the line count; only the task logs are shown.
  const logEntries = useMemo(
    () => Object.entries(logs?.logs || {}).filter(([task]) => task !== 'total'),
    [logs],
  );

  const copyLog = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      message.success('日志已复制');
    } catch (_) {
      message.error('复制失败，请手动选择日志内容');
    }
  };

  return (
    <Modal
      className="exploration-action-modal exploration-run-record-modal"
      title={(
        <span className="exploration-modal-title">
          {dataSourceName || '数据源'} · 运行记录
        </span>
      )}
      open={open}
      footer={null}
      centered
      width="min(1180px, calc(100vw - 48px))"
      styles={{ body: { paddingTop: 8 } }}
      onCancel={onClose}
    >
      <Spin spinning={loading}>
        {error ? (
          <Alert
            type="warning"
            showIcon
            message="运行记录读取失败"
            description={error}
            action={<Button size="small" onClick={() => void loadAgents()}>重试</Button>}
          />
        ) : agents.length === 0 ? (
          <div className="py-10 text-center text-[var(--st-color-text-muted)]">
            暂无可展示的 agent 运行记录
          </div>
        ) : (
          <Tabs
            className="exploration-run-record-tabs"
            activeKey={activeAgent}
            onChange={(key) => setActiveAgent(key)}
            items={agents.map((agent) => {
              const latestStatus = agent.runs?.[0]
                ? runStatusLabel(agent.runs[0].status)
                : { label: '暂无记录', color: 'default' as const };
              return {
                key: agent.agentType,
                label: (
                  <span className="exploration-run-record__agent-tab">
                    <span>{agent.label}</span>
                    <Tag color={latestStatus.color}>{latestStatus.label}</Tag>
                  </span>
                ),
                children: (
                <div className="exploration-run-record">
                  <aside className="exploration-run-record__runs">
                    <div className="exploration-run-record__runs-head">
                      <span>运行历史 <span className="exploration-run-record__run-count">{agent.runs?.length || 0}</span></span>
                      <Button
                        type="text"
                        size="small"
                        aria-label="刷新运行记录"
                        icon={<ReloadOutlined />}
                        onClick={() => void refreshRunsAndLogs()}
                      />
                    </div>
                    <div className="exploration-run-record__run-list">
                      {(agent.runs || []).length === 0 ? (
                        <div className="exploration-run-record__empty">该 agent 还没有运行记录</div>
                      ) : (agent.runs || []).map((run, index) => {
                        const status = runStatusLabel(run.status);
                        const isLatest = index === 0;
                        return (
                          <div
                            className={`exploration-run-record__run${isLatest ? ' is-latest' : ''}`}
                            key={run.runId}
                          >
                            <div className="exploration-run-record__run-head">
                              <Tag color={status.color}>{status.label}</Tag>
                              <span className="exploration-run-record__run-id" title={run.runId}>
                                {run.runId}
                              </span>
                              {isLatest && <span className="exploration-run-record__latest-label">最新日志</span>}
                            </div>
                            <div className="exploration-run-record__run-time">
                              <ClockCircleOutlined /> {run.startTime || '-'}
                              {run.endTime ? <> → {run.endTime}</> : null}
                            </div>
                            {run.errorMessage && (
                              <div className="exploration-run-record__run-error">
                                {run.errorMessage}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </aside>
                  <section className="exploration-run-record__log">
                    <div className="exploration-run-record__log-head">
                      <div className="exploration-run-record__log-title">
                        <strong>最近一次运行日志</strong>
                        {agent.runs?.[0]?.runId && (
                          <span title={agent.runs[0].runId}>对应运行：{agent.runs[0].runId}</span>
                        )}
                      </div>
                      <Space size="small">
                        <Button
                          type="text"
                          size="small"
                          aria-label="刷新日志"
                          icon={<ReloadOutlined />}
                          onClick={() => void loadLogs(agent.agentType)}
                        />
                        <Button
                          type="text"
                          size="small"
                          icon={<CopyOutlined />}
                          disabled={logEntries.length === 0}
                          onClick={() => void copyLog(logEntries.map(([, text]) => text).join('\n'))}
                        >
                          复制
                        </Button>
                      </Space>
                    </div>
                    <Spin spinning={logsLoading}>
                      {logsError ? (
                        <Alert type="warning" showIcon message="日志读取失败" description={logsError} />
                      ) : logEntries.length === 0 ? (
                        <Empty
                          image={Empty.PRESENTED_IMAGE_SIMPLE}
                          description={logs?.message || '该 agent 暂无运行日志'}
                        />
                      ) : (
                        logEntries.map(([task, text]) => (
                          <div className="exploration-run-record__log-block" key={task}>
                            <div className="exploration-run-record__log-task">{task}</div>
                            <pre className="exploration-run-record__log-text">{text}</pre>
                          </div>
                        ))
                      )}
                    </Spin>
                    <div className="exploration-run-record__note">
                      OpenMetadata 2.0.4 只保留每个 agent 最近一次运行的日志，历史运行仅显示状态与时间。
                    </div>
                  </section>
                </div>
                ),
              };
            })}
          />
        )}
      </Spin>
    </Modal>
  );
};

export default RunRecordLogModal;
