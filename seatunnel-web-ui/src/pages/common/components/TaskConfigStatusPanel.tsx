import { Alert, Button, Space, Spin } from 'antd';
import type { TaskConfigStatus } from '../hooks/useTaskConfig';

const notices = {
  online: ['任务已上线', '任务下线后可以编辑配置。'],
  forbidden: ['无权访问任务配置', '请联系管理员确认当前账号的任务访问权限。'],
  missing: ['未找到任务配置', '请返回任务列表，确认任务是否仍然存在。'],
  unavailable: ['任务配置读取失败', '请检查网络连接后重试，或返回任务列表。'],
  'draft-missing': ['未找到创建草稿', '请从任务列表重新进入创建向导。'],
  'draft-unreadable': ['创建草稿读取失败', '请返回任务列表，再打开创建向导。'],
};

interface Props {
  status: Exclude<TaskConfigStatus, 'ready'>;
  onBack: () => void;
  backLabel?: string;
  onRetry: () => void;
  onView?: () => void;
}

export default function TaskConfigStatusPanel({ status, onBack, backLabel = '返回任务列表', onRetry, onView }: Props) {
  if (status === 'loading') {
    return (
      <div className="flex items-center justify-center gap-3 p-8">
        <span className="inline-flex items-center gap-2" role="status"><Spin />正在读取任务配置…</span>
        <Button onClick={onBack}>{backLabel}</Button>
      </div>
    );
  }
  const [title, description] = notices[status];
  return (
    <div className="mx-auto w-full max-w-2xl p-6">
      <Alert
        type={status === 'online' || status === 'missing' || status === 'draft-missing' ? 'warning' : 'error'}
        showIcon
        message={title}
        description={(
          <>
            <div>{description}</div>
            <Space wrap className="mt-3">
              {status === 'unavailable' && <Button type="primary" autoInsertSpace={false} onClick={onRetry}>重试</Button>}
              {status === 'online' && onView && <Button type="primary" onClick={onView}>查看配置</Button>}
              <Button onClick={onBack}>{backLabel}</Button>
            </Space>
          </>
        )}
      />
    </div>
  );
}
