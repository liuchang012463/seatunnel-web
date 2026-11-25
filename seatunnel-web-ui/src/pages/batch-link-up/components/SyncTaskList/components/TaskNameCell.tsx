import { CopyOutlined } from '@ant-design/icons';
import { Tooltip } from 'antd';
import CatalogText from '@/pages/data-source/components/CatalogText';

interface TaskNameCellProps {
  name: string;
  id: string | number;
  engineJobId?: string | number;
  onCopy?: () => void;
  onOpen?: () => void;
}

export default function TaskNameCell({ name, id, engineJobId, onCopy, onOpen }: TaskNameCellProps) {
  return (
    <div className="sync-task-name-cell">
      <CatalogText className="sync-task-name-cell__title" text={name} onClick={onOpen} />
      <div className="sync-task-name-cell__id">
        <CatalogText text={String(id ?? '-')} />
        {onCopy && (
          <Tooltip title="复制任务定义 ID" trigger={['hover', 'focus']}>
            <button
              type="button"
              className="sync-task-copy-btn"
              aria-label={`复制任务定义 ID ${id}`}
              onClick={(event) => {
                event.stopPropagation();
                onCopy();
              }}
            >
              <CopyOutlined />
            </button>
          </Tooltip>
        )}
      </div>
      {engineJobId != null && (
        <CatalogText className="sync-task-name-cell__engine-id" text={`引擎 Job ID：${engineJobId}`} />
      )}
    </div>
  );
}
