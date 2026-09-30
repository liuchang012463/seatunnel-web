# OpenMetadata 1.12.10 Search API 聚合能力调研

调研日期：2026-09-30
调研动机：数据探查聚合接口（`/api/v1/data-inventory/*`）最初因"全目录遍历 OM（分页拉库/Schema/表 + 逐表 profile 扩展调用）+ 前端 15s 超时"频繁报"探查统计暂不可用"。Redis 缓存方案（`feat/redis-metadata-cache`，已合入 develop）解决了用户侧延迟；本文记录"用 Search API 从根源减少遍历"的可行性调研，供后续优化决策使用。

## 取证来源

```text
运行中的 managed OM 1.12.10：http://127.0.0.1:8585/api（令牌来自根目录 .env 的 METADATA_OPENMETADATA_TOKEN）
官方 SDK：~/.m2/repository/org/open-metadata/openmetadata-sdk/1.12.10/openmetadata-sdk-1.12.10.jar
Redis（现有缓存）：127.0.0.1:26379，键前缀 stweb:*（见 MetadataInventoryCache/OmReadCache）
对照基线：本地 seatunnel-web 后端 /api/v1/data-inventory/*（walk + Redis 缓存路径）
```

复现命令见文末附录。所有结论均为当日实测，不要从其他版本文档推导。

## 实验矩阵与结论

### 1. `table_search_index` 文档不含 profile 数据（关键负结论）

`GET /v1/search/query?q=&index=table_search_index&size=1` 返回的 `_source` 字段集合：
`classificationTags, columnNames, columns, customPropertiesTyped, database, databaseSchema,
deleted, description, displayName, domains, entityStatus, fullyQualifiedName, fqnHash, name,
owners, service, serviceType, tableConstraints, tableType, tags, usageSummary, ...`

**没有任何 rowCount / sizeInByte / profile / tableProfile 字段。** `columns` 子文档仅含
name/dataType/constraint/description/tags 等结构信息。

同时用实体 API 双向验证过：对一张**确定有 profile 数据**的表（扩展接口返回 rowCount=1947）：

| 请求 | 结果 |
|---|---|
| `GET /tables/name/{fqn}?fields=profile` | `profile: None` |
| `?fields=tableProfile` / `profile,tableProfile` | 均 `None` |
| 列表 `GET /tables?databaseSchema=...&fields=columns,profile` | 全部 `None` |
| `GET /tables/{fqn}/tableProfile/latest?includeColumnProfile=true` | **有数据** |

结论：**1.12.10 中 profiler 数据只存在于 `/tables/{fqn}/tableProfile/latest` 扩展，逐表调用无法通过实体 API 或搜索索引消除**（当初"OM 没有这方面能力"的调研结论正确）。

### 2. 专用聚合端点 `/v1/search/aggregate` 在 managed 1.12.10 上损坏

- 空 `q`（`q=`）：HTTP 500，`[es/search] failed: [x_content_parse_exception] [terms] failed to parse field [size]`；
- `q=*` 或省略 q：能执行，但**所有字段的 terms buckets 均为空**（serviceType/tableType/service.name/deleted 等全部空），`hits.total` 正常。

SDK 侧 `SearchAPI.aggregate(index, field, q)`（拼参 `field=` 而非 `fieldName=`）因此不可用。若未来升级 OM 后需要按字段分面统计，可重新验证该端点。

### 3. 计数查询路径可用且与现有聚合完全对齐（关键正结论）

```text
GET /v1/search/query?q=&index={index}&size=0&track_total_hits=true
    &query_filter={"query":{"bool":{"filter":[{"term":{"service.name":"<serviceFqn>"}},{"term":{"deleted":false}}]}}}
```

- 可用索引：`table_search_index`、`database_search_index`、`database_schema_search_index`
  （注意是 `database_schema_...` 不是 `databaseSchema_...`；索引枚举未在 SDK 中暴露）。
- `service.name` 为 keyword 映射，term 精确匹配可用；**必须加 `deleted=false`**（索引含软删文档）。
- **必须 `track_total_hits=true`**：超过 1 万条时默认 total 会被截断到 10000。
- 逐源对齐验证：`dataSourceId=22964041928224` 的 walk 聚合（库 1 / Schema 7 / 表 344）与
  三个索引的 search 计数**逐字段一致**。
- 延迟：单次计数 ~26ms；66 次（22 服务 × 3 索引）共 1.7s。若用 `terms` 过滤一次列出全部
  托管服务名，3 个查询即可得到全量计数（~80ms）。
- 陷阱：OM 实例上存在非 web 管理的服务（实测 22 个 `st_ds_*` 服务 vs web 绑定表 12 个源），
  **服务清单必须取自本地绑定表**（`MetadataBindingDao.queryAll()`，即 `DataInventoryService.loadSources`
  的同一来源），不能用 OM 的 databaseServices 全量列表，否则计数口径不一致。

### 4. SDK 原生支持（合规）

```java
// org.openmetadata.sdk.client.OpenMetadataClient#search() -> services.search.SearchAPI
sdk().search().query("")
    .index("table_search_index")
    .size(0)
    .trackTotalHits()
    .queryFilter("{\"query\":{\"bool\":{\"filter\":[...]}}}")
    .execute();   // 返回原始 JSON 字符串，hits.total.value 即精确计数
```

`SearchBuilder` 还支持 `postFilter` / `includeAggregations` / `sortBy` 等。完全走官方 SDK 网络客户端，
符合"仅使用官方 1.12.10 Java SDK / SDK 自带网络客户端"的项目约束。

## 可替代性矩阵（聚合快照 11 项指标）

| 指标 | 现来源 | search 可替代？ |
|---|---|---|
| dataSourceCount / unitCount / businessSystemCount / 类型分布 | 本地 DB | 不涉及 |
| databaseCount / schemaCount / tableCount | 遍历 75+ 个分页接口 | **✅ 可**（3 个计数查询/筛选键，已验证对齐） |
| columnCount | 表分页 columns 求和 | ❌ 无 metric 聚合，仍需读表文档 |
| profiledTableCount / profiledDatabaseCount / knownRowCount / knownSizeInByte | 838 次逐表扩展调用（Redis profile 缓存摊薄） | ❌ 索引无 profile 数据，**这是遍历成本大头（约 85%）** |

## 后续集成设计（未实施，记录备用）

混合模式，在 `DataInventoryService.buildSnapshot` 中分支化：

1. `OpenMetadataRestClient` 新增 `countEntities(index, List<String> serviceFqns)`：SDK
   `SearchBuilder` + `terms` 过滤（服务名清单来自本地绑定表）+ `deleted=false` + `trackTotalHits`，
   一次查询返回一个层级的总数；三个层级共 3 次调用（按 `dataSourceId` 筛选时按需改为逐服务）。
2. 计数部分直接采用 search 结果；columnCount 与 profile 指标继续走现有 Redis 缓存的表分页 +
   逐表 profile。
3. 注意点：search 索引为异步更新，存在秒级滞后（对 5 分钟 TTL 的聚合无感）；未来多实例部署时
   预热任务需加分布式锁（现有 Redis 缓存已具备跨进程共享能力）。

**触发条件**：目录规模涨到万级表、或重建中"页遍历 + JSON 反序列化"成为可观测瓶颈（当前
重建 4–7s 中页部分约占一半）时落地。预期收益：后台重建少 ~75 次 OM 调用与十几 MB columns
载荷，首次构建时间约减半；用户侧毫秒级响应维持不变。

## 附录：复现命令

```bash
TOKEN=$(grep '^METADATA_OPENMETADATA_TOKEN=' .env | cut -d= -f2-)
BASE=http://127.0.0.1:8585/api

# 文档结构（确认无 profile 字段）
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/search/query?q=&index=table_search_index&size=1"

# 聚合端点损坏复现（500 / 空 buckets）
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/search/aggregate?index=table_search_index&field=serviceType&q="
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/search/aggregate?index=table_search_index&field=serviceType&q=*"

# 计数查询（与 walk 对齐）
QF='{"query":{"bool":{"filter":[{"term":{"service.name":"st_ds_22964041928224"}},{"term":{"deleted":false}}]}}}'
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/search/query?q=&index=table_search_index&size=0&track_total_hits=true&query_filter=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$QF")"

# 对照：walk 聚合（本地后端）
curl -s "http://127.0.0.1:9527/api/v1/data-inventory/summary?dataSourceId=22964041928224"
```
