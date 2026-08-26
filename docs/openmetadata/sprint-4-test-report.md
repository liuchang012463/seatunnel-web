# Sprint 4 测试报告：扫描结果与数据源探查

## 范围

本 Sprint 在 OpenMetadata Server `1.12.10`、ingestion/managed build `1.12.10.0` 固定契约上完成扫描结果与探查门面。运行范围仍为 MySQL、PostgreSQL、Doris；Oracle、Dameng、Kingbase 明确 `DEFERRED`，没有用兼容 connector 冒充支持，也没有直接调用 Airflow。

## 后端定向测试

执行：

```bash
SPRINT_JAVA_HOME=/opt/jdk-21
export JAVA_HOME="$SPRINT_JAVA_HOME"
export PATH="$SPRINT_JAVA_HOME/bin:$PATH"
./mvnw -pl seatunnel-web-api -am -DskipTests=false \
  -Dtest=OpenMetadataRestClientTest,DataExplorationQualityEvaluatorTest,DataExplorationServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：**17 tests, 0 failures, 0 errors**。

覆盖：

- 1.12.10 Database/Schema/Table/Profile 路径和 `include=non-deleted`；
- Table 详情 `fields=columns,tableConstraints`；
- latest Table/Column Profile 字段映射；
- managed API 固定版本与无 Airflow URL/请求体约束；
- Database/Schema/Table Service 归属；
- 最新 Profile、轻量质量判定、Top20 复用；
- 缺 Profile/规则与跨 Service 拒绝。

## 前端测试

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
- Jest：**2 suites, 8 tests passed**；
- 所有请求均断言为 `/api/v1/data-exploration` 或既有 `/api/v1/data-source` SeaTunnel 后端路径，前端无 OM/Airflow 直连。

## 已知边界

- Table 读取使用 1.12.10 的受限 `limit`；全量 cursor 分页、清查聚合和拓扑缓存放在 Sprint 5。
- Profiler Pipeline 参数不可由产品 API 编辑；OM `tableProfilerConfig` 只在 REST client 固定版本回归中验证，不透传给前端。
- Profile 失败时本门面不会清空 OM 上一次成功 Profile；没有 Profile 时返回明确的空状态。
