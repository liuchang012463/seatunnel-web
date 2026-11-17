import { Breadcrumb } from 'antd';
import React, { useMemo } from 'react';
import { normalizeResourcePath } from '../utils';

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
        title: rootLabel,
        // 面包屑支持目录导航，显式提示鼠标可点击。
        style: { cursor: 'pointer' as const },
        onClick: () => onNavigate('/'),
      },
    ];

    segments.forEach((segment, index) => {
      const segmentPath = `/${segments.slice(0, index + 1).join('/')}`;
      breadcrumbItems.push({
        key: segmentPath,
        title: segment,
        style: { cursor: 'pointer' as const },
        onClick: () => onNavigate(segmentPath),
      });
    });

    return breadcrumbItems;
  }, [normalizedPath, onNavigate, rootLabel]);

  return <Breadcrumb items={items} />;
};

export default ResourceBreadcrumb;
