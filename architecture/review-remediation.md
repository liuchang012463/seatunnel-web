# 2026-11-17 仓库审查整改

本次仅处理 Web 功能、桌面布局、性能、维护性及测试，不包含网络安全审查或移动端工作。

| 原发现 | 处理 |
| --- | --- |
| CODE-001 | 时间变量三个路径参数显式命名，新增经过 MVC 绑定链的 GET/PUT/DELETE 回归；隔离运行环境完成真实 CRUD。 |
| UI-001 | 桌面降级网格使用内容行高和拓扑最小高度，验证 1920、1440、1366、1024 宽度无面板覆盖。 |
| UX-001 | 请求代次保护列表、总数、选中项、错误与加载状态，卸载后丢弃旧响应；浏览器受控乱序回归。 |
| CODE-002 | 新增 H2 `B1_0_55` 累计基线，包含基础业务表、内置初始化数据和 Quartz 官方 H2 schema；只作用于空库，已有版本迁移文件和历史记录不改。验证自定义 Flyway 历史表、重复 migrate 和已有历史跳过基线。 |
| UX-002 | 默认/示例 Nginx 显式代理文档路径；开发代理与普通 API 使用同一目标。 |
| PERF-001 | 复用分页 JOIN 的调度投影，只在内存中归一化手动模式；回归断言没有逐行调度查询。 |
| PERF-002 | 默认/示例静态服务启用 gzip，发行版回归检查 JavaScript 的 Content-Encoding。未进行无依据的依赖升级或分包。 |
| A11Y-001 | 新建数据源入口在默认及悬停态使用深色文字，浏览器计算对比度达到 4.5:1。 |
| UX-003 | 最近执行方式缺失时显示占位符，不推断为自动；自动执行对应 SCHEDULED。 |
| ARC-001 | DAO 不再依赖全部数据源实现，Core 的 SPI 测试显式使用 test scope 实现聚合，应用装配仍由 API 提供。 |
| ARC-002 | 提取有界分页收集器、指标转换和纯指标卡展示，保留元数据编排、事务与 SDK 调用路径。 |
| CODE-003 | 删除整文件注释的旧列表及无效 import 注释。 |
| TEST-001 | 增加发行版 Nginx + Chromium 桌面回归和 CI 步骤，明确使用 fixture API，不称为真实引擎端到端测试。 |
| TEST-002 | 两次使用 detectOpenHandles 的 266 项测试均正常退出，未复现原警告；CI 保留诊断，不使用 forceExit 掩盖句柄问题。 |

## 验证

- Java 21、仓库 Maven wrapper：1,501 项选定本地测试通过，0 failures/errors。仍排除 SeaTunnelRestClientRemoteTest、EmailAlarmChannelTest 和 *IT。
- 前端 Node 24.19.0、Yarn 1.22.22：266 项 Jest、lint/TypeScript、发行版构建通过。
- `yarn test:browser`：4 项 Chromium 桌面回归通过，包括四个桌面宽度、文档 JSON/gzip、筛选乱序、执行标签和文字对比度。
- 使用 `scripts/dev-up.sh` 在隔离副本启动空 H2 库，应用和 Quartz 正常启动；内置时间变量可读取，临时变量真实 CRUD 通过，临时记录已删除。
- 不代表真实 SeaTunnel、OpenMetadata、Doris、外部 JDBC、告警、消息或文件导入链路已完成验收；没有部署或重启远端容器。
- 拉取 origin 并 rebase 后，重新执行前端 266 项测试、lint/类型检查、构建和 4 项桌面回归，均通过；相关后端回归再次通过。原有三个本地提交保留。

## 复跑桌面回归

安装现有锁定依赖后，执行 `yarn playwright install chromium`、`yarn build`、`yarn test:browser`。本机需有 Nginx；可用 `TEST_NGINX_BIN` 指定可执行文件。脚本从发行版默认配置生成临时路径/端口，自动启动和回收 fixture API 与静态服务，不连接业务数据库。

Jest 通过 `yarn test:ci` 运行并保留句柄诊断。浏览器结果目录由 UI `.gitignore` 排除，截图/构建产物不进入提交。
