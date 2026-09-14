// https://umijs.org/config/

import { join } from "node:path";
import { defineConfig } from "@umijs/max";
import defaultSettings from "./defaultSettings";
import proxy from "./proxy";

import routes from "./routes";

const { REACT_APP_ENV = "dev" } = process.env;

/**
 * @name 使用公共路径
 * @description 部署时的路径，如果部署在非根目录下，需要配置这个变量
 * @doc https://umijs.org/docs/api/config#publicpath
 */
const PUBLIC_PATH: string = "/";

export default defineConfig({
  /**
   * @name 开启 hash 模式
   * @description 让 build 之后的产物包含 hash 后缀。通常用于增量发布和避免浏览器加载缓存。
   * @doc https://umijs.org/docs/api/config#hash
   */
  hash: true,

  publicPath: PUBLIC_PATH,

  /**
   * @name 兼容性设置
   * @description 设置 ie11 不一定完美兼容，需要检查自己使用的所有依赖
   * @doc https://umijs.org/docs/api/config#targets
   */
  // targets: {
  //   ie: 11,
  // },
  /**
   * @name 路由的配置，不在路由中引入的文件不会编译
   * @description 只支持 path，component，routes，redirect，wrappers，title 的配置
   * @doc https://umijs.org/docs/guides/routes
   */
  // umi routes: https://umijs.org/docs/routing
  routes,
  /**
   * @name 主题的配置
   * @description 虽然叫主题，但是其实只是 less 的变量设置
   * @doc antd的主题设置 https://ant.design/docs/react/customize-theme-cn
   * @doc umi 的 theme 配置 https://umijs.org/docs/api/config#theme
   */
  // theme: { '@primary-color': '#1DA57A' }
  /**
   * @name moment 的国际化配置
   * @description 如果对国际化没有要求，打开之后能减少js的包大小
   * @doc https://umijs.org/docs/api/config#ignoremomentlocale
   */
  ignoreMomentLocale: true,
  /**
   * @name 代理配置
   * @description 可以让你的本地服务器代理到你的服务器上，这样你就可以访问服务器的数据了
   * @see 要注意以下 代理只能在本地开发时使用，build 之后就无法使用了。
   * @doc 代理介绍 https://umijs.org/docs/guides/proxy
   * @doc 代理配置 https://umijs.org/docs/api/config#proxy
   */
  proxy: proxy[REACT_APP_ENV as keyof typeof proxy],
  /**
   * @name 快速热更新配置
   * @description 一个不错的热更新组件，更新时可以保留 state
   */
  fastRefresh: true,
  //============== 以下都是max的插件配置 ===============
  /**
   * @name 数据流插件
   * @@doc https://umijs.org/docs/max/data-flow
   */
  model: {},
  /**
   * 一个全局的初始数据流，可以用它在插件之间共享数据
   * @description 可以用来存放一些全局的数据，比如用户信息，或者一些全局的状态，全局初始状态在整个 Umi 项目的最开始创建。
   * @doc https://umijs.org/docs/max/data-flow#%E5%85%A8%E5%B1%80%E5%88%9D%E5%A7%8B%E7%8A%B6%E6%80%81
   */
  initialState: {},
  /**
   * @name layout 插件
   * @doc https://umijs.org/docs/max/layout-menu
   */
  title: "Aircas Web",
  layout: {
    locale: true,
    ...defaultSettings,
  },
  /**
   * @name moment2dayjs 插件
   * @description 将项目中的 moment 替换为 dayjs
   * @doc https://umijs.org/docs/max/moment2dayjs
   */
  moment2dayjs: {
    preset: "antd",
    plugins: ["duration"],
  },
  /**
   * @name 国际化插件
   * @doc https://umijs.org/docs/max/i18n
   */
  locale: {
    // default zh-CN
    default: "zh-CN",
    antd: true,
    // default true, when it is true, will use `navigator.language` overwrite default
    baseNavigator: true,
  },
  /**
   * @name antd 插件
   * @description 内置了 babel import 插件
   * @doc https://umijs.org/docs/max/antd#antd
   */
  antd: {
    appConfig: {},
    configProvider: {
      theme: {
        cssVar: true,
        token: {
          colorPrimary: "#1B87A8",
          colorLink: "#3FC6FF",
          colorLinkHover: "#7FD8FF",
          colorInfo: "#3FC6FF",
          colorSuccess: "#3DD68C",
          colorWarning: "#F5B83D",
          colorError: "#FF6B5E",
          colorBgBase: "#01151D",
          colorBgLayout: "#02222D",
          colorBgContainer: "#052F3F",
          colorBgElevated: "#0A3D52",
          colorBgSpotlight: "#052F3F",
          colorText: "#EDF4F7",
          colorTextSecondary: "#AFC4CD",
          colorTextTertiary: "#6C8792",
          colorTextQuaternary: "#6C8792",
          colorTextDisabled: "#6C8792",
          colorBorder: "rgba(126, 183, 208, 0.30)",
          colorBorderSecondary: "rgba(126, 183, 208, 0.14)",
          colorSplit: "rgba(126, 183, 208, 0.14)",
          colorFill: "rgba(63, 198, 255, 0.10)",
          colorFillSecondary: "rgba(63, 198, 255, 0.08)",
          colorFillTertiary: "rgba(126, 183, 208, 0.12)",
          colorFillQuaternary: "rgba(126, 183, 208, 0.08)",
          controlItemBgActive: "#1B87A8",
          controlItemBgHover: "rgba(63, 198, 255, 0.08)",
          controlOutline: "rgba(63, 198, 255, 0.35)",
          fontFamily:
            '"MiSans", "HarmonyOS Sans SC", "Source Han Sans SC", "Microsoft YaHei", "微软雅黑", Arial, sans-serif',
          fontSize: 14,
          fontSizeHeading1: 28,
          fontSizeHeading2: 20,
          fontSizeHeading3: 16,
          fontSizeHeading4: 14,
          fontSizeHeading5: 13,
          lineHeight: 1.571428571,
          lineHeightHeading1: 1.285714286,
          lineHeightHeading2: 1.4,
          lineHeightHeading3: 1.5,
          lineHeightHeading4: 1.571428571,
          lineHeightHeading5: 1.538461538,
          lineWidth: 1,
          borderRadius: 3,
          borderRadiusLG: 4,
          borderRadiusSM: 2,
          controlHeight: 32,
          controlHeightLG: 38,
          controlHeightSM: 28,
          boxShadow: "none",
          boxShadowSecondary: "none",
        },
        components: {
          Layout: {
            bodyBg: "#01151D",
            headerBg: "#052F3F",
            headerColor: "#EDF4F7",
            siderBg: "#01151D",
            footerBg: "#01151D",
            triggerBg: "#052F3F",
            triggerColor: "#AFC4CD",
            lightSiderBg: "#01151D",
            lightTriggerBg: "#052F3F",
            lightTriggerColor: "#AFC4CD",
          },
          Button: {
            borderRadius: 3,
            controlHeight: 32,
            controlHeightLG: 38,
            controlHeightSM: 28,
            defaultBg: "#052F3F",
            defaultBorderColor: "rgba(126, 183, 208, 0.30)",
            defaultColor: "#EDF4F7",
            primaryShadow: "none",
            dangerShadow: "none",
            defaultHoverBg: "#1B87A8",
            defaultHoverColor: 'white',
          },
          Card: {
            colorBgContainer: "#052F3F",
            colorBorderSecondary: "rgba(126, 183, 208, 0.14)",
            borderRadiusLG: 4,
            headerFontSize: 16,
          },
          Table: {
            colorBgContainer: "#052F3F",
            headerBg: "#2187A8",
            headerColor: "#FFFFFF",
            headerSplitColor: "transparent",
            borderColor: "rgba(126, 183, 208, 0.14)",
            rowHoverBg: "rgba(63, 198, 255, 0.08)",
            rowSelectedBg: "rgba(63, 198, 255, 0.14)",
            rowSelectedHoverBg: "rgba(63, 198, 255, 0.18)",
            cellPaddingBlock: 8,
            cellPaddingInline: 12,
            cellFontSize: 13,
          },
          Input: {
            colorBgContainer: "#012530",
            colorBorder: "rgba(126, 183, 208, 0.30)",
            activeBorderColor: "#3FC6FF",
            hoverBorderColor: "#3FC6FF",
            activeShadow: "0 0 0 2px rgba(63, 198, 255, 0.25)",
          },
          InputNumber: {
            colorBgContainer: "#012530",
            colorBorder: "rgba(126, 183, 208, 0.30)",
            activeBorderColor: "#3FC6FF",
            hoverBorderColor: "#3FC6FF",
            activeShadow: "0 0 0 2px rgba(63, 198, 255, 0.25)",
          },
          Select: {
            colorBgContainer: "#012530",
            colorBgElevated: "#0A3D52",
            colorBorder: "rgba(126, 183, 208, 0.30)",
            activeBorderColor: "#3FC6FF",
            hoverBorderColor: "#3FC6FF",
            optionActiveBg: "rgba(63, 198, 255, 0.08)",
            optionSelectedBg: "#1B87A8",
            optionSelectedColor: "#FFFFFF",
          },
          DatePicker: {
            colorBgContainer: "#012530",
            colorBgElevated: "#0A3D52",
            colorBorder: "rgba(126, 183, 208, 0.30)",
            activeBorderColor: "#3FC6FF",
            hoverBorderColor: "#3FC6FF",
            activeShadow: "0 0 0 2px rgba(63, 198, 255, 0.25)",
          },
          Menu: {
            darkItemBg: "#01151D",
            darkSubMenuItemBg: "#01151D",
            darkItemColor: "#AFC4CD",
            darkItemHoverColor: "#EDF4F7",
            darkItemHoverBg: "rgba(63, 198, 255, 0.08)",
            darkItemSelectedColor: "#3FC6FF",
            darkItemSelectedBg: "rgba(63, 198, 255, 0.14)",
            itemBorderRadius: 2,
          },
          Tabs: {
            itemColor: "#AFC4CD",
            itemHoverColor: "#3FC6FF",
            itemSelectedColor: "#EDF4F7",
            inkBarColor: "#3FC6FF",
            cardBg: "rgba(1, 37, 48, 0.64)",
          },
          Modal: {
            contentBg: "#052F3F",
            headerBg: "#052F3F",
            footerBg: "#052F3F",
            titleColor: "#EDF4F7",
            borderRadiusLG: 4,
          },
          Drawer: {
            colorBgElevated: "#052F3F",
          },
          Tooltip: {
            colorBgSpotlight: "#0A3D52",
            colorTextLightSolid: "#EDF4F7",
            borderRadius: 2,
          },
          Popover: {
            colorBgElevated: "#0A3D52",
            borderRadiusLG: 4,
          },
          Dropdown: {
            colorBgElevated: "#0A3D52",
            controlItemBgHover: "rgba(63, 198, 255, 0.08)",
          },
          Pagination: {
            itemActiveBg: "#1B87A8",
            itemBg: "transparent",
          },
          Segmented: {
            trackBg: "#012530",
            itemSelectedBg: "#1B87A8",
            itemSelectedColor: "#FFFFFF",
            itemHoverBg: "rgba(63, 198, 255, 0.08)",
          },
          Tag: {
            defaultBg: "rgba(63, 198, 255, 0.10)",
            defaultColor: "#AFC4CD",
          },
          Tree: {
            nodeHoverBg: "rgba(63, 198, 255, 0.08)",
            nodeSelectedBg: "rgba(63, 198, 255, 0.14)",
            directoryNodeSelectedBg: "#1B87A8",
            directoryNodeSelectedColor: "#FFFFFF",
          },
          Checkbox: {
            colorPrimary: "#1B87A8",
            colorPrimaryHover: "#3FC6FF",
            colorBgContainer: "#012530",
            colorBorder: "rgba(126, 183, 208, 0.30)",
          },
          Radio: {
            colorPrimary: "#1B87A8",
            colorPrimaryHover: "#3FC6FF",
            colorBgContainer: "#012530",
            colorBorder: "rgba(126, 183, 208, 0.30)",
            buttonBg: "#012530",
            buttonCheckedBg: "#1B87A8",
            buttonColor: "#AFC4CD",
          },
          Descriptions: {
            titleColor: "#EDF4F7",
            labelBg: "#0A3D52",
            labelColor: "#AFC4CD",
            contentColor: "#EDF4F7",
            extraColor: "#3FC6FF",
          },
        },
      },
    },
  },
  /**
   * @name 网络请求配置
   * @description 它基于 axios 和 ahooks 的 useRequest 提供了一套统一的网络请求和错误处理方案。
   * @doc https://umijs.org/docs/max/request
   */
  request: {},
  /**
   * @name 权限插件
   * @description 基于 initialState 的权限插件，必须先打开 initialState
   * @doc https://umijs.org/docs/max/access
   */
  access: {},
  /**
   * @name <head> 中额外的 script
   * @description 配置 <head> 中额外的 script
   */
  headScripts: [
    // 解决首次加载时白屏的问题
    { src: join(PUBLIC_PATH, "scripts/loading.js"), async: true },
  ],

  //================ pro 插件配置 =================
  presets: ["umi-presets-pro"],
  /**
   * @name openAPI 插件的配置
   * @description 基于 openapi 的规范生成serve 和mock，能减少很多样板代码
   * @doc https://pro.ant.design/zh-cn/docs/openapi/
   */
  openAPI: [
    {
      requestLibPath: "import { request } from '@umijs/max'",
      // 或者使用在线的版本
      // schemaPath: "https://gw.alipayobjects.com/os/antfincdn/M%24jrzTTYJN/oneapi.json"
      schemaPath: join(__dirname, "oneapi.json"),
      mock: false,
    },
    {
      requestLibPath: "import { request } from '@umijs/max'",
      schemaPath:
        "https://gw.alipayobjects.com/os/antfincdn/CA1dOm%2631B/openapi.json",
      projectName: "swagger",
    },
  ],

  mock: {
    include: ["mock/**/*", "src/pages/**/_mock.ts"],
  },
  /**
   * @name 是否开启 mako
   * @description 使用 mako 极速研发
   * @doc https://umijs.org/docs/api/config#mako
   */
  mako: {},
  esbuildMinifyIIFE: true,
  requestRecord: {},
   // 当前使用 Nginx SPA 部署，不启用静态路由导出
//   exportStatic: {},
  tailwindcss: {},
});
