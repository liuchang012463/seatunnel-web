# SeaTunnel Web 项目约束

* 修改或新增代码前，必须阅读根目录 [`CLAUDE.md`]。
* 需主动补充必要的注释和关键日志，确保代码逻辑清晰、执行过程可追踪，便于后续维护和问题排查。

## 贡献与发布

* 从最新 `develop` 建功能分支，按功能小步提交，使用中文 Conventional Commits。PR 默认提到 `develop`，写明改动和验证结果，前后端 CI 通过后合入。
* 合入 `main` 还需至少 1 名非作者审批。禁止直接推送或强推 `main` 和 `develop`。
* 仅发布维护者可创建或更新发布标签（如 `release_*`）或触发发布。

## 版本与范围

* SeaTunnel Web：`1.0.0`；SeaTunnel Engine：`3.0.0`。
* OpenMetadata Server / Java SDK：`2.0.4`；ingestion / managed APIs 仅使用已验证的 `2.0.4.x` 版本线。
* Spring Boot：`3.3.13`；部署 MySQL：`8.0.39`。
* Java 使用 `/opt/jdk-21.0.11+10`；Maven 使用仓库内 `./mvnw`。
* 前端使用 Node `24.19.0`、npm `11.17.0`、Yarn Classic `1.22.22`。
* 本项目仅涉及 Web 端；不要实现、适配或验收任何移动端功能或移动端 UI。
* JDBC 驱动与连接器参数以 SeaTunnel Engine `3.0.0` 为准。

## 本地环境

* Agent 启停前后端统一使用 `scripts/dev-{up,down,restart,status}.sh`。
* 后端从根目录 `.env` 读取测试库配置。
* SeaTunnel、MySQL、OpenMetadata 等 Compose 环境位于192.168.100.95服务器(ssh haruka@192.168.100.95访问)的 `/mnt/lc`；未经明确授权，不得执行 Compose、部署或重启容器。
* 构建、测试和部署彼此独立，不得因构建或测试隐式触发部署。

## 实现约束

* 保留用户已有改动；不得提交密钥、`.env`、生产数据或构建产物。
* 新增 Flyway migration 前必须检查现有版本，禁止重复版本；已应用的 migration 不改名、不修改，不手工编辑历史表。
* OpenMetadata 操作必须使用官方 `2.0.4` Java SDK；SDK 未直接暴露的能力只能使用 SDK 自带网络客户端。禁止自建 HTTP 客户端、直连 Airflow 或使用其他版本 Schema。
* OpenMetadata ingestion 扩展维护在 `../workspace/OpenMetadata/ingestion/extensions` 的版本匹配分支；新增数据库在该处实现 source/connection、`sourcePythonClass`、驱动路径和依赖，运行 `package-bundle.sh` 验证。本仓库不保存 OM 扩展源码或打包脚本。
* 不得使用破坏性 Git 操作。

## 验收

* 涉及用户可操作 Web 行为的新增或修改，在宣称完成前必须使用 Chrome dev/Playwright 实际完成受影响的 happy path 验收。
