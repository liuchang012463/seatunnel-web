import { FileSyncOutlined, PlusOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Button, message } from 'antd';
import TaskListPageHeader from '@/components/TaskListPageHeader';
import { seatunnelJobDefinitionApi } from '../batch-link-up/api';
import SyncTaskList from '../batch-link-up/components/SyncTaskList';

const FileLinkUpPage: React.FC = () => {
  const createFileTask = async () => {
    try {
      const response = await seatunnelJobDefinitionApi.getUniqueId();
      if (response?.code !== 0 || !response?.data) {
        throw new Error(response?.message || '申请任务定义 ID 失败');
      }
      history.push(
        `/sync/file-link-up/${response.data}/config/file-sync?scene=create`,
      );
    } catch (error: any) {
      message.error(error?.message || '新建文件引接任务失败');
    }
  };

  const editFileTask = (id: string) => {
    if (!id) {
      message.warning('任务定义 ID 不能为空');
      return;
    }
    history.push(`/sync/file-link-up/${id}/config/file-sync?scene=edit`);
  };

  return (
    <div>
      <TaskListPageHeader
        icon={<FileSyncOutlined />}
        title="文件引接任务管理"
        subtitle={
          <>
            独立管理 FTP、SFTP、S3 和 MinIO 的目录或对象前缀同步，不使用表、字段或 SQL 映射。
          </>
        }
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={createFileTask}
            className="h-10 rounded-full px-5"
          >
            新建文件引接任务
          </Button>
        }
      />

      <SyncTaskList
        goDetail={editFileTask}
        mode="FILE_SYNC"
        emptyDescription="暂无文件引接任务"
      />
    </div>
  );
};

export default FileLinkUpPage;
