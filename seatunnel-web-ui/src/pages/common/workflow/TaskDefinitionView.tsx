import { apiPrefix as batchDefinitions } from '@/pages/batch-link-up/api';
import { apiPrefix as streamingDefinitions } from '@/pages/stream-link-up/api';
import HttpUtils from '@/utils/HttpUtils';
import { BizError } from '@/utils/request';
import { history, useParams } from '@umijs/max';
import { Alert, Button, Card, Descriptions, Spin } from 'antd';
import { useEffect, useState } from 'react';

export type TaskKind = 'BATCH' | 'STREAMING';

interface TaskDefinition {
  jobName?: string;
  jobDesc?: string;
  mode?: string;
  releaseState?: string;
  sourceType?: string;
  sinkType?: string;
  sourceDatasourceName?: string;
  sinkDatasourceName?: string;
  sourceTable?: string;
  sinkTable?: string;
}

// Matches Status.BATCH_JOB_DEFINITION_NOT_EXIST for both definition lookups.
const DEFINITION_MISSING = 11301;

export async function readTaskDefinition(kind: TaskKind, id: string): Promise<TaskDefinition | null> {
  const prefix = kind === 'STREAMING' ? streamingDefinitions : batchDefinitions;
  try {
    const result = await HttpUtils.get<TaskDefinition>(`${prefix}/${encodeURIComponent(id)}`, { skipErrorHandler: true });
    if (result?.code === DEFINITION_MISSING) return null;
    if (result?.code === 0 && result.data) return result.data;
    throw new Error('无法读取任务定义');
  } catch (error) {
    if (error instanceof BizError && error.code === DEFINITION_MISSING) return null;
    throw error;
  }
}

const modes: Record<string, string> = {
  GUIDE_SINGLE: '单表同步',
  GUIDE_SINGLE_INCREMENTAL: '单表增量',
  GUIDE_MULTI: '多表同步',
  SCRIPT: '脚本模式',
};

export default function TaskDefinitionView({ kind }: { kind: TaskKind }) {
  const { id } = useParams<{ id: string }>();
  const [definition, setDefinition] = useState<TaskDefinition | null>();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let current = true;
    setDefinition(undefined);
    setFailed(false);
    if (id) {
      readTaskDefinition(kind, id)
        .then((data) => { if (current) setDefinition(data); })
        .catch(() => { if (current) setFailed(true); });
    }
    return () => { current = false; };
  }, [id, kind]);

  return (
    <div style={{ padding: 24 }}>
      <Card
        title={kind === 'STREAMING' ? '实时任务定义' : '批量任务定义'}
        extra={<Button onClick={() => history.push('/sync/topology')}>返回数据拓扑</Button>}
      >
        {failed || !id ? <Alert showIcon type="error" message="无法读取任务定义" />
          : definition === undefined ? <Spin aria-label="正在读取任务定义" />
            : definition === null ? <Alert showIcon type="warning" message="任务已删除" />
              : (
                <Descriptions bordered column={2} items={[
                  { key: 'name', label: '任务名称', children: definition.jobName || '—' },
                  { key: 'id', label: '定义 ID', children: id },
                  { key: 'mode', label: '任务模式', children: modes[definition.mode ?? ''] ?? definition.mode ?? '—' },
                  { key: 'state', label: '发布状态', children: definition.releaseState === 'ONLINE' ? '已上线'
                    : definition.releaseState === 'OFFLINE' ? '已下线' : definition.releaseState ?? '—' },
                  { key: 'source', label: '来源数据源', children: definition.sourceDatasourceName || definition.sourceType || '—' },
                  { key: 'target', label: '目标数据源', children: definition.sinkDatasourceName || definition.sinkType || '—' },
                  { key: 'source-table', label: '来源表', children: definition.sourceTable || '—' },
                  { key: 'target-table', label: '目标表', children: definition.sinkTable || '—' },
                  { key: 'description', label: '任务说明', children: definition.jobDesc || '—', span: 2 },
                ]} />
              )}
      </Card>
    </div>
  );
}
