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

/** 自动轮询间隔：存在非终态任务时按此间隔刷新列表。 */
export const TASK_LIST_AUTO_REFRESH_INTERVAL = 8000;

/**
 * 列表请求超时：页面级 withTimeout 与请求本身使用同一个值，
 * 否则请求会在页面报超时之后继续挂着，直到客户端默认超时再报一次。
 */
export const TASK_LIST_REQUEST_TIMEOUT = 10000;

export const hasNonTerminalTask = (taskList: any[]) =>
  (Array.isArray(taskList) ? taskList : []).some((record) =>
    NON_TERMINAL_STATUS_SET.has(String(record?.lastJobStatus || '').toUpperCase()),
  );

/**
 * 任务列表存在运行中（非终态）任务时，每隔 8 秒调用一次 refresh；
 * 全部为终态后自动停止，卸载时清理定时器。
 *
 * refresh 收到 silent=true 表示这是一次自动轮询：调用方不要切换 loading，
 * 否则表格每 8 秒被遮罩一次；上一轮还没结束时跳过本轮，避免请求叠加。
 */
const useTaskListAutoRefresh = (
  taskList: any[],
  refresh: (silent: boolean) => void | Promise<unknown>,
) => {
  const refreshRef = useRef(refresh);
  const inFlightRef = useRef(false);

  useEffect(() => {
    refreshRef.current = refresh;
  }, [refresh]);

  const shouldRefresh = hasNonTerminalTask(taskList);

  useEffect(() => {
    if (!shouldRefresh) return undefined;

    const timer = window.setInterval(() => {
      if (inFlightRef.current) return;

      inFlightRef.current = true;
      Promise.resolve(refreshRef.current(true)).finally(() => {
        inFlightRef.current = false;
      });
    }, TASK_LIST_AUTO_REFRESH_INTERVAL);

    return () => window.clearInterval(timer);
  }, [shouldRefresh]);
};

export default useTaskListAutoRefresh;
