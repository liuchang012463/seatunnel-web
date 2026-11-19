import type { Settings as LayoutSettings } from '@ant-design/pro-components';
import AccessibilityNormalizer from '@/components/AccessibilityNormalizer';
import ThemeConfigProvider from '@/components/ThemeConfigProvider';
import '@ant-design/v5-patch-for-react-19';
import { MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons';
import { Link, type RequestConfig, type RunTimeLayoutConfig } from '@umijs/max';
import { Alert, Spin, Tooltip } from 'antd';
import { isValidElement, Suspense } from 'react';
import 'd3-transition';
import defaultSettings from '../config/defaultSettings';
import { Knowledge } from './components/RightContent';
import { menuData } from './menuData';
import { errorConfig } from './requestErrorConfig';
import { applyNavTheme, persistNavTheme, setNavTheme } from './theme';
import HttpUtils from './utils/HttpUtils';
import { resolveDocumentTitle } from './utils/documentTitle';
import { installIframeHistoryGuard } from './utils/iframeHistoryGuard';
import {
  applyLayoutVisibility,
  shouldHideLayout,
  withLayoutPrefix,
} from './utils/iframeLayout';

const getThemeSettings = (navTheme: 'light' | 'realDark') => ({
  ...defaultSettings,
  navTheme,
  colorPrimary: navTheme === 'light' ? '#0758B8' : '#126681',
});

/**
 * Install the `/iframe` navigation guard after Umi has created history.
 * Do not touch history during module evaluation of this file.
 */
export function render(oldRender: () => void) {
  // Prefer the core history module to avoid a circular import through `@umijs/max`.
  return import('@@/core/history').then(({ history }) => {
    installIframeHistoryGuard(history);
    oldRender();
  });
}

/**
 * @see https://umijs.org/docs/api/runtime-config#getinitialstate
 * */
export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: API.CurrentUser;
  oidcEnabled?: boolean;
  oidcNotice?: string;
  loading?: boolean;
  fetchUserInfo?: () => Promise<API.CurrentUser | undefined>;
}> {
  const fetchUserInfo = async () => {
    try {
      const msg = await HttpUtils.get<API.CurrentUser | undefined>('/api/v1/users/currentUser');

      return msg.data;
    } catch (_error) {
      // The backend owns the current-user context; do not redirect to a local
      // login page when the current-user request is unavailable.
    }
    return undefined;
  };
  let oidcNotice: string | undefined;
  if (typeof window !== 'undefined') {
    const currentUrl = new URL(window.location.href);
    const oidcResult = currentUrl.searchParams.get('oidc');
    if (oidcResult) {
      currentUrl.searchParams.delete('oidc');
      window.history.replaceState(
        window.history.state,
        '',
        `${currentUrl.pathname}${currentUrl.search}${currentUrl.hash}`,
      );
      oidcNotice = oidcResult === 'pending'
        ? '身份已验证，等待管理员绑定本地用户后即可登录。'
        : '统一用户登录失败，请联系管理员或使用本地账号恢复访问。';
    }
  }
  const currentUser = await fetchUserInfo();
  let oidcEnabled = false;
  try {
    const provider = await HttpUtils.get<{ enabled?: boolean }>(
      '/api/v1/unified-auth/oidc/provider',
      { skipErrorHandler: true },
    );
    oidcEnabled = Boolean(provider.data?.enabled);
  } catch (_error) {
    // Keep local authentication available when the optional provider cannot be read.
  }
  // 浅色 v2 尚未完成前，桌面验收固定在深色值班台主题。
  const navTheme = 'realDark' as const;
  setNavTheme(navTheme);
  applyNavTheme(navTheme);
  persistNavTheme(navTheme);
  return {
    fetchUserInfo,
    currentUser,
    oidcEnabled,
    oidcNotice,
    settings: {
      ...getThemeSettings(navTheme),
    } as Partial<LayoutSettings>,
  };
}

// ProLayout 支持的api https://procomponents.ant.design/components/layout
export const layout: RunTimeLayoutConfig = ({ initialState }) => {
  const hideLayout = shouldHideLayout();
  applyLayoutVisibility(hideLayout);

  return {
    menuDataRender: () => initialState?.currentUser?.access === 'admin'
      ? menuData
      : menuData.map((entry) => entry.path === '/menu/operations'
        ? { ...entry, children: entry.children?.filter((child) => child.path !== '/operations/unified-users') }
        : entry),
    onPageChange: (location) => {
      if (typeof document !== 'undefined') {
        document.title = resolveDocumentTitle(location?.pathname || '');
      }
    },
    menuItemRender: (menuItemProps, defaultDom, menuProps) => {
      if (menuItemProps.isUrl || menuItemProps.children) {
        return defaultDom;
      }

      const itemPath = menuItemProps.path
        ? withLayoutPrefix(menuItemProps.path.replace('/*', ''))
        : menuItemProps.path;
      const isCurrent = Boolean(itemPath && menuProps.location?.pathname === itemPath);
      // Always keep link semantics, including the current item: rendering the current item as a
      // bare span removes it from the keyboard tab order and loses aria-current.
      const menuItem = itemPath ? (
        <Link
          to={itemPath}
          target={menuItemProps.target}
          aria-current={isCurrent ? 'page' : undefined}
        >
          {defaultDom}
        </Link>
      ) : (
        defaultDom
      );

      if (!menuProps.collapsed || !menuItemProps.collapsedHoverPanel) {
        return menuItem;
      }

      return (
        <Tooltip
          title={<span className="st-sidebar-leaf-hover-panel__item">{menuItemProps.name}</span>}
          placement="right"
          arrow={false}
          classNames={{ root: "st-sidebar-leaf-hover-panel" }}
        >
          {menuItem}
        </Tooltip>
      );
    },
    collapsedButtonRender: (collapsed, defaultDom) => {
      // ProLayout's defaultDom carries the actual collapse handler; keep using it so the
      // accessible button still toggles the sider.
      const toggle = isValidElement(defaultDom)
        ? (defaultDom.props as { onClick?: () => void }).onClick
        : undefined;
      return (
        <button
          type="button"
          className="ant-pro-sider-collapsed-button st-sider-collapsed-button"
          aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'}
          aria-expanded={!collapsed}
          title={collapsed ? '展开侧边栏' : '收起侧边栏'}
          onClick={toggle}
        >
          {collapsed ? <MenuUnfoldOutlined aria-hidden /> : <MenuFoldOutlined aria-hidden />}
        </button>
      );
    },
    // ProLayout's default header title is an <h1>; each page owns the single h1.
    headerTitleRender: hideLayout ? false : (logo) => (
      <div className="st-app-logo" aria-label="Aircas Web">
        {logo}
        <span className="st-app-logo__title">Aircas Web</span>
      </div>
    ),
    // 主题切换在浅色 token 完整迁移前隐藏，避免同一产品出现两套几何语言。
    actionsRender: () => !initialState?.currentUser?.userid
      ? [
          <Link key="local-login" to="/login">本地账号登录</Link>,
          ...(initialState?.oidcEnabled
            ? [<a key="unified-user-login" href="/api/v1/unified-auth/oidc/start">统一用户登录</a>]
            : []),
          <Knowledge key="knowledge" />,
        ]
      : [<Knowledge key="knowledge" />],
    // 业务水印默认关闭；审计/导出场景另行显式开启。
    waterMarkProps: undefined,
    // Footer 为空组件，关闭以免 ProLayout 预留底部空白导致内容区撑不满视口
    footerRender: false,
    links: [],
    // 自定义 403 页面
    // unAccessible: <div>unAccessible</div>,
    // 路由懒加载只替换内容区：全局 loading.tsx 会卸载侧栏/顶栏，分页或切换路由时
    // 用户会丢失上下文。内层 Suspense 把兜底限制在布局内容区。
    childrenRender: (children) => (
      <Suspense
        fallback={
          <div className="st-route-loading" role="status" aria-live="polite">
            <Spin size="small" />
            <span>页面加载中…</span>
          </div>
        }
      >
        <>
          {initialState?.oidcNotice ? (
            <Alert
              type={initialState.oidcNotice.includes('等待管理员') ? 'info' : 'warning'}
              showIcon
              message={initialState.oidcNotice}
              style={{ marginBottom: 16 }}
            />
          ) : null}
          {children}
        </>
      </Suspense>
    ),
    ...initialState?.settings,
    menuRender: hideLayout
      ? false
      : (_menuProps, defaultDom) => <nav aria-label="主导航">{defaultDom}</nav>,
    headerRender: hideLayout ? false : undefined,
  };
};

/**
 * @name request 配置，可以配置错误处理
 * 它基于 axios 和 ahooks 的 useRequest 提供了一套统一的网络请求和错误处理方案。
 * @doc https://umijs.org/docs/max/request#配置
 */
export const request: RequestConfig = {
  ...errorConfig,
};

/**
 * DESIGN.md §6.2 antd 算法正规化：运行时统一走 ThemeConfigProvider 的
 * theme.algorithm 派生映射令牌（浅色 v2 也在该 Provider 内切换算法）。
 */
export const rootContainer = (container: React.ReactNode) => {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = 'zh-CN';
  }

  return <ThemeConfigProvider><AccessibilityNormalizer />{container}</ThemeConfigProvider>;
};
