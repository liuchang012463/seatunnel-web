import type { RequestOptions } from '@/utils/HttpUtils';

export type TaskConfigProblem = 'online' | 'forbidden' | 'missing' | 'unavailable';
export type ConfigRequest<T = any> = (id: string, options: RequestOptions) => Promise<{ code: number; data: T }>;
type ConfigResult<T> = { data: T } | { problem: TaskConfigProblem };
interface ErrorFields {
  code?: number;
  status?: number;
  message?: string;
  msg?: string;
  response?: ErrorFields;
}

function classify(error: unknown): TaskConfigProblem {
  const failure = error as ErrorFields | undefined;
  const code = failure?.response?.code ?? failure?.code;
  const status = failure?.response?.status ?? failure?.status;
  const message = [failure?.response?.msg, failure?.response?.message, failure?.msg, failure?.message].join(' ');

  if (status === 403 || status === 401 || code === 403 || code === 401 ||
      /NOT_LOGIN|forbidden|unauthori[sz]ed|access denied|无权访问|权限不足/i.test(message)) return 'forbidden';
  // Status.BATCH_JOB_DEFINITION_NOT_EXIST also covers streaming definitions and missing configuration content.
  if (status === 404 || code === 404 || code === 11301 || code === 11707 || code === 11807) return 'missing';
  if (/only offline (?:streaming )?job definition can be edited|请先下线任务|任务已上线/i.test(message)) return 'online';
  return 'unavailable';
}

/** Both rejected business errors and HTTP responses returned by the request client reach this page. */
export async function readTaskConfig<T>(
  id: string,
  request: ConfigRequest<T>,
  readReleaseState?: ConfigRequest<{ releaseState?: string | number }>,
): Promise<ConfigResult<T>> {
  const options = { skipErrorHandler: true };
  let failure: unknown;
  try {
    const response = await request(id, options);
    if (response?.code === 0 && response.data && typeof response.data === 'object' && !Array.isArray(response.data)) {
      return { data: response.data };
    }
    failure = response;
  } catch (error) {
    failure = error;
  }

  const problem = classify(failure);
  const fields = failure as ErrorFields | undefined;
  const code = fields?.response?.code ?? fields?.code;
  // Streaming uses the same 11303 for its online guard and query failures. Confirm the release
  // state with the read-only definition endpoint before describing an edit restriction.
  if (problem === 'unavailable' && code === 11303 && readReleaseState) {
    const summary = await readTaskConfig(id, readReleaseState);
    if ('data' in summary) {
      if (summary.data.releaseState === 'ONLINE' || summary.data.releaseState === 1) return { problem: 'online' };
    } else {
      return summary;
    }
  }
  if (problem === 'unavailable') {
    console.warn('[task-config] 读取任务配置失败', { id, code, status: fields?.status ?? fields?.response?.status });
  }
  return { problem };
}
