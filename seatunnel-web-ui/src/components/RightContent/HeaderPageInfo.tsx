import type { MenuDataItem } from '@ant-design/pro-components';
import { useLocation } from '@umijs/max';
import React from 'react';
import { prototypeMenuData } from '@/prototype/menuData';

const findMenuName = (items: MenuDataItem[], pathname: string): string | undefined => {
  for (const item of items) {
    if (item.children?.length) {
      const childName = findMenuName(item.children, pathname);
      if (childName) {
        return childName;
      }
    }
    if (item.path && (pathname === item.path || pathname.startsWith(`${item.path}/`))) {
      return typeof item.name === 'string' ? item.name : undefined;
    }
  }
  return undefined;
};

const rawAppEnv = process.env.REACT_APP_ENV;
const ENV_LABEL = rawAppEnv === 'prod' ? '生产' : rawAppEnv === 'pre' ? '预发' : '测试';

/**
 * 顶栏左侧信息：环境徽标 + 当前页面标题（与菜单同词）。
 */
const HeaderPageInfo: React.FC = () => {
  const location = useLocation();
  const pageName = findMenuName(prototypeMenuData, location.pathname);

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        height: '100%',
        width: 'max-content',
        lineHeight: 'normal',
        whiteSpace: 'nowrap',
      }}
    >
      <span
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          height: 20,
          padding: '0 8px',
          fontSize: 12,
          lineHeight: '18px',
          color: 'var(--st-color-text-muted)',
          background: 'var(--st-color-bg-control)',
          border: '1px solid var(--st-color-divider-subtle)',
          borderRadius: 'var(--st-radius-sm)',
        }}
      >
        <i
          style={{
            width: 6,
            height: 6,
            borderRadius: '50%',
            background: 'var(--st-color-accent)',
          }}
        />
        {ENV_LABEL}
      </span>
      {pageName && (
        <>
          <span
            style={{
              width: 1,
              height: 14,
              margin: '0 12px',
              background: 'var(--st-color-divider-subtle)',
            }}
          />
          <span
            style={{
              fontSize: 14,
              fontWeight: 500,
              color: 'var(--st-color-text-primary)',
            }}
          >
            {pageName}
          </span>
        </>
      )}
    </div>
  );
};

export default HeaderPageInfo;
