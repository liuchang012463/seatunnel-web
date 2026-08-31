# Sprint 6 测试报告：验收加固

## 版本与运行边界

- OpenMetadata Server：`1.12.10`，未升级或切换版本。
- `openmetadata-ingestion` / `openmetadata-managed-apis`：已验证 `1.12.10.0`，固定在 `1.12.10.x` 版本线。
- SeaTunnel Web 的 REST client 只访问 OM `/api`；没有直连 Airflow `:8082`。
- MySQL、PostgreSQL、Doris（OM 测试源 `doris_test`）为当前通过范围；Oracle、Dameng、Kingbase 与后续一等 Connector 一起实现，保持 `DEFERRED`。

## 后端全量 metadata 回归

执行：

```bash
SPRINT_JAVA_HOME=/opt/jdk-21
export JAVA_HOME="$SPRINT_JAVA_HOME"
export PATH="$SPRINT_JAVA_HOME/bin:$PATH"
./mvnw -pl seatunnel-web-api -am -DskipTests=false -DskipITs \
  -Dtest='org.apache.seatunnel.web.api.metadata.**' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

同时单独执行过包含健康、cursor、探查、清查、拓扑和导出的定向集合；结果记录在最终交付命令输出中，失败项必须在进入提交前修复。

本次固定类名回归结果：`Tests run: 43, Failures: 0, Errors: 0, Skipped: 0`，构建 `BUILD SUCCESS`。

覆盖重点：

- OM 1.12.10 版本/managed build 健康和精确 endpoint；
- Service/Pipeline desired-state、retry、delete、UNKNOWN 状态；
- cursor `after`、`paging.total`、`include=non-deleted`；
- MySQL/PostgreSQL/Doris adapter 选择与稳定 FQN；延期库不触发 OM；
- 数据源探查归属、Profile 保留、质量薄判定、Top20 facade；
- 清查缓存/聚合、拓扑懒加载、SXSSF 四 Sheet 导出；
- 禁止把 datasource DTO/连接密码整体写入日志。

## 前端回归

```bash
cd seatunnel-web-ui
npm run tsc
npm test -- --runInBand \
  src/pages/data-source/service.test.ts \
  src/pages/data-source/components/MetadataStatus.test.tsx
```

验证 DataSource 既有管理页面仍是唯一入口；清查看板、拓扑树和扫描结果抽屉均通过 SeaTunnel API，不在浏览器访问 OM/Airflow。

本次结果：TypeScript 检查通过；2 个 Jest suite、9 个测试全部通过。

## 发布前静态检查

- `bash -n tools/openmetadata/smoke-test.sh tools/openmetadata/verify-version.sh` 通过。
- `git diff --check` 通过。
- DataSource 列表异常日志仅保留分页、类型和归属 ID 白名单字段，不再整体记录 DTO。

## 真实环境 Gate 说明

Sprint 0 已在 `/mnt/lc/open_metadata` 对 MySQL、PostgreSQL、Doris 完成 Metadata/Profiler/Profile/lifecycle smoke。Doris 使用已配置的 `doris_test` 源。Oracle、Dameng、Kingbase 尚未满足一等 Connector 的 Metadata + Profiler 全链路，因此不把 Sprint 6 的三库回归外推为六库发布通过。
