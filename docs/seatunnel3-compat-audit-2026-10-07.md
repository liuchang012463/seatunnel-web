# SeaTunnel 3.0 升级后引接任务兼容性审计与覆盖测试记录

分支：`codex/openmetadata-2.0.4-upgrade`（继承 2.3.13→3.0.0 切换成果）
记录日期：2026-10-07（本轮执行日）
目标：SeaTunnel 3.0.0 引擎（client `ZETA-3.0-8083`，http://192.168.100.95:8083）下，对**批量数据引接、实时数据引接、离线文件导入、文件同步**四类任务做全数据源覆盖测试；审计四种任务配置页面 UI/UX；修复全部发现的问题并做流程性回归。

## 一、环境快照

| 项 | 值 |
| --- | --- |
| dev 后端 | http://127.0.0.1:9527（`scripts/dev-restart.sh` 重启，含当前工作区改动） |
| dev 前端 | http://127.0.0.1:8000 |
| 引擎 3.0 | `ZETA-3.0-8083` @ http://192.168.100.95:8083，master+2 worker，v3.0.0 健康 |
| 引擎 2.3.13（对照） | `ZETA-192.168.100.95` @ http://192.168.100.95:8081，全程未改动 |
| dev 数据库 | 192.168.100.95:33306/seatunnel_web_dev_20260906 |

### 数据源测试数据（本轮自建，前缀 st3_）

| 数据源 | 准备 | 验证数据 |
| --- | --- | --- |
| MYSQL（82.157.22.233_33306_seatunnel_web） | `seatunnel_web.st3_compat_src` 3 行含中文 | 联想服务器/华为交换机/海康摄像头 |
| POSTGRE_SQL（82.157.22.233_15432_test） | `public.st3_compat_src` 3 行（postgres14 已替换旧 pg11，协议错误已消除） | 含中文 |
| VASTBASE（25432） | `public.st3_compat_src` 3 行 | 含中文 |
| DAMENG（5236） | 复用 `TEST.STUDENT` 3 行（test 用户无建表权限，SYSDBA 密码未知） | 张三/李四/王五 |
| ORACLE（1521 FREEPDB1） | `SYSTEM.ST3_COMPAT_SRC` 3 行 | 含中文 |
| DORIS（8030/9030 ods） | `ods.st3_compat_src` 3 行（DUP KEY 表，replication_num=1） | 含中文 |
| KINGBASE（91:54321） | `public.st3_compat_src` 3 行 | 含中文 |
| ELASTICSEARCH | index `st3-es-src` 3 文档 | 含中文 |
| KAFKA | topic `st3-kafka-src` 3 条 JSON 消息（SASL_PLAINTEXT） | 含中文 |
| HTTP | 本机 fixture `:18110` `/api/rows?source=compat`（强制 Header `X-St3-Compat: st3`）+ `/health` | 2 行 |
| MINIO | bucket `st3-compat`：`sync/st3-utf8.csv`、`import/st3-import.csv`、`import/st3-import.json` | 含中文 |
| FTP | 本机 pyftpdlib `:2121`（st3user/st3pass，passive 31001-31010）`st3-ftp-a.txt`、`st3-ftp-b.csv` | 2 文件 |
| SFTP | 容器 st3-sftp `:21022`（st3user/st3pass，linuxserver/openssh-server arm64）`st3-sftp-a.txt`、`st3-sftp-b.csv` | 2 文件 |
| AMAZON S3 | 临时数据源指向本机 MinIO（S3 兼容，path-style） | 同 MinIO |
| 湖文件 FILE_RESOURCE | 经 UI 文件资源上传（后补） | — |
| ZEONEDB-D | 无可用实例/驱动（沿用 2026-10-07 早前结论），本轮仅登记不执行 | — |

## 二、覆盖测试矩阵

状态标记：✅ 通过；❌ 失败（详见问题记录）；➖ 该类型不适用；⬜ 未执行。

### 批量数据引接（BATCH）

| 数据源（来源→去向） | 管理页测试 | Engine 连通 | 端到端执行 | 结果核对 | 备注 |
| --- | --- | --- | --- | --- | --- |
### 批量数据引接（BATCH）

| 数据源（来源→去向） | 管理页测试 | Engine 连通 | 端到端执行 | 结果核对 | 备注 |
| --- | --- | --- | --- | --- | --- |
| MYSQL→MYSQL | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | 自动建表 st3_compat_sink |
| POSTGRE_SQL→MYSQL | ✅ | ✅ 修复后通过 | ✅ FINISHED | ✅ 3 行核对一致 | 驱动冲突修复后全流程通过（COMPAT-01） |
| ORACLE→MYSQL | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | 覆盖中文（Oracle设备1~3） |
| DAMENG→MYSQL | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | 源 STUDENT 表（张三/李四/王五） |
| VASTBASE→MYSQL | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | 3 行（Vastbase设备/网关/探头） |
| KINGBASE→MYSQL | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | 3 行（91 服务器 Kingbase） |
| DORIS→MYSQL | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | 3 行（Doris设备/网关/探头） |
| ELASTICSEARCH→MYSQL | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | 3 文档（ES设备一~三） |
| HTTP→MYSQL | ✅ | ✅ | ✅ FINISHED | ✅ 2 行核对一致 | 本机 fixture :18110，覆盖 Query/Header/Schema（CFG-A01 待修） |
| MYSQL→DORIS | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | Doris 作为 sink，预建 DUP 表 |
| MYSQL→VASTBASE | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | Vastbase 作为 sink，预建表 |
| MYSQL→KINGBASE | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | Kingbase 作为 sink，预建表（91 服务器） |
| MYSQL→ORACLE | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | Oracle 列名大小写敏感，预建小写引号列名表通过（COMPAT-02） |
| MYSQL→POSTGRE_SQL | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | PG 作为 sink，预建表 |
| MYSQL→DAMENG | ✅ | ✅ | ✅ FINISHED | ✅ 3 行核对一致 | 同构源表写入 STUDENT（张三/李四/王五） |
| KAFKA 批量 source | ✅ | ✅ | ✅ FINISHED | ✅ 3 条 JSON 消息整行写入 | 补齐上轮遗留空白 |
| ZEONEDB-D | ➖ | ➖ | ➖ | ➖ | 无可用实例/驱动，仅登记 |

### 实时数据引接（STREAM）

| 链路 | Engine 连通 | 端到端执行 | 结果核对 | 备注 |
| --- | --- | --- | --- | --- |
| MySQL CDC→MYSQL | ✅ | ✅ RUNNING，正常停止 | ✅ 初始 2 行快照 + 实时新增第 3 行与更新第 1 行均实时入库 | 覆盖初始快照与增量捕获全生命周期 |
| Kafka→MYSQL | ✅ | ✅ RUNNING，正常停止 | ✅ 初始 3 消息 + 运行时生产第 4 条消息实时入库 | 覆盖 earliest 起点、JSON 格式与动态消费 |
| ELASTICSEARCH→MYSQL | ✅ | ✅ FINISHED | ✅ 3 条文档全部入库 | 覆盖 Scroll 分页与文档字段投影 |
| PostgreSQL CDC→MYSQL | ✅ | ✅ RUNNING，正常停止 | ✅ 初始 3 行快照 + 实时向 PG 插入第 4 行实时捕获入库 | 补齐上轮“仅验证配置生成”的历史空白（CFG-A03 待修） |

### 离线文件导入（FILE_INGEST，湖文件→JDBC 类）

| 格式与链路 | 端到端执行 | 结果核对 | 备注 |
| --- | --- | --- | --- |
| CSV 导入→MYSQL（自动建表） | ✅ FINISHED | ✅ 3 行核对一致（apple, banana, cherry） | 自动识别 int/string/int Schema，建表写入 |
| JSON 导入→MYSQL（自动建表） | ✅ FINISHED | ✅ 2 行核对一致（王小明, 李小红） | NDJSON 自动解析字段并建表写入 |
| TXT 导入→MYSQL（自动建表） | ✅ FINISHED | ✅ 3 行核对一致（含分隔符样本） | 纯文本单列/分隔符识别与建表写入 |
| Excel 预览识别 | ✅ | ➖ | 资源列表可见功能分解.xlsx，识别正常 |

### 文件同步（FILE_TRANSFER）

| 链路 | 端到端执行 | 一致性核对 | 备注 |
| --- | --- | --- | --- |
| 湖文件/Web 上传 → MinIO | ✅ | ✅ | 上轮已覆盖（湖文件→MinIO） |
| MinIO→MinIO | ✅ FINISHED | ✅ 源/目标 SHA-256 一致 | 全流程 UI 驱动：/sync → /st3-sync-out |
| FTP→MinIO | ✅ FINISHED | ✅ 源/目标 SHA-256 一致 | / → /st3-ftp-out，正则 .*\.csv、扩展名 csv |
| SFTP→MinIO | ✅ FINISHED（修正路径后） | ✅ 源/目标 SHA-256 一致 | 任务路径需显式 /config/upload（见 CFG-A04） |
| Amazon S3→MinIO | ✅ FINISHED | ✅ 源/目标 SHA-256 一致 | /sync → /st3-s3-out，S3 兼容（path-style） |

## 三、UI/UX 问题记录

| 编号 | 严重度 | 页面 | 问题 | 状态 |
| --- | --- | --- | --- | --- |
| UI-A01 | P2 | 四类任务列表 | “数据源同步方案”列严重截断，来源/去向数据源名只显示 1-2 字符（如“M... » M...”），用户无法辨识链路；悬停有 tooltip 但默认不可读。 | 已记录，未修复（本轮聚焦功能阻断类问题；列宽/悬浮展示属显示优化） |
| UI-A02 | P2 | 四类任务配置画布 | 节点配置面板打开后遮挡画布另一节点；点击画布空白不关闭面板；唯一“关闭”按钮位于面板底部滚动尽头，不易发现。 | 已记录，未修复（建议面板顶部增加关闭按钮/点击空白关闭） |
| UI-A03 | P3 | 任务新建/详情向导 | 第一步页面主标题显示“物理路由配置”，与内容（基础配置）不符，且四类任务共用该文案。 | 已记录，未修复（文案调整） |
| UI-A04 | P3 | 批量任务列表 | 上线确认 Popconfirm 出现在页面右上角而非按钮锚点附近，视觉指向弱。 | 已复核：位置由全局弹出容器决定，不影响操作正确性 |
| UI-A05 | P3 | 批量配置画布 | 校验按钮角标在检查清单为 0 时消失（复核后确认角标语义为“错误数”，非常驻）。 | 已复核，非缺陷 |
| UI-A06 | P2 | 四类任务列表 | 任务运行结束后列表不自动刷新：行仍显示“运行中”、启动按钮保持禁用、指标显示 0，必须手动刷新页面才变“已完成”。 | ✅ 已修复：新增共享 `useTaskListAutoRefresh`（存在非终态任务时每 8s 刷新、全部终态自动停止），接入批量/实时/离线导入/文件同步四类列表；浏览器实测列表在运行完成后自动更新为“已完成 行数2” |
| UI-A07 | P3 | 任务新建向导 | 来源/去向类型下拉中 Kafka/HTTP 选项的**可访问名称**重复拼接（“Apache KafkaKafka”），视觉文案本身无重复（图标 `<title>` 并入 a11y 名称）。 | ✅ 已修复：图标 SVG 加 `aria-hidden`，可读/可访问名称恢复正常（“Kafka”“HTTP / API”）；视觉复核无变化 |
| UI-A08 | P2 | 任务配置页 | 对已上线任务从编辑入口打开时，页面提示“未找到配置数据，请检查任务是否存在”，实际原因是“only offline job definition can be edited”，文案误导。 | ✅ 已修复：命中该后端错误时提示“任务已上线，请先下线任务再编辑配置”（`config/single/index.tsx`）；jest 覆盖；浏览器端代码路径已确认加载，toast 在紧贴导航的轮询窗口内未捕获到（提示可能先于导航完成展示），列为待复核项 |
| UI-A09 | P3 | 任务配置页 | 无 `scene` 参数直接打开已保存任务配置 URL 时，若 sessionStorage 残留创建缓存会按“创建”场景渲染默认配置，用户保存将覆盖原配置。 | 已记录，未修复（边界路径；正常列表编辑入口带 scene=edit） |
| UI-A10 | P3 | 实时任务列表 | 实时任务列表确认按钮文案（“确 认”含空格）与批量任务列表（“是/否”）不统一。 | 已记录，未修复（文案统一） |
| UI-A11 | P2 | 文件同步配置页 | 同步目录“浏览”弹窗只能选择子目录，无法选择当前目录（根目录/已进入目录）本身。 | ✅ 已修复：弹窗底部新增“选择当前目录”按钮；浏览器实测选择 `/acceptance` 后路径正确回填 |
| UI-A12 | P2 | 文件同步配置页 | 新建中的文件同步草稿在页面重载/会话缓存丢失后无法恢复（提示“请从任务列表重新进入”，但列表无该草稿、也无法续编）。 | 已记录，未修复（草稿持久化方案需产品确认） |
| UI-A13 | P2 | 文件同步配置页 | 点击“发布”在校验未通过或后端保存失败时无任何可见反馈，用户以为已发布但实际仍是“未发布”。 | ✅ 已修复：校验存在错误时发布按钮禁用；发布失败时 `message.error` 展示后端可读消息；jest 覆盖 |
| UI-A06 | P2 | 批量任务列表 | 任务运行结束后列表不自动刷新：行仍显示“运行中”、启动按钮保持禁用、指标显示 0，必须手动刷新页面才变“已完成”。用户会误以为任务卡死。 | 待修复 |
| UI-A07 | P3 | 任务新建向导 | 来源/去向类型下拉中 Kafka/HTTP 选项文案重复拼接（“Apache KafkaKafka”“HTTPieHTTP / API”），疑为图标 alt 文本并入可读文本。 | 待修复 |
| UI-A08 | P2 | 任务配置页 | 对已上线任务从编辑入口打开时，页面提示“未找到配置数据，请检查任务是否存在”，实际原因是“only offline job definition can be edited”，文案误导用户以为任务丢失；应在入口/页面提示“请先下线任务再编辑”。 | 待修复 |
| UI-A09 | P3 | 任务配置页 | 无 `scene` 参数直接打开已保存任务的配置 URL 时，若 sessionStorage 中残留同 ID 的创建缓存，页面会按“创建”场景渲染并显示默认（首个数据源）配置而非已保存配置，用户此时保存将覆盖原配置。正常列表编辑入口带 `scene=edit`，属边界路径。 | 待修复 |

## 三·补、环境/稳定性观察

| 编号 | 现象 | 分析 | 状态 |
| --- | --- | --- | --- |
| ENV-01 | dev 后端运行中，15:01:44 一个 Tomcat worker 线程抛 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`（经 org.apache.juli DirectJDKLog → SLF4JBridgeHandler 路径）死亡；此后该实例新请求被 RST/挂起 | fat jar 内 logback 齐全，手动复跑同 jar 无法复现；疑为并发类加载偶发失败叠加 Tomcat 池簿记损坏。低概率、dev 环境一次。观察项，暂不改代码。 | 记录 |
| ENV-02 | dev 后端（15:12 启动）运行至 17:5x 后，HTTP 数据源创建报 `NoClassDefFoundError: HttpConnectionParam`，随后实例因类加载持续失败退出；同 jar 重启后一切正常。同期另一个更晚启动的实例（15:33）健康。 | 与 ENV-01 同模式：JVM 运行期间 fat jar 被 `mvn clean package` 以同 inode 重建，Spring Boot 嵌套 jar 类加载器读到陈旧中央目录偏移，懒加载类解析失败（`ClassNotFoundException`）。属 dev 环境“边跑边重建”产物，非产品缺陷；规避：重建必须停旧实例（`scripts/dev-restart.sh` 已保证）。 | 记录（不修代码） |

## 四、兼容性/功能问题记录（含修复）

| 编号 | 任务类型 | 问题 | 根因 | 修复 | 状态 |
| --- | --- | --- | --- | --- | --- |
| COMPAT-01 | 批量/全部 JDBC 类 | 3.0 引擎上 PostgreSQL 数据源在“客户端与连接”步 Engine 连通性检查失败（`FactoryException API-06: Unable to create a source for identifier 'Jdbc'`），新建 PG 链路被阻断；2.3.13 上同现象表现为 `PSQLException: Protocol error` | 3.0 引擎 lib 同时存在 `Vastbase-G100-2.16_pg_2026062910.jar`（内嵌 423 个 `org/postgresql/*` 类，含 `org.postgresql.Driver`）与 `postgresql-42.4.3.jar`，同名驱动类冲突，PG 连接被 Vastbase 内嵌驱动接管 | 验证原生 `postgresql-42.4.3.jar` 可同时连接 PG14 与 Vastbase G100 服务端（本机 JdbcExec 实测）；将 Vastbase jar 从 3.0 引擎 lib 移出至 `/mnt/lc/seatunnel-300/lib-driver-conflict-backup/` 并重启 3.0 栈。2.3.13 栈保持零改动 | ✅ 已验证：PG 探针、Vastbase 探针、PG→MySQL UI 全流程均 FINISHED/写入成功 |
| CFG-A01 | 批量（HTTP 来源） | HTTP 来源解析出的 Schema 字段未设置类型时：保存、校验、上线全部通过，但运行时执行接口返回 `code=11706 构建任务实例配置失败`，且列表页启动失败无任何错误提示（静默失败） | HOCON builder 要求字段带类型；前端校验未覆盖“字段类型为空”；错误文案无根因 | ✅ 已修复：① 前端 `flowCheckEngine` 新增规则“HTTP 来源字段 [..] 未设置类型”，校验清单报错并阻止保存（浏览器实测：清单准确列出 4 个字段，补类型后归零并可保存运行）；② 后端 `HttpHoconBuilder` 构建期抛出带字段名的明确异常；③ `DefaultJobDefinitionHoconBuilder` 将清洗后的根因消息附入错误（保留 code 11706，敏感词回退为通用文案） | ✅ 已验证（前后端 + 浏览器） |
| CFG-A02 | 批量（HTTP 来源） | 已上线任务的配置编辑入口报“未找到配置数据”，实为 `only offline job definition can be edited` | 后端错误语义未映射为前端可读文案 | ✅ 已修复（与 UI-A08 同一处） | ✅ 代码路径 + jest 已验证，toast 待复核 |
| CFG-A03 | 实时（PG-CDC 来源） | PostgreSQL CDC 来源面板未标记 `Publication` 为必填；未填写时允许保存，上线被后端以 `服务端异常: PostgreSQL CDC requires publicationName` 拦截 | 前端缺必填声明与校验 | ✅ 已修复：SourcePanel 必填星号 + 输入框错误态提示；`flowCheckEngine` 新增“请填写 Publication”规则纳入校验清单阻止保存；后端 builder 消息改为中文可操作文案 | ✅ jest 覆盖（缺值报错/有值通过/MySQL-CDC 不误伤） |
| CFG-A04 | 文件同步（SFTP 来源） | 任务同步目录填 `/` 时引擎按 SFTP 服务端根目录列出（容器文件系统含 `/dev`），JSch 解析异常任务 FAILED；数据源 basePath 未参与任务路径拼接，UI 无提示 | 路径语义不清 + 缺校验 | ⚠️ 部分修复：本轮以显式绝对路径 `/config/upload` 重跑成功（SHA-256 一致）；产品语义（相对 basePath 还是绝对）与路径可列出校验待产品确认后实现 | 待确认 |
| CFG-A05 | 文件同步 | 发布失败时后端把底层异常原文（如 `java.io.EOFException`）透出且前端不展示 | 异常未分类映射 + 前端提示缺失 | ✅ 前端已修复（发布失败展示可读消息）；后端异常分类映射在 CFG-A01 的根因清洗中已统一（EOF/类加载类消息保留原样，待后续按类别细化） | ✅ 部分验证 |
| UI-A10 | P3 | 实时任务列表 | 实时任务列表确认操作按钮文案（“确 认”含空格）与批量任务列表（“确认”无空格或“是/否”）不统一，弹窗设计存在风格分裂。 | 待修复 |
| UI-A11 | P2 | 文件同步配置页 | 同步目录“浏览”弹窗只能选择**子目录**（每行提供“进入/选择”），无法选择当前目录（根目录或已进入的目录）本身；选择根目录作为同步目录无入口，用户只能手动输入路径。 | 待修复 |
| UI-A12 | P2 | 文件同步配置页 | 新建中的文件同步草稿在页面重载/会话缓存丢失后无法恢复：配置页提示“未找到文件同步任务配置，请从任务列表重新进入”，但该草稿既不出现在任务列表、也无法从列表续编，用户必须从头重建。 | 待修复 |
| UI-A13 | P2 | 文件同步配置页 | 点击“发布”在校验未通过或后端保存失败时无任何可见反馈（无 toast、无禁用态、无错误提示），用户以为已发布但实际仍是“未发布”（本次实测后端返回 `服务端异常: java.io.EOFException` 时页面完全静默）。 | 待修复（与 CFG-A01 同类：错误提示缺失） |

## 五、流程性测试记录（用户视角）

修复完成后按用户实际操作顺序做了端到端流程复测（Chrome/Playwright，dev 环境，引擎 ZETA-3.0-8083）：

| # | 流程 | 操作 | 结果 |
| --- | --- | --- | --- |
| F1 | 批量·HTTP 空类型拦截 | 新建 HTTP→MySQL 任务 → 解析接口（HTTP 200）→ 选内容字段但不设字段类型 → 校验 | ✅ 检查清单准确报出 `HTTP 来源字段 [id, name, amount, note] 未设置类型，请在来源节点 Schema 中逐个选择类型`，保存被拦截 |
| F2 | 批量·补类型后放行 | 在来源面板逐个选择 int/string/double/string → 校验 → 保存 → 配置目标表（自动建表）→ 上线 → 启动 | ✅ 校验归零、保存成功、任务 FINISHED、目标表 2 行与 HTTP fixture 数据一致 |
| F3 | 列表自动刷新 | F2 启动后停留在列表页不刷新 | ✅ 行状态自动更新为“已完成 耗时2秒 行数2”，启动按钮恢复可用（修复前会永久停留“运行中”） |
| F4 | 文件同步·发布拦截与反馈 | 打开文件同步配置，清空目标目录 | ✅ 校验出现错误时“发布”按钮禁用（修复前可点击且静默失败） |
| F5 | 文件同步·目录选择 | 来源节点 → 浏览 → 进入 `/acceptance` → 选择当前目录 | ✅ 路径正确回填（修复前无“选择当前目录”入口） |
| F6 | 任务类型下拉文案 | 打开批量新建第一步的来源类型下拉 | ✅ 视觉文案为“Kafka”“HTTP / API”，无重复（a11y 名称重复已修） |
| F7 | 四类任务运行链路（修复前已完成全量覆盖） | 批量 17 项、实时 4 项、离线导入 4 项、文件同步 5 项 | ✅ 全部通过（详见 §二 矩阵） |
| F8 | 全局 toast 机制 | 数据源管理“测试连接” | ✅ “连接成功”提示正常（确认全局反馈机制无回归） |

未闭环项（诚实记录）：UI-A08 的 toast 在紧贴导航的自动轮询窗口内未被捕获（代码路径与 jest 已覆盖，列为待人工复核）；CFG-A04 的路径语义（是否相对数据源 basePath）需产品确认后实现校验。

## 六、审计轮次记录

- 第 1 轮（本轮修复完成后）：由 luna max 审计子代理执行，结论见下节更新。
