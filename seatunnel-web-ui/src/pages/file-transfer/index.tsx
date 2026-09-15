import { FileSyncOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { message } from 'antd';
import FileTaskList from '../file-ingest/TaskListAdapter';
import TaskPageHeader from '../file-ingest/TaskPageHeader';
import { fileTransferTaskApi } from '../file-ingest/api';
import { FILE_RESOURCE_SOURCE, fileTaskDraftKey } from '../file-ingest/types';

const FileTransferPage: React.FC = () => {
  const createTask = async () => {
    try {
      const response = await fileTransferTaskApi.getUniqueId();
      const id = response?.data as any;
      if (response?.code !== 0 || !id) {
        throw new Error(response?.message || '申请任务定义 ID 失败');
      }

      sessionStorage.setItem(
        fileTaskDraftKey('FILE_TRANSFER', String(id)),
        JSON.stringify({
          id,
          taskType: 'FILE_TRANSFER',
          mode: 'FILE_SYNC',
          sourceType: FILE_RESOURCE_SOURCE,
          targetType: { dbType: 'FTP', connectorType: 'FtpFile', pluginName: 'FtpFile' },
        }),
      );
      history.push(`/sync/file-transfer/${id}/detail?scene=create`);
    } catch (error: any) {
      message.error(error?.message || '新建文件传输任务失败');
    }
  };

  const editTask = (id: string, item?: any) => {
    if (!id) {
      message.warning('任务定义 ID 不能为空');
      return;
    }

    if (String(item?.sourceType || '').toUpperCase() === 'WEB_UPLOAD') {
      history.push(`/sync/file-link-up/${id}/config/file-sync?scene=edit`);
      return;
    }

    history.push(`/sync/file-transfer/${id}/config/file-sync?scene=edit`);
  };

  return (
    <div>
      <TaskPageHeader
        icon={<FileSyncOutlined />}
        title="文件传输"
        subtitle="在文件资源库与 FTP、SFTP、S3、MinIO 之间传输二进制对象，不解析文件内容。"
        createText="新建文件传输"
        onCreate={() => void createTask()}
      />
      <FileTaskList
        taskType="FILE_TRANSFER"
        mode="FILE_SYNC"
        fileMode
        goDetail={(id, item) => editTask(String(id), item)}
        emptyDescription="暂无文件传输任务"
      />
    </div>
  );
};

export default FileTransferPage;
