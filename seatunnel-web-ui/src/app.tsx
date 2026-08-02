import type { Settings as LayoutSettings } from '@ant-design/pro-components';
import { SettingDrawer } from '@ant-design/pro-components';
import { AvatarDropdown, AvatarName, Footer } from '@/components';
import '@ant-design/v5-patch-for-react-19';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import 'd3-transition';
import defaultSettings from '../config/defaultSettings';
import { GlobalSearch, Knowledge } from './components/RightContent';
import ThemeSwitch from './components/RightContent/ThemeSwitch';
import { prototypeMenuData } from './prototype/menuData';
import { isPrototypeMode } from './prototype/mode';
import PrototypeAnnotationBar from './prototype/PrototypeAnnotationBar';
import { errorConfig } from './requestErrorConfig';
import { applyNavTheme, getStoredNavTheme } from './theme';
import HttpUtils from './utils/HttpUtils';
import {
  applySidebarVisibility,
  shouldHideSidebar,
} from './utils/iframeLayout';

const isDev = process.env.NODE_ENV === 'development';
const hideSidebar = shouldHideSidebar();
applySidebarVisibility(hideSidebar);
const prototypeUser = {
  name: '原型演示用户',
  avatar: '',
  userid: 'prototype-sso-user',
  access: 'admin',
} as API.CurrentUser;

const getThemeSettings = (navTheme: 'light' | 'realDark') => ({
  ...defaultSettings,
  navTheme,
  colorPrimary: navTheme === 'light' ? 'hsl(231 48% 48%)' : '#2187A8',
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
      const msg = await HttpUtils.get<API.CurrentUser | undefined>(
        '/api/v1/users/currentUser',
      );

      return msg.data;
    } catch (_error) {
      // The backend owns the current-user context; do not redirect to a local
      // login page when the current-user request is unavailable.
    }
    return undefined;
  };
  if (isPrototypeMode) {
    const navTheme = getStoredNavTheme(
      defaultSettings.navTheme === 'light' ? 'light' : 'realDark',
    );
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
  const navTheme = getStoredNavTheme(
    defaultSettings.navTheme === 'light' ? 'light' : 'realDark',
  );
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
export const layout: RunTimeLayoutConfig = ({
  initialState,
  setInitialState,
}) => {
  return {
    menuDataRender: () => prototypeMenuData,
    menuProps: {
      defaultOpenKeys: isPrototypeMode
        ? [
            '/menu/reporting',
            '/menu/resources',
            '/menu/ingestion',
            '/menu/operations',
            '/menu/lake',
            '/menu/system',
          ]
        : ['/menu/ingestion'],
    },
    actionsRender: () =>
      isPrototypeMode
        ? []
        : [
            <GlobalSearch key="globalsearch" />,
            <Knowledge key="knowledge" />,
            <ThemeSwitch key="theme-switch" />,
          ],
    avatarProps: {
      src: initialState?.currentUser?.avatar,
      title: <AvatarName />,
      render: (_, avatarChildren) => {
        return <AvatarDropdown>{avatarChildren}</AvatarDropdown>;
      },
    },
    waterMarkProps: {
      content: initialState?.currentUser?.name,
    },
    footerRender: () => <Footer />,
    bgLayoutImgList: isPrototypeMode ? [] : [
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
      // if (initialState?.loading) return <PageLoading />;
      const content = isPrototypeMode ? (
        <PrototypeAnnotationBar>{children}</PrototypeAnnotationBar>
      ) : (
        children
      );
      return (
        <>
          {content}
          {isDev && !isPrototypeMode && (
            <SettingDrawer
              disableUrlParams
              enableDarkTheme
              settings={initialState?.settings}
              onSettingChange={(settings) => {
                setInitialState((preInitialState) => ({
                  ...preInitialState,
                  settings,
                }));
              }}
            />
          )}
        </>
      );
    },
    ...initialState?.settings,
    menuRender: hideSidebar ? false : initialState?.settings?.menuRender,
    menuHeaderRender: hideSidebar ? false : undefined,
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
