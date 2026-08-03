# Repository Guidelines

## 项目结构与模块组织

本项目是基于 React、TypeScript、Umi Max 和 Ant Design Pro 的 SeaTunnel Web 前端。业务页面位于 `src/pages/`，通用组件放在 `src/components/`，接口客户端与类型位于 `src/services/`，公共逻辑放在 `src/utils/`。图片、字体等资源归入 `src/assets/` 或 `public/`；多语言文案维护在 `src/locales/`。路由、代理和应用配置集中在 `config/`，本地模拟接口位于 `mock/`。不要手工修改生成目录 `src/.umi/`、`dist/` 或覆盖率产物。

## 构建、测试与本地开发

- `npm install`：安装依赖并执行 Umi 初始化；要求 Node.js 20 或更高版本。
- `npm run start:dev`：以开发环境启动应用，并关闭本地 Mock。
- `npm run build`：生成生产构建到 `dist/`。
- `npm run lint`：执行 Biome 检查和 TypeScript 类型检查。
- `npm test`：运行 Jest 测试；可用 `npm test -- DataSourceSelect.test.tsx` 聚焦单个文件。
- `npm run test:coverage`：生成覆盖率报告。

## 编码风格与命名

遵循 `.editorconfig` 和 `biome.json`：UTF-8、LF、两空格缩进、120 字符行宽；JavaScript/TypeScript 使用单引号、分号和尾随逗号，JSX 属性使用双引号。React 组件及其文件使用 `PascalCase`，函数、变量和工具文件使用 `camelCase`，页面目录沿用现有 `kebab-case`。提交前运行 `npx @biomejs/biome check --write src`，并避免复制 `src/services/` 中的生成代码。

## 测试规范

测试采用 Jest、jsdom 和 Testing Library，并通过 Umi 配置解析路径别名。测试应与实现相邻，命名为 `*.test.ts` 或 `*.test.tsx`；共享初始化仅放入 `tests/setupTests.jsx`。当前未设置硬性覆盖率阈值，但新增分支、数据转换及交互行为应有针对性测试，避免只验证快照。

## 提交与拉取请求

Commitlint 使用 Conventional Commits。历史中常见 `feat(ui): ...`、`fix(datasource): ...`；新提交应采用 `type(scope): 简短说明`，避免历史中的无类型消息。拉取请求需说明目的和验证命令，关联相关 Issue；视觉改动附明暗主题截图，接口或配置变更注明兼容性与部署影响。

## SeaTunnel 与配置注意事项

目标 SeaTunnel Engine 版本为 **2.3.13**。连接器名称、HOCON 参数和部署说明必须以该版本为准。不得提交真实凭据；环境差异通过 `.env` 与 `config/proxy.ts` 管理，并在 PR 中列出新增变量。

## 修改后的验证约束

修改代码后不要执行前端验证或启动命令，包括 `npm run build`、`npm run dev`、`npm run start` 及同类命令。仅在用户明确要求时运行这些命令。
