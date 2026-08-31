# 引接分系统双模入湖 — Luna Max 开发任务 v1.0

> 唯一设计基线：`引接分系统_双模入湖_Codex实现设计_v1.4.md`  
> 前端基线：`引接分系统_双模入湖_前端页面设计_v1.0.md`  
> 执行方式：按依赖小步提交；每个任务完成后运行指定测试；不得回用旧分支湖设计。

---

## 0. 总门禁

- 从干净 `develop@943a17a0` 功能分支开发；
- 不修改 V1.0.15–V1.0.20；
- 不提交 `.env`、密码、Token、Catalog 完整 DDL、构建产物；
- Java 固定 `/opt/jdk-21.0.11+10`，Maven 只用 `./mvnw`；
- 前端固定 Yarn Classic，先 tsc/build 再 Maven package；
- 每个独立任务一到数个小提交，提交信息可审计/回滚；
- 旧湖表和旧分支代码不作为实现模板。

---

## Task 0 — Phase 0 Spike 与基线测试

交付：

- 记录当前 Doris version/backends/catalog/driver inventory；
- 把已验证的 Key STRING failure、VARCHAR Key、retention ALTER、SHOW CREATE、table_properties 固化为 fixture/集成测试；
- 增加测试专用唯一 DB/Catalog 命名与 finally cleanup；
- 验证 MySQL 已有 V1.0.15–V1.0.20 的升级路径。

门禁：不修改业务行为；Spike 记录不含秘密。

---

## Task 1 — V1.0.21 控制面 Migration

交付：

- MySQL V1.0.21 八张表；
- H2 测试等价 migration（若现有 DAO test runtime 需要）；
- Entity/Enum/Mapper/Repository；
- lock_version/generation/operation_token；
- Repository 测试和唯一约束竞态测试。

验收：在带旧 migration 的 MySQL 上升级成功，旧表未修改。

---

## Task 2 — LakeProperties 与 Doris 安全基础

交付：

- LakeProperties；
- DorisIdentifier、DorisSqlLiteral、CatalogPropertyWhitelist/Redactor；
- Lake DataSource 解析和连接复用；
- secret-safe logging 测试。

验收：恶意 identifier/property、quote、反斜线、换行和 password 均有测试。

---

## Task 3 — Resource Operation Coordinator

交付：

- durable intent/finalize/fail；
- token compare；
- stale retry takeover；
- operation query API；
- 故障注入测试：外部成功、本地 finalize 失败；旧 token 晚到。

验收：不得在未提交本地事务内调用 Doris DDL。

---

## Task 4 — DorisLakeClient / Contract / DDL / Reader

交付：

- Database/Table/Catalog metadata API；
- TargetContract v2 和 canonical hash；
- Validator（Key 禁 STRING）；
- DDL Builder；
- ContractReader，归一化 TEXT↔STRING、DATETIME、默认属性；
- 真实 4.1.2 Golden fixtures。

验收：Duplicate/Unique/Auto Range/Auto Bucket/retention 全覆盖。

---

## Task 5 — SourceObjectRef 与 ODS Database Backend

交付：

- OM UUID ownership lookup；
- Source baseline/hash；
- 主数据 code 校验和命名器；
- DB create/retry/reconcile/delete；
- DataSource 删除保护；
- UNKNOWN vs MISSING 错误分类。

验收：缺 BusinessSystem/非法 legacy unit code 有稳定错误和测试。

---

## Task 6 — MANAGED Table Backend

交付：

- Preview token；
- preview/create/retry/detail/delete impact；
- Contract/Source baseline/field mappings；
- 三段式 CREATE/DROP；
- Target conflict/adoption policy；
- API/DTO tests。

验收：Create 不执行客户端 DDL；Preview 后 OM 变化会阻止创建。

---

## Task 7 — Job Bridge Model 与 Target Database Override

交付：

- structured command 增加 `odsDatabaseBindingId`；
- 后端解析并覆盖 Doris node database；
- MANAGED mappings 预填；
- Lake job detector；
- TABLE/NAMESPACE relation；
- save/update/delete relation hooks。

验收：Single/Multi/Whole/Incremental/Streaming DTO round-trip 不丢 Binding。

---

## Task 8 — Job Bridge 强制安全

交付：

- save/online/execute 三层校验；
- MANAGED 强制 ERROR_WHEN_SCHEMA_NOT_EXIST；
- ODS 禁 RECREATE；
- source owner 校验；
- Script 禁 Lake DataSource；
- 旧任务在线/执行时也受保护；
- 删除表检查 ONLINE/调度/ACTIVE Relation。

验收：所有绕过路径测试；AUTO_CREATED 删除后不会被调度静默重建。

---

## Task 9 — AUTO_CREATED / UNMANAGED / Reconcile Backend

交付：

- 精确单表 AUTO_CREATED；
- MULTI/WHOLE NAMESPACE Relation；
- discovered UNMANAGED difference；
- explicit bind/unbind OM source；
- Source/Target/Task Drift；
- 多 relation 聚合；
- 显式 POST Reconcile。

验收：GET 详情不写库；OM timeout 与 404 区分。

---

## Task 10 — Lifecycle Backend

交付：

- Policy/Binding CRUD；
- prepartitioned validate；
- create-time retention；
- apply/update/verify；
- decrease impact preview；
- actual partition summary；
- 不支持 Active→永久。

验收：未分区表不可 Apply；Binding 为唯一 retention desired source。

---

## Task 11 — Logical Catalog Backend

交付：

- Adapter Registry；
- MySQL/PG/Oracle Adapter；
- Driver URL/class/checksum Registry；
- capability reason；
- desired spec/hash/credential revision；
- Catalog create/update/refresh/validate/reconcile/delete；
- scope ALL/DATABASE/TABLE；
- secret redaction。

验收：MySQL 真实环境通过；PG/Oracle Driver 缺失时 disabled，不虚报成功。

---

## Task 12 — 结构化只读查询 Backend

交付：

- 单表和 Join request DTO；
- 服务端 SQL generator；
- readonly DataSource；
- timeout/cancel/max rows/max bytes；
- audit/redaction；
- unsupported/sensitive fields 处理。

验收：API 不接受 raw SQL；写语句没有输入面。

---

## Task 13 — Recommendation Backend

交付：

- Physical/Logical capability resolver；
- 四问决策树；
- 原因和 disabled reason；
- 全分支测试。

---

## Task 14 — 前端共享层与路由

交付：

- 三个 lake 路由替换 prototype placeholder；
- types/service/status；
- shared status/error/reconcile/operation/impact components；
- DataSource Card 的推荐/物理/逻辑入口。

验收：状态映射无散落 magic string。

---

## Task 15 — 物理入湖前端

交付：

- 列表、详情、操作记录；
- 建库 Modal；
- 四步 MANAGED 向导；
- 表详情和 Desired/Actual Diff；
- DataSource → existing job 页面跳转与预填；
- UNMANAGED bind/unbind；
- 删除 impact。

验收：Loading/Empty/Error/Partial/Permission 状态；320/768/1440 检查。

---

## Task 16 — Lifecycle 前端

交付：策略列表/编辑、Apply Drawer、表生命周期详情、retention decrease impact confirmation。

验收：未预分区表 disabled reason；文案只说历史分区数。

---

## Task 17 — Logical 前端

交付：列表、Capability、创建向导、详情、结构化单表/跨 Catalog Join、Driver/credential 运维提示、删除确认。

验收：没有 raw SQL 编辑器；结果截断/超时/取消状态完整。

---

## Task 18 — 合同联调与回归

交付：

- F6-01/F6-02/F15-01 三条真实 Demo；
- 现有批量/流式/数据源/OpenMetadata 回归；
- 前端 tsc/build；
- Maven scoped tests 和最终 package；
- 配置/部署/API/未完成项文档；
- 不含 secret 的验收证据。

最终构建：

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

---

## 提交顺序建议

```text
Task 0
  ↓
Task 1 → Task 2 → Task 3 → Task 4
  ↓                    ↓
Task 5 → Task 6 → Task 7 → Task 8 → Task 9
                  ↓                 ↓
                Task 10          Task 11 → Task 12
                  └──────┬──────────┘
                       Task 13
                          ↓
                 Task 14 → 15/16/17
                          ↓
                       Task 18
```

