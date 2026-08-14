import { ArrowDownOutlined, ArrowUpOutlined } from '@ant-design/icons';
import { Button, Tooltip } from 'antd';
import React from 'react';

export type TaskSortField = 'name' | 'createTime';
export type TaskSortOrder = 'asc' | 'desc';

interface TaskSortControlsProps {
  field: TaskSortField;
  order: TaskSortOrder;
  onChange: (field: TaskSortField, order: TaskSortOrder) => void;
}

const nextOrder = (active: boolean, order: TaskSortOrder, defaultOrder: TaskSortOrder) =>
  active ? (order === 'asc' ? 'desc' : 'asc') : defaultOrder;

const SortIndicator: React.FC<{ active: boolean; order: TaskSortOrder }> = ({ active, order }) =>
  active ? (order === 'asc' ? <ArrowUpOutlined /> : <ArrowDownOutlined />) : <ArrowDownOutlined className="opacity-30" />;

const TaskSortControls: React.FC<TaskSortControlsProps> = ({ field, order, onChange }) => (
  <div className="flex items-center gap-2" aria-label="任务排序">
    <span className="text-xs text-slate-500">排序</span>
    <Tooltip title={`按任务名称${field === 'name' ? (order === 'asc' ? '升序' : '降序') : '升序'}排列`}>
      <Button
        size="small"
        type={field === 'name' ? 'primary' : 'default'}
        icon={<SortIndicator active={field === 'name'} order={order} />}
        onClick={() => onChange('name', nextOrder(field === 'name', order, 'asc'))}
      >
        名称
      </Button>
    </Tooltip>
    <Tooltip title={`按配置时间${field === 'createTime' ? (order === 'asc' ? '升序' : '降序') : '降序'}排列`}>
      <Button
        size="small"
        type={field === 'createTime' ? 'primary' : 'default'}
        icon={<SortIndicator active={field === 'createTime'} order={order} />}
        onClick={() => onChange('createTime', nextOrder(field === 'createTime', order, 'desc'))}
      >
        配置时间
      </Button>
    </Tooltip>
  </div>
);

export default TaskSortControls;
