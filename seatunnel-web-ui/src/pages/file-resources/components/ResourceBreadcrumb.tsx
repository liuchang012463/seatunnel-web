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
        onClick: () => onNavigate('/'),
      },
    ];

    segments.forEach((segment, index) => {
      const segmentPath = `/${segments.slice(0, index + 1).join('/')}`;
      breadcrumbItems.push({
        key: segmentPath,
        title: segment,
        onClick: () => onNavigate(segmentPath),
      });
    });

    return breadcrumbItems;
  }, [normalizedPath, onNavigate, rootLabel]);

  return <Breadcrumb items={items} />;
};

export default ResourceBreadcrumb;
