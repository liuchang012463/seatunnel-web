import { PlusOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import type { ReactNode } from 'react';
import TaskListPageHeader from '@/components/TaskListPageHeader';

interface TaskPageHeaderProps {
  icon: ReactNode;
  title: string;
  createText: string;
  onCreate: () => void;
}

const TaskPageHeader: React.FC<TaskPageHeaderProps> = ({
  icon,
  title,
  createText,
  onCreate,
}) => (
  <TaskListPageHeader
    icon={icon}
    title={title}
    actions={
      <Button
        type="primary"
        icon={<PlusOutlined />}
        onClick={onCreate}
        className="task-list-page-header__create-button"
      >
        {createText}
      </Button>
    }
  />
);

export default TaskPageHeader;
