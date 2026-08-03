// https://umijs.org/config/

import { join } from "node:path";
import { defineConfig } from "@umijs/max";
import defaultSettings from "./defaultSettings";
import proxy from "./proxy";

import routes from "./routes";

const { REACT_APP_ENV = "dev" } = process.env;
const IS_PROTOTYPE =
  process.env.REACT_APP_PROTOTYPE === "1" ||
  process.env.UMI_APP_PROTOTYPE === "1";

/**
 * @name 使用公共路径
 * @description 部署时的路径，如果部署在非根目录下，需要配置这个变量
 * @doc https://umijs.org/docs/api/config#publicpath
 */
const PUBLIC_PATH: string = "/";

export default defineConfig({
  define: {
    "process.env.REACT_APP_PROTOTYPE": IS_PROTOTYPE ? "1" : "0",
    "process.env.UMI_APP_PROTOTYPE": IS_PROTOTYPE ? "1" : "0",
  },
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
  title: IS_PROTOTYPE ? "数据采集引接软件" : "Seatunnel Web",
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
          colorPrimary: "#2187A8",
          colorPrimaryHover: "#14627B",
          colorPrimaryActive: "#14627B",
          colorLink: "#4DD2FF",
          colorLinkHover: "#FFFFFF",
          colorInfo: "#4DD2FF",
          colorSuccess: "#38B24A",
          colorWarning: "#D9B719",
          colorError: "#C23B3B",
          colorBgBase: "#001922",
          colorBgLayout: "#001922",
          colorBgContainer: "#002E41",
          colorBgElevated: "#07394A",
          colorBgSpotlight: "#002E41",
          colorText: "#FFFFFF",
          colorTextSecondary: "#D5D5D5",
          colorTextTertiary: "#9AA8AE",
          colorTextQuaternary: "#666F75",
          colorTextDisabled: "#666F75",
          colorBorder: "#2187A8",
          colorBorderSecondary: "rgba(17, 125, 160, 0.32)",
          colorSplit: "rgba(17, 125, 160, 0.24)",
          colorFill: "rgba(33, 135, 168, 0.18)",
          colorFillSecondary: "rgba(33, 135, 168, 0.14)",
          colorFillTertiary: "rgba(17, 125, 160, 0.12)",
          colorFillQuaternary: "rgba(17, 125, 160, 0.08)",
          controlItemBgActive: "#2187A8",
          controlItemBgHover: "rgba(33, 135, 168, 0.24)",
          controlOutline: "rgba(77, 210, 255, 0.24)",
          fontFamily: '"Microsoft YaHei", "微软雅黑", Arial, sans-serif',
          fontSize: 14,
          fontSizeHeading1: 28,
          fontSizeHeading2: 24,
          fontSizeHeading3: 20,
          fontSizeHeading4: 18,
          fontSizeHeading5: 16,
          lineHeight: 1.357142857,
          lineHeightHeading1: 1.321428571,
          lineHeightHeading2: 1.291666667,
          lineHeightHeading3: 1.3,
          lineHeightHeading4: 1.333333333,
          lineHeightHeading5: 1.3125,
          lineWidth: 1,
          borderRadius: 4,
          borderRadiusLG: 4,
          borderRadiusSM: 2,
          controlHeight: 34,
          controlHeightLG: 38,
          controlHeightSM: 30,
          boxShadow: "none",
          boxShadowSecondary: "none",
        },
        components: {
          Layout: {
            bodyBg: "#001922",
            headerBg: "#002E41",
            headerColor: "#FFFFFF",
            siderBg: "#001922",
            footerBg: "#001922",
            triggerBg: "#002E41",
            triggerColor: "#D5D5D5",
            lightSiderBg: "#001922",
            lightTriggerBg: "#002E41",
            lightTriggerColor: "#D5D5D5",
          },
          Button: {
            borderRadius: 4,
            controlHeight: 34,
            controlHeightLG: 38,
            controlHeightSM: 30,
            defaultBg: "#002E41",
            defaultBorderColor: "#2187A8",
            defaultColor: "#FFFFFF",
            primaryShadow: "none",
            dangerShadow: "none",
            defaultHoverBg: "#2187a8",
            defaultHoverColor: 'white',
          },
          Card: {
            colorBgContainer: "#002E41",
            colorBorderSecondary: "rgba(33, 135, 168, 0.72)",
            borderRadiusLG: 4,
            headerFontSize: 20,
          },
          Table: {
            colorBgContainer: "#002E41",
            headerBg: "#0A526A",
            headerColor: "#FFFFFF",
            headerSplitColor: "rgba(77, 210, 255, 0.24)",
            borderColor: "rgba(33, 135, 168, 0.56)",
            rowHoverBg: "rgba(33, 135, 168, 0.3)",
            rowSelectedBg: "rgba(33, 135, 168, 0.4)",
            rowSelectedHoverBg: "rgba(33, 135, 168, 0.52)",
            cellPaddingBlock: 8,
            cellPaddingInline: 12,
            cellFontSize: 14,
          },
          Input: {
            colorBgContainer: "rgba(0, 25, 34, 0.72)",
            colorBorder: "#2187A8",
            activeBorderColor: "#4DD2FF",
            hoverBorderColor: "#4DD2FF",
            activeShadow: "0 0 0 2px rgba(77, 210, 255, 0.14)",
          },
          InputNumber: {
            colorBgContainer: "rgba(0, 25, 34, 0.72)",
            colorBorder: "#2187A8",
            activeBorderColor: "#4DD2FF",
            hoverBorderColor: "#4DD2FF",
            activeShadow: "0 0 0 2px rgba(77, 210, 255, 0.14)",
          },
          Select: {
            colorBgContainer: "rgba(0, 25, 34, 0.72)",
            colorBgElevated: "#002E41",
            colorBorder: "#2187A8",
            activeBorderColor: "#4DD2FF",
            hoverBorderColor: "#4DD2FF",
            optionActiveBg: "rgba(33, 135, 168, 0.3)",
            optionSelectedBg: "#2187A8",
            optionSelectedColor: "#FFFFFF",
          },
          DatePicker: {
            colorBgContainer: "rgba(0, 25, 34, 0.72)",
            colorBgElevated: "#002E41",
            colorBorder: "#2187A8",
            activeBorderColor: "#4DD2FF",
            hoverBorderColor: "#4DD2FF",
            activeShadow: "0 0 0 2px rgba(77, 210, 255, 0.14)",
          },
          Menu: {
            darkItemBg: "#001922",
            darkSubMenuItemBg: "#001922",
            darkItemColor: "#D5D5D5",
            darkItemHoverColor: "#FFFFFF",
            darkItemHoverBg: "rgba(33, 135, 168, 0.24)",
            darkItemSelectedColor: "#4DD2FF",
            darkItemSelectedBg: "rgba(33, 135, 168, 0.42)",
            itemBorderRadius: 2,
          },
          Tabs: {
            itemColor: "#D5D5D5",
            itemHoverColor: "#4DD2FF",
            itemSelectedColor: "#FFFFFF",
            inkBarColor: "#4DD2FF",
            cardBg: "rgba(0, 25, 34, 0.64)",
          },
          Modal: {
            contentBg: "#002E41",
            headerBg: "#002E41",
            footerBg: "#002E41",
            titleColor: "#FFFFFF",
            borderRadiusLG: 4,
          },
          Drawer: {
            colorBgElevated: "#002E41",
          },
          Tooltip: {
            colorBgSpotlight: "#002E41",
            colorTextLightSolid: "#FFFFFF",
            borderRadius: 2,
          },
          Popover: {
            colorBgElevated: "#002E41",
            borderRadiusLG: 4,
          },
          Dropdown: {
            colorBgElevated: "#002E41",
            controlItemBgHover: "rgba(33, 135, 168, 0.3)",
          },
          Pagination: {
            itemActiveBg: "#2187A8",
            itemBg: "transparent",
          },
          Segmented: {
            trackBg: "rgba(0, 25, 34, 0.72)",
            itemSelectedBg: "#2187A8",
            itemSelectedColor: "#FFFFFF",
            itemHoverBg: "rgba(33, 135, 168, 0.24)",
          },
          Tag: {
            defaultBg: "rgba(33, 135, 168, 0.18)",
            defaultColor: "#D5D5D5",
          },
          Tree: {
            nodeHoverBg: "rgba(33, 135, 168, 0.24)",
            nodeSelectedBg: "rgba(33, 135, 168, 0.42)",
            directoryNodeSelectedBg: "#2187A8",
            directoryNodeSelectedColor: "#FFFFFF",
          },
          Checkbox: {
            colorPrimary: "#2187A8",
            colorPrimaryHover: "#4DD2FF",
            colorBgContainer: "rgba(0, 25, 34, 0.72)",
            colorBorder: "#2187A8",
          },
          Radio: {
            colorPrimary: "#2187A8",
            colorPrimaryHover: "#4DD2FF",
            colorBgContainer: "rgba(0, 25, 34, 0.72)",
            colorBorder: "#2187A8",
            buttonBg: "rgba(0, 25, 34, 0.72)",
            buttonCheckedBg: "#2187A8",
            buttonColor: "#D5D5D5",
          },
          Descriptions: {
            titleColor: "#FFFFFF",
            labelBg: "#07394A",
            labelColor: "#D5D5D5",
            contentColor: "#FFFFFF",
            extraColor: "#4DD2FF",
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
  openAPI: IS_PROTOTYPE ? [] : [
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
