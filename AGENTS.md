# SeaTunnel Web 工作约定

## 基本规则

- 根目录 `AGENTS.md` 纳入 Git 版本管理，规则变化随代码提交。
- 先读相关代码和配置，遵循现有模式，做最小改动；保留用户已有改动，不提交密钥、`.env`、生产数据或构建产物。

## 固定版本

- SeaTunnel Web：`1.0.0`；SeaTunnel Engine：`2.3.13`。
- OpenMetadata Server、Java SDK：`1.12.10`；ingestion 与 managed APIs 使用已验证的 `1.12.10.x` 版本线。
- Spring Boot：`3.3.13`；部署 MySQL：`8.0.39`。
- Java 使用 `/opt/jdk-21.0.11+10`；Maven 只能使用仓库 `./mvnw`。
- 前端使用 Node `24.19.0`、npm `11.17.0`、Yarn Classic `1.22.22`（Node >=20）。
- JDBC 驱动与连接器参数以 SeaTunnel Engine `2.3.13` 为准。

## 启动与测试

- Agent 启停前后端用 `scripts/dev-{up,down,restart,status}.sh`。
- 后端读根目录 `.env` 连接测试库。
- SeaTunnel / MySQL / Open Metadata 等 Compose 在 `/mnt/lc`；未获授权不得 Compose / 部署 / 重启容器。
- 构建、测试、部署彼此独立；按变更范围做最小必要验证并如实报告。

## 代码探索与文档

- 仓库存在 `.codegraph` 时，优先使用 `codegraph explore "问题或符号"` 定位代码，再按需使用 `rg`。
- 涉及库、框架、SDK、API、CLI 或云服务时，先用 Context7 的 `resolve-library-id`，再用 `query-docs` 查询文档。

## 编写代码

- 修改或新增代码前，必须阅读根目录 [`CLAUDE.md`](CLAUDE.md)。
- 新增 Flyway migration 前先检查版本，禁止重复版本；已应用的 migration 不改名、不修改，不手工编辑历史表。
- OpenMetadata 操作必须使用官方 `1.12.10` Java SDK；未暴露的能力只能使用 SDK 自带网络客户端，禁止自建 HTTP 客户端、直连 Airflow 或使用 `1.13.x` Schema。
- 每个独立功能小步提交，使用 Conventional Commits；不使用破坏性 Git 操作。
