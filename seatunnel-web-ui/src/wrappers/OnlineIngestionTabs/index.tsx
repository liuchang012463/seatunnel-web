import { stripLayoutPrefix, withLayoutPrefix } from '@/utils/iframeLayout';
import { history, Outlet, useLocation } from '@umijs/max';
import { Tabs } from 'antd';
import { findOnlineIngestionTab, onlineIngestionTabs } from '../../../config/onlineIngestion';
import './index.less';

export default function OnlineIngestionTabs() {
  const { pathname } = useLocation();
  // 用现有列表路径识别页签，详情返回、历史链接和列表筛选参数都沿用原来的路由。
  const activeTab = findOnlineIngestionTab(stripLayoutPrefix(pathname)) ?? onlineIngestionTabs[0];

  return (
    <div className="task-list-page-shell online-ingestion-page">
      <Tabs
        activeKey={activeTab.path}
        onChange={(path) => history.push(withLayoutPrefix(path, pathname))}
        items={onlineIngestionTabs.map(({ path, label }) => ({
          key: path,
          label,
          children: path === activeTab.path ? <Outlet /> : null,
        }))}
      />
    </div>
  );
}
