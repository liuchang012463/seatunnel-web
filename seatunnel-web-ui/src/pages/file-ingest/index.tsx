import { PlusOutlined } from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import { App, Button, Segmented } from 'antd';
import TaskListPageHeader from '@/components/TaskListPageHeader';
import FileTaskList from './TaskListAdapter';
import OfflineImportTaskList from './import/list';

const OFFLINE_TAB = 'offline-import';

const FileIngestPage: React.FC = () => {
  const { message } = App.useApp();
  const location = useLocation();
  const activeTab = new URLSearchParams(location.search).get('tab') === OFFLINE_TAB
    ? OFFLINE_TAB
    : 'file-ingest';

  const switchTab = (value: string) => {
    history.push(value === OFFLINE_TAB ? '/sync/file-ingest?tab=offline-import' : '/sync/file-ingest');
  };

  const createTask = () => {
    try {
      history.push('/sync/file-ingest/select');
    } catch (error: any) {
      message.error(error?.message || '新建离线文件导入任务失败');
    }
  };

  const editTask = (id: string, item?: any) => {
    if (!id) {
      message.warning('任务定义 ID 不能为空');
      return;
    }

    if (String(item?.sourceType || '').toUpperCase() === 'WEB_UPLOAD') {
      history.push(`/sync/batch-link-up/${id}/config/single?scene=edit`);
      return;
    }

    history.push(`/sync/file-ingest/${id}/config/single?scene=edit`);
  };

  return (
    <div className="task-list-page-shell">
      <TaskListPageHeader
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={createTask}
            className="task-list-page-header__create-button"
          >
            新建导入任务
          </Button>
        }
      >
        <Segmented
          value={activeTab}
          onChange={(value) => switchTab(String(value))}
          options={[
            { label: '文件导入任务', value: 'file-ingest' },
            { label: 'DMP / SQL 导入', value: OFFLINE_TAB },
          ]}
        />
      </TaskListPageHeader>

      {activeTab === OFFLINE_TAB ? (
        <OfflineImportTaskList />
      ) : (
        <FileTaskList
          taskType="FILE_INGEST"
          mode="GUIDE_SINGLE"
          goDetail={(id, item) => editTask(String(id), item)}
          emptyDescription="暂无离线文件导入任务"
        />
      )}
    </div>
  );
};

export default FileIngestPage;
