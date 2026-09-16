import type { Settings as LayoutSettings } from '@ant-design/pro-components';
import { Footer } from '@/components';
import ThemeConfigProvider from '@/components/ThemeConfigProvider';
import '@ant-design/v5-patch-for-react-19';
import { Link, type RequestConfig, type RunTimeLayoutConfig } from '@umijs/max';
import { Tooltip } from 'antd';
import 'd3-transition';
import defaultSettings from '../config/defaultSettings';
import { Knowledge } from './components/RightContent';
import { menuData } from './menuData';
import { errorConfig } from './requestErrorConfig';
import { applyNavTheme, persistNavTheme, setNavTheme } from './theme';
import HttpUtils from './utils/HttpUtils';
import { applyLayoutVisibility, shouldHideLayout } from './utils/iframeLayout';

const getThemeSettings = (navTheme: 'light' | 'realDark') => ({
  ...defaultSettings,
  navTheme,
  colorPrimary: navTheme === 'light' ? '#1B87A8' : '#1B87A8',
});

/**
 * @see https://umijs.org/docs/api/runtime-config#getinitialstate
 * */
export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: API.CurrentUser;
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
  const currentUser = await fetchUserInfo();
  // 浅色 v2 尚未完成前，桌面验收固定在深色值班台主题。
  const navTheme = 'realDark' as const;
  setNavTheme(navTheme);
  applyNavTheme(navTheme);
  persistNavTheme(navTheme);
  return {
    fetchUserInfo,
    currentUser,
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
    menuDataRender: () => menuData,
    menuItemRender: (menuItemProps, defaultDom, menuProps) => {
      if (menuItemProps.isUrl || menuItemProps.children) {
        return defaultDom;
      }

      const itemPath = menuItemProps.path;
      const menuItem = itemPath && menuProps.location?.pathname !== itemPath ? (
        <Link to={itemPath.replace('/*', '')} target={menuItemProps.target}>
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
    // 主题切换在浅色 token 完整迁移前隐藏，避免同一产品出现两套几何语言。
    actionsRender: () => [<Knowledge key="knowledge" />],
    // 业务水印默认关闭；审计/导出场景另行显式开启。
    waterMarkProps: undefined,
    footerRender: () => <Footer />,
    links: [],
    // 自定义 403 页面
    // unAccessible: <div>unAccessible</div>,
    // 增加一个 loading 的状态
    childrenRender: (children) => children,
    ...initialState?.settings,
    menuRender: hideLayout ? false : initialState?.settings?.menuRender,
    menuHeaderRender: hideLayout ? false : undefined,
    headerRender: hideLayout ? false : undefined,
  };
};

/**
 * @name request 配置，可以配置错误处理
 * 它基于 axios 和 ahooks 的 useRequest 提供了一套统一的网络请求和错误处理方案。
 * @doc https://umijs.org/docs/max/request#配置
 */
export const request: RequestConfig = {
  baseURL: 'https://proapi.azurewebsites.net',
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

  return <ThemeConfigProvider>{container}</ThemeConfigProvider>;
};
