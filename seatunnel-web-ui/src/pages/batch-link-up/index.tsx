import { PlusOutlined } from '@ant-design/icons';
import { Button, message } from 'antd';
import { history } from 'umi';
import {
  DEFAULT_TASK_DATASOURCE_TYPE,
  DEFAULT_TASK_TARGET_DATASOURCE_TYPE,
} from '../data-source/dataSourceRegistry';
import { seatunnelJobDefinitionApi } from './api';
import SyncTaskList from './components/SyncTaskList';
import './index.less';

// import SyncTaskList from "./SyncTaskList";

const App = () => {
  const sourceType = { ...DEFAULT_TASK_DATASOURCE_TYPE };
  const targetType = { ...DEFAULT_TASK_TARGET_DATASOURCE_TYPE };

  /**
   * 新增场景：
   * 先申请唯一ID，继续沿用缓存方式进入详情页
   */
  const goDetail = () => {
    seatunnelJobDefinitionApi.getUniqueId().then((data) => {
      if (data?.code === 0) {
        const returnId = data?.data;

        sessionStorage.setItem(
          `batch-link-up-detail-${returnId}`,
          JSON.stringify({
            sourceType,
            targetType,
            id: returnId,
          }),
        );

        history.push(`/sync/batch-link-up/${returnId}/detail`);
      } else {
      }
    });
  };

  /**
   * 编辑场景：
   * 不再进入 DetailPage，而是先查编辑详情，根据 mode 直接跳转到对应配置页
   */
  const goEdit = async (id: string, item: any) => {
    if (!id) {
      message.warning('任务定义ID不能为空');
      return;
    }

    console.log(item);

    try {
      const mode = item?.mode;

      if (mode === 'GUIDE_SINGLE') {
        history.push(`/sync/batch-link-up/${id}/config/single?scene=edit`);
        return;
      }

      if (mode === 'GUIDE_SINGLE_INCREMENTAL') {
        history.push(`/sync/batch-link-up/${id}/config/single-incremental?scene=edit`);
        return;
      }

      if (mode === 'FILE_SYNC') {
        history.push(`/sync/batch-link-up/${id}/config/file-sync?scene=edit`);
        return;
      }

      if (mode === 'GUIDE_MULTI') {
        history.push(`/sync/batch-link-up/${id}/config/multi?scene=edit`);
        return;
      }

      if (mode === 'SCRIPT') {
        history.push(`/sync/batch-link-up/${id}/config/script?scene=edit`);
        return;
      }
    } catch (error) {}
  };

  return (
    <div className="task-list-page-shell">
      <SyncTaskList
        goDetail={goEdit}
        createAction={(
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={goDetail}
            className="task-search-create"
          >
            创建任务
          </Button>
        )}
        taskType="BATCH"
        excludeMode="FILE_SYNC"
      />
    </div>
  );
};

export default App;
