# 公共组件样式

参考 `vite-vue3-template/src/styles` 的分层与交互状态处理，使用当前 React / Ant Design 5 技术栈实现，不引入 Element Plus、Vue 或 Sass。

- `../design-system.less`：现有 `--st-*` 语义令牌与暗亮主题，保留项目配色和布局；正文与辅助文字最小 14px。
- `antd-theme.ts`：通过 `ThemeConfigProvider` 映射官方组件令牌，优先在这里适配按钮、输入、选择、日期、表格及弹窗。仅直接绘制的组件令牌使用 CSS 变量；算法需要解析颜色的 Seed Token 继续使用实际色值。
- `antd-components.less`：处理存量全局强覆盖造成的校验、禁用和选中状态冲突，由 `global.tsx` 在设计系统之后引入。局部业务样式继续就近维护。

参考项目的 `.el-*` 选择器、`--el-*` 变量和 Vue 深层选择器不迁入本项目；使用 Ant Design 官方 Props 和 Token。浮层继承根令牌，避免依赖页面 DOM 层级、生成的 hash 类名或替换业务组件。

验收至少覆盖：按钮 hover/active/disabled、表单 focus/error/warning、选择器选中及禁用选项、表格筛选/空状态/分页/固定列、弹窗校验与关闭、抽屉打开与关闭；检查常用电脑窗口尺寸。主题切换只验证现有能力，不扩大浅色主题功能范围。
