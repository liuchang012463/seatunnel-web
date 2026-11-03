import { history } from '@umijs/max';
import { App, Tabs } from 'antd';
import { useState } from 'react';
import ImportTaskList from './ImportTaskList';
import './index.less';

type ImportTaskKind = 'FILE_INGEST' | 'OFFLINE_IMPORT';

const FileIngestPage: React.FC = () => {
  const { message } = App.useApp();
  const [activeTaskKind, setActiveTaskKind] = useState<ImportTaskKind>(() => (
    typeof window !== 'undefined'
      && new URLSearchParams(window.location.search).get('tab') === 'offline-import'
      ? 'OFFLINE_IMPORT'
      : 'FILE_INGEST'
  ));

  const createTask = () => {
    history.push('/sync/file-ingest/select');
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
    <div className="task-list-page-shell file-ingest-task-page">
      {/* Tabs 只作顶栏切换；列表作为壳层末子节点，走全局列表页 flex 高度链，避免分页被裁切 */}
      <Tabs
        className="file-ingest-task-tabs"
        activeKey={activeTaskKind}
        onChange={(key) => {
          const nextTaskKind = key as ImportTaskKind;
          setActiveTaskKind(nextTaskKind);
          history.replace(nextTaskKind === 'OFFLINE_IMPORT'
            ? '/sync/file-ingest?tab=offline-import'
            : '/sync/file-ingest');
        }}
        items={[
          {
            key: 'FILE_INGEST',
            label: <span className="file-ingest-task-tab-label">文件导入任务</span>,
          },
          {
            key: 'OFFLINE_IMPORT',
            label: <span className="file-ingest-task-tab-label">DMP / SQL 导入</span>,
          },
        ]}
      />
      <div className="file-ingest-task-body">
        <ImportTaskList
          key={activeTaskKind}
          taskKind={activeTaskKind}
          onCreate={createTask}
          goDetail={editTask}
        />
      </div>
    </div>
  );
};

export default FileIngestPage;
