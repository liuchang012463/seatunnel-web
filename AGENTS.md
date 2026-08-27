# SeaTunnel Web 项目约定

## 固定版本

- SeaTunnel Web：`1.0.0`；SeaTunnel Engine：`2.3.13`。
- Java：Eclipse Temurin `21.0.11+10`，路径 `/opt/jdk-21.0.11+10`。
- Maven：只能使用仓库 `./mvnw`（Maven Wrapper `3.9.16`），不要使用主机默认 Maven。
- 前端构建：Node `24.19.0`、npm `11.17.0`、Yarn Classic `1.22.22`（Node `>=20`）。
- Spring Boot：`3.3.13`；部署 MySQL：`8.0.39`。
- OpenMetadata Server、Java SDK：固定 `1.12.10`。
- OpenMetadata ingestion/managed APIs：固定 `1.12.10.x`，当前镜像为
  `openmetadata/ingestion:1.12.10-kingbase`，包版本 `1.12.10.0`。
- JDBC 驱动：Dameng `DmJdbcDriver18.jar`、Kingbase `kingbase8-8.6.1.jar`、Oracle
  `ojdbc8-19.3.0.0.jar`。

## 本地构建与部署

在仓库根目录执行，前端必须先于 Maven：

```bash
cd seatunnel-web-ui
yarn install --frozen-lockfile
yarn run tsc
yarn run build
cd ..

JAVA_HOME=/opt/jdk-21.0.11+10 \
PATH=/opt/jdk-21.0.11+10/bin:$PATH \
./mvnw clean package -DskipTests
```

产物为 `seatunnel-web-dist/target/seatunnel-web-1.0.0.tar.gz`。部署到
`/mnt/lc/seatunnel-web-docker-new` 时，先删除目标 `dist/seatunnel-web-1.0.0`，再解压
同一个 tar 包并修正为可读目录权限，避免旧前端文件残留：

```bash
DEPLOY=/mnt/lc/seatunnel-web-docker-new
rm -rf "$DEPLOY/dist/seatunnel-web-1.0.0"
mkdir -p "$DEPLOY/dist"
tar -xzf seatunnel-web-dist/target/seatunnel-web-1.0.0.tar.gz -C "$DEPLOY/dist"
chmod -R a+rX "$DEPLOY/dist/seatunnel-web-1.0.0"
cd "$DEPLOY"
docker-compose --env-file .env -f docker-compose.yml up -d --force-recreate
docker-compose ps
```

部署 `.env` 只保存本机密钥和运行参数，不能提交 Git；metadata 至少需要配置
`METADATA_OPENMETADATA_ENABLED`、`METADATA_OPENMETADATA_BASE_URL` 和
`METADATA_OPENMETADATA_TOKEN`。当前 OM 地址必须是 `/api` 入口；Kingbase 元数据探查可额外
配置 `METADATA_OPENMETADATA_KINGBASE_TUNNEL_HOST/PORT`，仅替换 OpenMetadata 元数据连接端点，
不改变 SeaTunnel 数据源或任务配置。

## VS Code

- `.vscode/launch.json` 使用主类
  `org.apache.seatunnel.web.api.SeaTunnelWebApplication`、项目
  `seatunnel-web-api`，并从 `${workspaceFolder}/.env` 读取变量；Flyway repair 默认关闭。
- `.vscode/settings.json` 使用 `/opt/jdk-21.0.11+10`、JavaSE-21、`./mvnw` 和
  `/home/haruka/.m2/settings.xml`，终端自动注入同一 JDK，Maven 视图为 hierarchical。

## OpenMetadata 与数据源

- 所有 OM 操作必须经过 OpenMetadata `1.12.10` 官方 Java SDK；SDK 未暴露的
  `1.12.10` 管道控制能力只能使用 SDK 自带网络客户端，禁止自建 HTTP 客户端、直连
  Airflow 或引用 `1.13.x` 路径/Schema。
- 探查为用户触发的一次性 metadata/profile 操作，不配置定时调度；Server 和 managed
  ingestion 版本不允许自行升级或切换。
- Kingbase 远端通过 SSH 隧道提供给容器；隧道断开时先恢复隧道，再重试数据源探查。
  本机隧道示例：`ssh -fNT -o ExitOnForwardFailure=yes -L 0.0.0.0:25432:127.0.0.1:54321 root@192.168.100.91`；
  部署 `.env` 使用 Docker 宿主机网关地址和 `25432`，重启前确认端口仍在监听。

## 数据库、测试与提交

- 新增 Flyway migration 前先检查版本；已应用 migration 不改名、不修改、不手工改历史表。
- 变更后按范围运行测试；每个独立功能小步提交，提交信息清晰，便于审查和回滚。
- 保留用户已有改动和 `tmp/` 参考目录；禁止强制删除分支、强推或提交 `.env`、密钥、
  密码和本地构建产物。
