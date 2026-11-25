import { Button, Empty } from 'antd';
import type { ReactNode } from 'react';

interface TaskListEmptyProps {
  filters: object;
  description: ReactNode;
  error?: string;
  onClear: () => void;
}

export default function TaskListEmpty({ filters, description, error, onClear }: TaskListEmptyProps) {
  // 未选日期时表单会提交空数组，仍应展示首次使用的空态。
  const filtered = Object.values(filters).some((value) => Array.isArray(value)
    ? value.length > 0
    : value != null && String(value).trim() !== '');

  return (
    <Empty
      image={Empty.PRESENTED_IMAGE_SIMPLE}
      description={error ? '请重试加载任务列表' : filtered ? '没有符合筛选条件的任务' : description}
    >
      {!error && filtered && (
        <Button type="link" onClick={(event) => {
          // 清除后空态按钮会卸载，焦点回到当前列表（可能位于页签内）的任务名输入框。
          event.currentTarget.closest('.sync-task-list')?.querySelector<HTMLInputElement>('input[name="jobName"]')?.focus();
          onClear();
        }}>
          清除筛选
        </Button>
      )}
    </Empty>
  );
}
