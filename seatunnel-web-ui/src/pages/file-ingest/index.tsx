import { FileTextOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { message } from 'antd';
import FileTaskList from './TaskListAdapter';
import TaskPageHeader from './TaskPageHeader';
import { fileIngestTaskApi } from './api';
import { FILE_RESOURCE_SOURCE, fileTaskDraftKey } from './types';

const FileIngestPage: React.FC = () => {
  const createTask = async () => {
    try {
      const response = await fileIngestTaskApi.getUniqueId();
      const id = response?.data as any;
      if (response?.code !== 0 || !id) {
        throw new Error(response?.message || '申请任务定义 ID 失败');
      }

      sessionStorage.setItem(
        fileTaskDraftKey('FILE_INGEST', String(id)),
        JSON.stringify({
          id,
          taskType: 'FILE_INGEST',
          mode: 'GUIDE_SINGLE',
          sourceType: FILE_RESOURCE_SOURCE,
          targetType: {
            dbType: 'MYSQL',
            connectorType: 'Jdbc',
            pluginName: 'JDBC-MYSQL',
          },
        }),
      );
      history.push(`/sync/file-ingest/${id}/detail?scene=create`);
    } catch (error: any) {
      message.error(error?.message || '新建文件数据引接任务失败');
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
    <div>
      <TaskPageHeader
        icon={<FileTextOutlined />}
        title="文件数据引接"
        subtitle="从文件资源库读取 CSV、Excel、JSON 或 TXT，配置单表解析、字段映射和目标端入库。"
        createText="新建文件数据引接"
        onCreate={() => void createTask()}
      />
      <FileTaskList
        taskType="FILE_INGEST"
        mode="GUIDE_SINGLE"
        goDetail={(id, item) => editTask(String(id), item)}
        emptyDescription="暂无文件数据引接任务"
      />
    </div>
  );
};

export default FileIngestPage;
