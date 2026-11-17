import { Breadcrumb } from 'antd';
import React, { useMemo } from 'react';
import { normalizeResourcePath } from '../utils';
import './ResourceBreadcrumb.less';

export interface ResourceBreadcrumbProps {
  path: string;
  onNavigate: (path: string) => void;
  rootLabel?: React.ReactNode;
}

const ResourceBreadcrumb: React.FC<ResourceBreadcrumbProps> = ({ path, onNavigate, rootLabel = '湖文件' }) => {
  const normalizedPath = normalizeResourcePath(path);
  const items = useMemo(() => {
    const segments = normalizedPath.split('/').filter(Boolean);
    const breadcrumbItems = [
      {
        key: '/',
        // 直接使用按钮承载导航，避免外层 cursor 被 Breadcrumb 内部文字样式覆盖。
        title: <button type="button" className="resource-breadcrumb__link" onClick={() => onNavigate('/')}>{rootLabel}</button>,
      },
    ];

    segments.forEach((segment, index) => {
      const segmentPath = `/${segments.slice(0, index + 1).join('/')}`;
      breadcrumbItems.push({
        key: segmentPath,
        title: <button type="button" className="resource-breadcrumb__link" onClick={() => onNavigate(segmentPath)}>{segment}</button>,
      });
    });

    return breadcrumbItems;
  }, [normalizedPath, onNavigate, rootLabel]);

  return <Breadcrumb items={items} />;
};

export default ResourceBreadcrumb;
