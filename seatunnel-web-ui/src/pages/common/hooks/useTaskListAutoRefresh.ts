import { useEffect, useRef } from 'react';

// 与后端 JobStatusHelper.runningLikeStatuses 保持一致的非终态任务状态。
const NON_TERMINAL_STATUS_SET = new Set([
  'INITIALIZING',
  'CREATED',
  'PENDING',
  'SCHEDULED',
  'RUNNING',
  'FAILING',
  'DOING_SAVEPOINT',
  'CANCELING',
]);

const AUTO_REFRESH_INTERVAL = 8000;

export const hasNonTerminalTask = (taskList: any[]) =>
  (Array.isArray(taskList) ? taskList : []).some((record) =>
    NON_TERMINAL_STATUS_SET.has(String(record?.lastJobStatus || '').toUpperCase()),
  );

/**
 * 任务列表存在运行中（非终态）任务时，每隔 8 秒调用一次 refresh；
 * 全部为终态后自动停止，卸载时清理定时器。
 */
const useTaskListAutoRefresh = (taskList: any[], refresh: () => void) => {
  const refreshRef = useRef(refresh);

  useEffect(() => {
    refreshRef.current = refresh;
  }, [refresh]);

  const shouldRefresh = hasNonTerminalTask(taskList);

  useEffect(() => {
    if (!shouldRefresh) return undefined;

    const timer = window.setInterval(() => {
      refreshRef.current();
    }, AUTO_REFRESH_INTERVAL);

    return () => window.clearInterval(timer);
  }, [shouldRefresh]);
};

export default useTaskListAutoRefresh;
