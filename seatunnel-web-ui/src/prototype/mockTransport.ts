import type {
  PrototypeRecord,
  PrototypeRequest,
  PrototypeRequestHandler,
} from './types';

const MOCK_KEY = 'seatunnel-prototype:http-records';

const initialRecords: PrototypeRecord[] = [
  {
    id: '1001',
    name: '装备主数据离线引接',
    type: 'MySQL → S3',
    status: 'ONLINE',
    owner: '张工',
    updatedAt: '2026-07-27 15:20',
    description: '原型请求适配器中的离线任务',
    mode: 'GUIDE_SINGLE',
    jobName: '装备主数据离线引接',
    releaseState: 'ONLINE',
    lastJobStatus: 'FINISHED',
  },
  {
    id: '1002',
    name: '遥测数据实时引接',
    type: 'Kafka → Paimon',
    status: 'RUNNING',
    owner: '李工',
    updatedAt: '2026-07-27 15:25',
    description: '原型请求适配器中的实时任务',
    mode: 'GUIDE_SINGLE',
    jobName: '遥测数据实时引接',
    releaseState: 'ONLINE',
    lastJobStatus: 'RUNNING',
    instanceId: 2002,
    clientId: 1,
    engineJobId: '3002',
  },
];

const readRecords = (): PrototypeRecord[] => {
  if (typeof window === 'undefined') return [...initialRecords];
  const cached = window.localStorage.getItem(MOCK_KEY);
  if (!cached) {
    window.localStorage.setItem(MOCK_KEY, JSON.stringify(initialRecords));
    return [...initialRecords];
  }
  try {
    return JSON.parse(cached) as PrototypeRecord[];
  } catch {
    return [...initialRecords];
  }
};

const writeRecords = (records: PrototypeRecord[]) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(MOCK_KEY, JSON.stringify(records));
  }
};

const success = (data: any = null) => ({
  code: 0,
  msg: 'prototype success',
  message: 'prototype success',
  data,
});

const statusFromUrl = (url: string) => {
  if (url.includes('/offline') || url.includes('/pause')) return 'OFFLINE';
  if (url.includes('/online') || url.includes('/execute')) return 'RUNNING';
  return undefined;
};

export const resetPrototypeRequests = () => {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(MOCK_KEY);
  }
};

export const handlePrototypeRequest: PrototypeRequestHandler = async ({
  url,
  method,
  body,
}: PrototypeRequest) => {
  const normalizedMethod = method.toUpperCase();
  let records = readRecords();

  if (url.includes('/api/v1/users/currentUser')) {
    return success({
      userid: 'prototype-sso-user',
      name: '原型演示用户',
      access: 'admin',
    });
  }
  if (url.includes('/get-unique-id')) {
    return success(String(Date.now()));
  }
  if (url.includes('/metrics')) {
    return success({
      cpuUsage: 36,
      memoryUsage: 58,
      threadCount: 42,
      runningOps: 7,
    });
  }
  if (url.includes('/channel-types')) {
    return success(['EMAIL', 'WEBHOOK', 'DINGTALK']);
  }
  if (url.includes('/records')) {
    return success({
      bizData: records.map((item, index) => ({
        ...item,
        level: index ? 'WARNING' : 'ERROR',
      })),
      pagination: { pageNo: 1, pageSize: 10, total: records.length },
    });
  }
  if (url.includes('/channels') || url.includes('/rules')) {
    return success(records);
  }
  if (url.includes('/option') || url.endsWith('/all')) {
    return success(
      records.map((item) => ({ value: item.id, label: item.name, ...item })),
    );
  }
  if (
    url.includes('/connect-test') ||
    url.includes('/verify-datasource') ||
    url.includes('/preview')
  ) {
    return success({ connected: true, rows: 20 });
  }
  if (url.includes('/page')) {
    return success({
      bizData: records,
      pagination: {
        pageNo: Number(body?.pageNo || 1),
        pageSize: Number(body?.pageSize || 10),
        total: records.length,
      },
    });
  }

  const nextStatus = statusFromUrl(url);
  if (nextStatus) {
    const id = url.match(/\/(\d+)(?:\/|$|\?)/)?.[1];
    records = records.map((item) =>
      !id || item.id === id
        ? {
            ...item,
            status: nextStatus,
            releaseState: nextStatus === 'OFFLINE' ? 'OFFLINE' : 'ONLINE',
            lastJobStatus: nextStatus,
          }
        : item,
    );
    writeRecords(records);
    return success(true);
  }

  if (normalizedMethod === 'DELETE') {
    const id = url.match(/\/([^/]+)$/)?.[1];
    records = records.filter((item) => item.id !== id);
    writeRecords(records);
    return success(true);
  }

  if (['POST', 'PUT'].includes(normalizedMethod) && body) {
    const incoming = body as PrototypeRecord;
    const id = String(incoming.id || Date.now());
    const next: PrototypeRecord = {
      ...incoming,
      id,
      name: incoming.name || incoming.jobName || `原型记录 ${id}`,
      type: incoming.type || incoming.mode || 'SeaTunnel',
      status: incoming.status || 'DRAFT',
      owner: incoming.owner || '当前 SSO 用户',
      updatedAt: '2026-07-27 16:40',
      description: incoming.description || '由原型请求适配器创建',
    };
    records = [next, ...records.filter((item) => item.id !== id)];
    writeRecords(records);
    return success(id);
  }

  const id = url.match(/\/(\d+)(?:$|\?)/)?.[1];
  if (id) return success(records.find((item) => item.id === id) || records[0]);

  return success(records);
};
