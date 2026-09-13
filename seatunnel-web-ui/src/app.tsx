import type { Settings as LayoutSettings } from '@ant-design/pro-components';
import { Footer } from '@/components';
import ThemeConfigProvider from '@/components/ThemeConfigProvider';
import '@ant-design/v5-patch-for-react-19';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import 'd3-transition';
import defaultSettings from '../config/defaultSettings';
import { Knowledge } from './components/RightContent';
import { prototypeMenuData } from './prototype/menuData';
import { isPrototypeMode } from './prototype/mode';
import PrototypeAnnotationBar from './prototype/PrototypeAnnotationBar';
import { errorConfig } from './requestErrorConfig';
import ThemeSwitch from './components/RightContent/ThemeSwitch';
import { applyNavTheme, getStoredNavTheme, setNavTheme } from './theme';
import HttpUtils from './utils/HttpUtils';
import { applyLayoutVisibility, shouldHideLayout } from './utils/iframeLayout';

const isDev = process.env.NODE_ENV === 'development';
const prototypeUser = {
  name: '原型演示用户',
  avatar: '',
  userid: 'prototype-sso-user',
  access: 'admin',
} as API.CurrentUser;

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
    if (isPrototypeMode) {
      return prototypeUser;
    }
    try {
      const msg = await HttpUtils.get<API.CurrentUser | undefined>('/api/v1/users/currentUser');

      return msg.data;
    } catch (_error) {
      // The backend owns the current-user context; do not redirect to a local
      // login page when the current-user request is unavailable.
    }
    return undefined;
  };
  if (isPrototypeMode) {
    const navTheme = getStoredNavTheme();
    setNavTheme(navTheme);
    applyNavTheme(navTheme);
    return {
      fetchUserInfo,
      currentUser: prototypeUser,
      settings: {
        ...getThemeSettings(navTheme),
        title: '数据采集引接软件',
      } as Partial<LayoutSettings>,
    };
  }
  const currentUser = await fetchUserInfo();
  // 浅色 v2（DESIGN.md §6.4）：恢复读取持久化主题，切换入口回到顶栏。
  const navTheme = getStoredNavTheme();
  setNavTheme(navTheme);
  applyNavTheme(navTheme);
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
  const pathname = typeof window === 'undefined' ? '' : window.location.pathname;
  const showWatermark = !pathname.startsWith('/lake/') && pathname !== '/data-source/master-data';

  return {
    menuDataRender: () => prototypeMenuData,
    actionsRender: () =>
      isPrototypeMode
        ? []
        : [<ThemeSwitch key="theme" />, <Knowledge key="knowledge" />],
    waterMarkProps: showWatermark
      ? {
          content: initialState?.currentUser?.name,
          fontSize: 13,
          fontColor: 'rgba(143, 173, 186, 0.10)',
        }
      : undefined,
    footerRender: () => <Footer />,
    bgLayoutImgList: isPrototypeMode
      ? []
      : [
          {
            src: 'https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/D2LWSqNny4sAAAAAAAAAAAAAFl94AQBr',
            left: 85,
            bottom: 100,
            height: '303px',
          },
          {
            src: 'https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/C2TWRpJpiC0AAAAAAAAAAAAAFl94AQBr',
            bottom: -68,
            right: -45,
            height: '303px',
          },
          {
            src: 'https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/F6vSTbj8KpYAAAAAAAAAAAAAFl94AQBr',
            bottom: 0,
            left: 0,
            width: '331px',
          },
        ],
    links: [],
    // 自定义 403 页面
    // unAccessible: <div>unAccessible</div>,
    // 增加一个 loading 的状态
    childrenRender: (children) => {
      const content = isPrototypeMode ? <PrototypeAnnotationBar>{children}</PrototypeAnnotationBar> : children;
      return content;
    },
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
export const rootContainer = (container: React.ReactNode) => (
  <ThemeConfigProvider>{container}</ThemeConfigProvider>
);
