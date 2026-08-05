# 离线、文件、实时引接任务日志模块 v1

## 1. 适用范围

离线任务和文件任务都归入 `BATCH` 实例，实时任务归入 `STREAMING` 实例。三类任务共用日志查询、解析和回放语义；文件任务不另建一套日志协议。

## 2. 完整日志

Web 节点实例日志文件是任务日志的持久化文档。写入采用同步追加和按文件互斥锁，避免异步有界队列满载时丢行。任务结束时，再从 SeaTunnel Engine 拉取 `job-{engineJobId}.log`，以带 SHA-256 的快照段追加到同一文档；同一快照重复回调不会重复写入。

查询完整日志时：

- 运行中任务返回 Web 本地日志加当前 Engine 快照；
- 已结束任务返回已经合并的完整文档，并再次做内容哈希去重；
- 本地日志读取不再按 50 MB 截断。大日志的分页展示使用搜索接口，不改变原始文档。

## 3. 搜索接口

`GET /api/v1/job-log/{jobMode}/{instanceId}/search`

支持 `keyword`、`level`、`source`、`category`、`page`、`pageSize`。`pageSize` 上限为 500，关键字不区分大小写，返回物理行号、来源和原始行，便于从命中记录定位原文。

## 4. 结构化解析规则 v1

解析器保留每一条物理日志行，并补充 `sequence`、`lineNumber`、`timestamp`、`level`、`source`、`category`、`eventType` 和相对首个时间戳的 `elapsedMs`。

来源规则：

- 普通实例日志标记为 `WEB`；
- `=== SEA TUNNEL ENGINE LOG SNAPSHOT ... ===` 与结束标记之间标记为 `ENGINE`；
- 无法识别时间戳的续行继承上一条级别，但不丢失原文。

分类优先级：

1. `ERROR`：级别为 ERROR，或包含 exception、failed、failure、error、错误等故障词；
2. `DATA_SNAPSHOT`：包含 rows、records、bytes、throughput、snapshot、metric、pipeline、table 等数据读取/吞吐快照词；
3. `OPERATION`：包含 submit、config、monitor、watcher、pause、stop、checkpoint、savepoint、created 等操作词；
4. `EXECUTION_FLOW`：包含 scheduled、pending、running、finished、canceled、status 等状态流转词；
5. `TIMELINE`：其他保留日志，作为时序上下文。

同一行只归入一个分类；规则是确定性的，后续可在不改变原始字段的前提下增加规则版本。

## 5. 分析和回放

- `GET /api/v1/job-log/{jobMode}/{instanceId}/analysis` 返回操作行为、数据读取快照、执行流程、错误记录和操作时序五组记录；每组记录都带回原始行号。
- `GET /api/v1/job-log/{jobMode}/{instanceId}/replay` 返回按 `sequence` 排序的步骤，以及来源、分类、状态、相对耗时和原始详情。
- 前端回放按步骤播放，支持播放/暂停、上一步、下一步和进度拖动。回放不重新执行任务，只重构已记录事实，因此不会产生副作用。

## 6. 后续扩展边界

解析规则只负责从记录中提取事实；故障原因判断由独立的 Spring AI 诊断服务完成。AI 输入必须经过敏感信息脱敏，并同时携带错误记录、结构化流程和数据快照，输出采集端、传输链路、数据源、系统组件四类故障及证据。
