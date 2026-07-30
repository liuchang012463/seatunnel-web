import { FileSyncOutlined, PlusOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Button, message } from 'antd';
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
      <div className="mx-4 mb-5 rounded-[20px] border border-indigo-100 bg-white/90 p-5">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-indigo-50 text-xl text-indigo-600">
              <FileSyncOutlined />
            </div>
            <div>
              <h1 className="m-0 text-xl font-semibold text-slate-900">
                文件引接任务管理
              </h1>
              <p className="mb-0 mt-1 text-sm text-slate-500">
                独立管理 FTP、SFTP、S3 和 MinIO
                的目录或对象前缀同步，不使用表、字段或 SQL 映射。
              </p>
            </div>
          </div>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={createFileTask}
            className="h-10 rounded-full px-5"
          >
            新建文件引接任务
          </Button>
        </div>
      </div>

      <SyncTaskList
        goDetail={editFileTask}
        mode="FILE_SYNC"
        emptyDescription="暂无文件引接任务"
      />
    </div>
  );
};

export default FileLinkUpPage;
