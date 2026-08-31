# Sprint 5 测试报告：数据清查、拓扑与导出

## 固定版本与范围

- OpenMetadata Server：`1.12.10`。
- `openmetadata-ingestion` / `openmetadata-managed-apis`：已验证并固定 `1.12.10.0`（`1.12.10.x` 版本线）。
- 运行调用只指向 OM `/api`；没有 SeaTunnel Web → Airflow 直连。
- MySQL、PostgreSQL、Doris 为当前元数据闭环范围，Doris 使用已配置 OM 测试源 `doris_test`；Oracle、Dameng、Kingbase 为 `DEFERRED`。

## 后端定向回归

执行：

```bash
SPRINT_JAVA_HOME=/opt/jdk-21
export JAVA_HOME="$SPRINT_JAVA_HOME"
export PATH="$SPRINT_JAVA_HOME/bin:$PATH"
./mvnw -pl seatunnel-web-api -am -DskipTests=false -DskipITs \
  -Dtest=DataExplorationServiceTest,DataInventoryServiceTest,DataSourceTopologyServiceTest,\
DataExplorationExportServiceTest,OpenMetadataRestClientTest,MetadataPipelineOperationServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：上述定向测试在分页改造后重新编译通过；单独回归结果为 **22 tests, 0 failures, 0 errors**（此前清查/拓扑/导出/REST cursor 组合为 14 tests 全部通过，探查 facade 4 tests 通过，Pipeline operation 4 tests 通过）。

覆盖：

- Database/Schema/Table cursor `after` 与 `paging.total`；
- 1.12.10 `include=non-deleted` 读取边界；
- 清查计数、Profile 覆盖率、已探查行数和短缓存；
- 延期 Oracle 不访问 OM；
- 初始拓扑不加载 OM，Database/Schema/Table 展开时才加载；
- SXSSF 四 Sheet、固定列头和可打开的 XLSX；
- 既有 scan/exploration 操作仍经过 OM client。

## 前端回归

执行：

```bash
cd seatunnel-web-ui
npm run tsc
npm test -- --runInBand \
  src/pages/data-source/service.test.ts \
  src/pages/data-source/components/MetadataStatus.test.tsx
```

结果：

- TypeScript：通过；
- Jest：**2 suites, 9 tests passed**；
- 请求断言均为 SeaTunnel Web `/api/v1/data-exploration`、`/api/v1/data-inventory`、`/api/v1/data-source-topology` 或既有 `/api/v1/data-source`，浏览器不拿 OM token。

## 设计约束核对

- 清查只保存聚合快照，不新增 Database/Schema/Table/Column 明细表。
- 拓扑按层懒加载，Column 在 Table 详情中展示，不把全量 Table 一次性塞入初始树。
- 导出使用 POI SXSSF 有界行窗口，响应为流式 XLSX；POI 的 commons-io 运行时冲突已用直接依赖固定。
- Scan/Profile 状态刷新成功后失效清查缓存。
- `knownRowCount` 只展示有最近成功 Profile 的表，并在 UI 标注“已探查数据量”。
- Oracle、Dameng、Kingbase 没有伪造 adapter、Profile 或清查数据；待后续一等 Connector Gate。

## 未在本 Sprint 宣称的能力

- 六种数据库全量交叉验收仍受延期库 Connector Gate 约束；
- 不新增 Airflow managed API client；
- 不允许从 1.12.10 切换到 1.13.x；
- 大规模部署的 Redis/快照表、复杂质量规则、血缘和 View 仍不在 MVP。
