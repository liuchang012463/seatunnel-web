# RabbitMQ 通用消息推送 / 拉取接口 · 实现文档

> 版本：v1.1（定稿）　|　日期：2026-09-21
> 状态：**方案已定稿，待开工**
> 来源：基于 grill-me 逐分支确认的设计结论
> 关联文档：[`code-analysis-2026-09-21.md`](code-analysis-2026-09-21.md) · [`rabbitmq-messaging-api-usage.md`](rabbitmq-messaging-api-usage.md)

**本文档是实现依据。** 所有决策均已与需求方逐条确认，实现方不得自行更改；如需变更，须先回到本文档修订。

---

## 1. 需求

为外部系统提供两个通用接口，用于与 RabbitMQ 之间收发消息：

1. **推送**：把一段 JSON 消息写进 RabbitMQ
2. **拉取**：从 RabbitMQ 取出一批 JSON 消息

约束：调用方在请求中同时传入**目标 RabbitMQ 的连接配置**；消息为 **JSON 格式但内部结构不固定**。

---

## 2. 设计结论汇总

| # | 决策项 | 结论 | 影响 |
| --- | --- | --- | --- |
| A | 调用方 | **外部系统**（推 + 拉双向） | 定位为对外开放通道，需准入控制 |
| A3 | 配置传递 | 调用方传**完整**连接配置（含用户名密码） | 接口签名含连接信息；不做 host 白名单 |
| A4 | 防护 | 仅做**可达性校验**，不做 SSRF 防护 | 已知风险，见 §9 |
| F1 | 鉴权 | **API Key，可配；未配置则不校验** | 见 §7.3 |
| B1 | 批量 | `maxMessages` 调用方指定，服务端**硬截断**（默认 100） | 防内存耗尽 |
| B2 | ack | 只做两个接口，**AUTO 模式** | **接受 at-most-once，可能丢消息** |
| B3 | 取数 | `basicGet` 循环 + deadline | 请求响应式，非常驻消费者 |
| C1 | 连接 | **每请求新建、请求内释放** | 抽 `MessageConnectionResources` 接口 |
| C2 | 错误信息 | **脱敏**返回，完整信息只进服务端日志 | 不外泄内网拓扑 |
| D1 | 入向格式 | **对象写法**（调用方直接嵌 JSON） | 服务端解析，需防护 |
| D2 | 大小上限 | **1 MB** | 超限拒绝 |
| D3 | 队列归属 | 仅本通道 push 写入 | pull 侧仍保留解析失败降级 |
| D4 | 发送目标 | `exchange` 可空 → 直发 `queue`；填了走交换机 | 对调用方最友好 |
| E | confirm | **不等** publisher confirm | 与 B2 的可靠性预期一致 |
| F2 | 模块 | 新建 `seatunnel-web-messaging-plugins` | 与 datasource/alarm 平级 |
| F3 | 数据源体系 | **不纳入**，不修改 `DbType` 枚举 | 独立工具，非作业组件 |
| F4 | 客户端版本 | `com.rabbitmq:amqp-client` **5.36.0** | 见 §4.2 |

**一句话定位**：一个对外开放的、尽力而为的消息中继 —— 调用方自带 MQ 配置，平台代其推 / 拉 JSON，**不保证消息不丢**，以 API Key 作为最低限度准入。

---

## 3. 模块结构

```
seatunnel-web-messaging-plugins/                 ← 新建聚合模块（packaging=pom）
├── seatunnel-web-messaging-api/                 ← 契约层，仅依赖 seatunnel-web-spi
│   └── org.apache.seatunnel.plugin.messaging.api
│       ├── MessageClient.java                   推送 / 拉取契约
│       ├── MessageClientFactory.java            SPI 工厂契约
│       ├── MessageConnectionResources.java      连接资源生命周期契约
│       ├── MessageConnectionParam.java          连接配置（带 @FormField）
│       ├── MessagePushCommand.java              推送入参（SPI 侧）
│       ├── MessagePushResult.java
│       ├── MessagePullCommand.java
│       ├── MessagePullResult.java
│       ├── MessagePullItem.java
│       └── MessageException.java
├── seatunnel-web-messaging-rabbitmq/            ← 实现层，依赖 messaging-api + amqp-client
│   └── org.apache.seatunnel.plugin.messaging.rabbitmq
│       ├── RabbitMessageClientFactory.java      @AutoService(MessageClientFactory.class)
│       ├── RabbitMessageClient.java             basicPublish / basicGet
│       └── RabbitConnectionResources.java       每请求新建连接
└── seatunnel-web-messaging-all/                 ← 运行时聚合（packaging=jar，无源码）
    └── 依赖 messaging-rabbitmq，保证实现进入运行时 classpath
```

**为什么需要 `messaging-all`（实现阶段补充）**

对照 `seatunnel-web-alarm-all` 的既有惯例。`seatunnel-web-api` 若直接依赖 `messaging-rabbitmq`，会把 RabbitMQ 实现硬编码进接口层；改为依赖 `-all` 聚合器后，新增消息中间件实现只需改 `-all` 的依赖列表，接口层不动。ServiceLoader 也依赖这一点 —— SPI 实现必须在运行时 classpath 上才能被发现。

`seatunnel-web-api` 只**新增**文件，不改动既有类：

```
org.apache.seatunnel.web.api.message
├── MessageProperties.java             配置绑定（seatunnel.message.*）
└── plugin/MessagePluginManager.java   照搬 AlarmPluginManager（约 70 行）

org.apache.seatunnel.web.api.controller.message
├── MessagePushRequest.java            接口层请求体（含 connection）
└── MessagePullRequest.java

org.apache.seatunnel.web.api.controller
└── MessageController.java             两个接口 + 内部 API Key 校验
```

**为什么不放进现有模块**（依据 [`code-analysis-2026-09-21.md`](code-analysis-2026-09-21.md) §2）：

- `amqp-client` 是全新第三方依赖，不应污染 `seatunnel-web-datasource-plugins` 的父模块
- 项目既有惯例：插件实现层不依赖 `core` / `api` / `dao`（对照 `seatunnel-web-datasource-kafka`，它只依赖 `-api` + `kafka-clients`）
- 与 `datasource-plugins`（12 种数据源）、`alarm-plugins`（email/webhook）保持结构对称

---

## 4. 依赖与构建

### 4.1 依赖声明位置（修正）

`kafka-clients` 的版本**不在** `seatunnel-web-bom` 里，而在根 `pom.xml` 的属性 + `dependencyManagement` 中。`amqp-client` **按同样方式办理**，不放进 BOM：

```xml
<!-- 根 pom.xml <properties> -->
<amqp-client.version>5.36.0</amqp-client.version>

<!-- 根 pom.xml <dependencyManagement> -->
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>amqp-client</artifactId>
    <version>${amqp-client.version}</version>
</dependency>
```

### 4.2 版本选择依据

- 最新稳定版为 **5.36.0**（2026-09-15 发布）
- 项目同类依赖均取当时最新稳定版（`kafka-clients` 3.4.0、`aws-java-sdk-s3` 1.12.692、`lombok` 1.18.42）
- **与服务端版本无关**：`amqp-client` 实现的是已冻结的 AMQP 0-9-1 协议，5.36.0 可连 RabbitMQ 3.x ~ 4.x 全系。版本升级成本极低
- **唯一需向对接方确认的服务端配置**：`max_message_size`（默认 128MB，远高于本接口 1MB 上限；但若被管理员调小到 512KB 以下则消息会被拒）

> **已在真实 RabbitMQ 4.3.6 上实测通过**（见 §8 步骤 6）。实测中发现一处 RabbitMQ 4.x 行为变更，需对接方注意：
> 4.x 默认**不再允许「非持久化 + 非排他」队列**（`transient_nonexcl_queues` 已废弃），此类声明会以
> `reply-code=541 INTERNAL_ERROR` 被拒。本实现**不受影响**——生产代码只做只读的
> `queueDeclarePassive`（不创建资源），push/pull 均假设目标队列已由业务方预先创建。
> 但若对接方自行用 `queueDeclare(..., durable=false, exclusive=false, ...)` 建队列，需改为
> `durable=true`（或 `exclusive=true`），否则会失败。

### 4.3 模块 pom 依赖

| 模块 | 依赖 |
| --- | --- |
| `messaging-api` | `seatunnel-web-spi`、`lombok`(provided)、`junit-jupiter`(test) |
| `messaging-rabbitmq` | `seatunnel-web-messaging-api`、`com.rabbitmq:amqp-client`、`auto-service`、`lombok`(provided)、`junit-jupiter`(test) |
| `messaging-all` | `seatunnel-web-messaging-rabbitmq`（纯聚合，无源码） |

`messaging-api` 的 pom 直接参照 `seatunnel-web-alarm-plugins/seatunnel-web-alarm-api/pom.xml`；`messaging-all` 参照 `seatunnel-web-alarm-all/pom.xml`。

`seatunnel-web-api` 侧新增两个依赖（`dependencyManagement` 里三个 messaging artifact 的版本已在根 `pom.xml` 统一声明）：

```xml
<dependency>
    <groupId>org.apache.seatunnel.web</groupId>
    <artifactId>seatunnel-web-messaging-api</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.seatunnel.web</groupId>
    <artifactId>seatunnel-web-messaging-all</artifactId>
</dependency>
```

---

## 5. 接口契约

### 5.1 推送

```http
POST /api/v1/message/push
X-Api-Key: <api-key>            # 仅当配置了 seatunnel.message.api-key 时必填
Content-Type: application/json
```

```json
{
  "connection": {
    "host": "10.0.0.1",
    "port": 5672,
    "virtualHost": "/",
    "username": "guest",
    "password": "guest",
    "sslEnabled": false,
    "connectionTimeoutMs": 10000
  },
  "exchange": "",
  "queue": "order.sync",
  "routingKey": "order.sync",
  "message": { "orderId": "A001", "amount": 100 },
  "persistent": true,
  "headers": { "source": "erp" }
}
```

**字段说明**

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `connection` | 是 | — | RabbitMQ 连接配置 |
| `connection.host` | 是 | — | MQ 主机 |
| `connection.port` | 否 | `5672` | MQ 端口 |
| `connection.virtualHost` | 否 | `/` | 虚拟主机 |
| `connection.username` | 是 | — | 用户名 |
| `connection.password` | 是 | — | 密码 |
| `connection.sslEnabled` | 否 | `false` | 是否启用 TLS |
| `connection.connectionTimeoutMs` | 否 | `10000` | 连接超时 |
| `exchange` | 否 | `""` | 留空 → 走默认交换机，用 `queue` 当 routingKey 直投 |
| `queue` | 视情况 | — | `exchange` 为空时必填 |
| `routingKey` | 否 | 取 `queue` | 走交换机时使用 |
| `message` | 是 | — | 任意 JSON 对象，服务端解析后原样转发 |
| `persistent` | 否 | `true` | `true` → `deliveryMode=2` |
| `headers` | 否 | — | 透传为 AMQP header |

**响应**

```json
{ "code": 0, "msg": "success", "data": { "success": true, "exchange": "", "routingKey": "order.sync" } }
```

### 5.2 拉取

```http
POST /api/v1/message/pull
```

```json
{
  "connection": { "...": "同上" },
  "queue": "order.sync",
  "maxMessages": 10,
  "timeoutMs": 3000
}
```

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `queue` | 是 | — | 来源队列 |
| `maxMessages` | 否 | `10` | 服务端以 `max-batch-size`（默认 100）截断 |
| `timeoutMs` | 否 | `3000` | `basicGet` 循环的 deadline |

**响应**

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "messages": [
      {
        "deliveryTag": 42,
        "parseable": true,
        "body": { "orderId": "A001", "amount": 100 },
        "headers": { "source": "erp" },
        "contentType": "application/json"
      }
    ],
    "returned": 1,
    "truncated": false,
    "elapsedMs": 12
  }
}
```

| 返回字段 | 说明 |
| --- | --- |
| `messages[].body` | 消息内容，已解析为 JSON 对象 |
| `messages[].parseable` | `true` = `body` 是合法 JSON；`false` 时改看 `rawBody` |
| `messages[].rawBody` | 内容非法 JSON 时的原文 |
| `messages[].deliveryTag` | 本次投递序号，仅供排查 |
| `returned` | 本次实际取到几条 |
| `truncated` | `true` = 队列中可能还有消息，可再调一次 |
| `elapsedMs` | 本次耗时 |

队列为空时 `messages` 为 `[]`、`returned` 为 `0`，`code` 仍为 `0` —— **不是错误**。

---

## 6. 关键实现要点

### 6.1 消息体处理（对应 D1 / D2 / D3）

接口层用 Jackson 解析入参为 `MessagePushCommand`，`message` 字段为 `JsonNode`；转发时序列化为 bytes：

```java
byte[] body = objectMapper.writeValueAsBytes(command.getMessage());
```

**必须启用解析防护**（「结构不固定」= 必须设防）。解析防护与体积上限都实现在
`MessagePayloadValidator`（`api/message/`），**不在 controller 内联** —— 便于单元测试，且这两项都是安全属性：

```java
JsonFactory factory = JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(200)          // 默认 1000，压到 200
                .maxStringLength(1_048_576)
                .maxNumberLength(1000)
                .build())
        .build();
```

- 大小上限 1 MB：**对「重新序列化后」的字节数测量**，而非原始请求体。因为前者才是真正进 MQ 的东西 —— 紧凑写法可能在规范化时膨胀，量原始输入会低估
- 校验**在进入 MQ 逻辑之前**完成
- **出向**：`basicGet` 拿到的 bytes 尝试解析为 `JsonNode`；失败则 `parseable=false` + 原文放 `rawBody`

> ⚠️ **实现阶段修掉的一个真实缺陷。** 体积校验最初写成一个只「测量」不「比较」的方法
> （`validateAndMeasureBody` 返回字节数，调用方仅判 null），导致 **1MB 上限完全未生效** ——
> 1.5MB 的消息会一路直达 broker。现已改为返回拒绝原因或 `null`，并补了
> `MessagePayloadValidatorTest`（11 个用例）作为回归防护。**其中 2 个用例在旧逻辑下会失败**，
> 已验证过。

### 6.2 拉取（对应 B1 / B3）

```java
long deadline = System.currentTimeMillis() + timeoutMs;
int limit = Math.min(requestedMax, maxBatchSize);
List<MessagePullItem> items = new ArrayList<>();
while (items.size() < limit && System.currentTimeMillis() < deadline) {
    GetResponse resp = channel.basicGet(queue, /* autoAck = */ true);   // AUTO 模式
    if (resp == null) {
        break;                       // 队列已空，立即返回，不空转等满 timeoutMs
    }
    items.add(toItem(resp));
}
```

- `autoAck = true` 即 AUTO 模式：**拿到即确认，消息出队**
- `truncated` = `items.size() == limit`

### 6.3 连接生命周期（对应 C1）

```java
public interface MessageConnectionResources extends AutoCloseable {
    Channel channel();
    @Override void close();
}
```

`RabbitMessageClient` 内部通过 `try (MessageConnectionResources res = open(param)) { ... }` 使用，保证 `finally` 关闭。

将来若需连接池，新增 `PooledConnectionResources` 实现即可，业务逻辑不动。**本次不实现池。**

### 6.4 错误处理（对应 C2）

| 场景 | HTTP | 返回文案 |
| --- | --- | --- |
| 参数缺失 / JSON 非法 | 200 | 指出具体字段（不含内网信息） |
| 连接失败 / 认证失败 | 200 | `MQ 连接失败，请检查配置` |
| 队列不存在 | 200 | `目标队列不存在` |
| 消息超限 | 200 | `消息体超过 1MB 限制` |
| API Key 错误或缺失 | 401 | 鉴权失败 |

- **不用 HTTP 5xx**：外部 HTTP 客户端普遍对 5xx 自动重试，而认证失败重试无意义
- 完整异常（含 host、底层原因）**只写服务端日志**，详略分界见 §6.4.2

#### 6.4.1 「失败 Result」与「抛异常」的分界（实现阶段补充）

`MessageException` 承载了两类含义完全不同的错误，靠 **`getCause() == null`** 区分：

| 形态 | 语义 | 行为 |
| --- | --- | --- |
| **无 cause** | 调用方必须修改请求才能成功 —— 参数为 null、`exchange` 与 `queue` 同空、TLS 初始化失败 | **抛异常** → 全局 handler 转为可读文案 |
| **有 cause** | 预期内的运营失败 —— TCP 拒连、认证失败、队列不存在 | **返回失败 Result** → 脱敏文案 |

判别式写在 `RabbitMessageClient.isCallerFixable(Exception)`。

**维护者注意**：新增 `throw new MessageException(...)` 时必须想清楚属于哪一类，并据此决定是否挂 cause。挂错会导致「连不上 MQ 时接口返回 500 而不是可读文案」，或反之「参数写错时静默返回失败而不提示」。

具体地：

- `RabbitConnectionResources.verify()` 里「连接/通道已关闭」那条**必须挂 cause** —— 死连接是运营失败
- `RabbitMessageClient.open()` 里 `useSslProtocol()` 失败那条**必须不挂 cause** —— 属配置问题

#### 6.4.2 日志详略的分界（实现阶段补充）

同一个判别式（`MessageException` 且无 cause）也决定**日志打多详细**，判别式在
`MessageController.isCallerFixable(RuntimeException)`：

| 异常形态 | 日志 | 理由 |
| --- | --- | --- |
| 无 cause 的 `MessageException` | 单行：`{动作}失败, {上下文}, reason={message}` | message 按契约已脱敏且本身即完整说明，堆栈不增加任何信息 |
| 其他任何异常 | 完整堆栈 | 服务或插件的缺陷，堆栈是唯一有用的线索 |

**为什么必须区分**：调用方可修复的错误会随调用方的重试而重复出现。一个配置错的调用方
循环重试时，每次请求都打约 60 行堆栈，会把真正的故障淹掉。此前两处 `catch` 无条件把异常
作为 SLF4J 最后一个参数传入，因此必然打堆栈 —— 这正是要避免的形态。

**日志前缀保持 `消息推送失败` / `消息拉取失败` 不变**，基于日志的 grep 与告警规则不受影响。

**运营失败（Result 路径）的日志由插件层负责**，controller 侧不重复记录 ——
`RabbitMessageClient` 中每条运营类 `Result.fail` 都配了带 throwable 的 `LOG.warn`：

| `Result.fail` | 对应日志 |
| --- | --- |
| `消息体不是合法的 JSON` | `:112` `消息体序列化失败` |
| `MQ 连接失败，请检查配置`（push） | `:123` / `:136` `RabbitMQ 投递失败` |
| `MQ 连接失败，请检查配置`（pull） | `:179` / `:187` `RabbitMQ 拉取失败` |

只有纯入参校验（`exchange 为空时 queue 必填`、`routingKey 不能为空`、`queue 不能为空`、
`maxMessages 必须大于 0`）是静默的，这类错误调用方看响应文案即可，无需服务端留痕。

### 6.5 密码保护

`MessageConnectionParam.toString()` 必须掩码，参照 `KafkaConnectionParam`：

```java
@Override
public String toString() {
    return "MessageConnectionParam{host='" + host + "', port=" + port
            + ", virtualHost='" + virtualHost + "', username='" + username
            + "', password='******'}";
}
```

> 注意：这只防日志泄露，**防不住 HTTP body 明文传输**（见 §9 风险 3）。

---

## 7. 配置项

### 7.1 新增配置（`seatunnel-web-api/src/main/resources/application.yml`）

```yaml
seatunnel:
  message:
    api-key: ${SEATUNNEL_MESSAGE_API_KEY:}                                # 空 = 不校验鉴权
    max-body-bytes: ${SEATUNNEL_MESSAGE_MAX_BODY_BYTES:1048576}           # 1MB
    max-batch-size: ${SEATUNNEL_MESSAGE_MAX_BATCH_SIZE:100}               # 单次 pull 上限
    pull-default-timeout-ms: ${SEATUNNEL_MESSAGE_PULL_DEFAULT_TIMEOUT_MS:3000}
    max-nesting-depth: ${SEATUNNEL_MESSAGE_MAX_NESTING_DEPTH:200}
```

### 7.2 环境变量（`.env`，已在 `.gitignore` 中）

```bash
SEATUNNEL_MESSAGE_API_KEY=st-web-mq-a0b46efa9725c9bdde6031ae1a346c03b4013e980022b604
```

> **禁止**把该值写进 `application.yml` 的默认值 —— 否则重犯 [`code-analysis-2026-09-21.md`](code-analysis-2026-09-21.md) 中 R1 的明文凭据问题。

### 7.3 API Key 校验行为

在 `MessageController` 内用一个私有方法校验（**不改动 `WebMvcConfig.java`**，改动面更小）：

```java
private void assertApiKey(String providedKey) {
    if (StringUtils.isBlank(configuredApiKey)) {
        return;                      // 未配置 → 完全放行
    }
    if (!configuredApiKey.equals(providedKey)) {
        throw new ServiceException(Status.UNAUTHORIZED);   // → HTTP 401
    }
}
```

- 未配置 → 行为与不加鉴权完全一致，本地开发零影响
- 配置后 → 仅影响 `/api/v1/message/**`，不影响任何现有功能

---

## 8. 实施步骤

按依赖顺序推进，每步独立可验证。

### 步骤 1：依赖与模块骨架

| 文件 | 操作 |
| --- | --- |
| `pom.xml`（根） | `<properties>` 加 `amqp-client.version`；`<modules>` 加 `seatunnel-web-messaging-plugins`；`dependencyManagement` 加 `amqp-client` 与两个 messaging artifact |
| `seatunnel-web-messaging-plugins/pom.xml` | 新建（packaging=pom，样板参照 `seatunnel-web-alarm-plugins/pom.xml`） |
| `.../seatunnel-web-messaging-api/pom.xml` | 新建 |
| `.../seatunnel-web-messaging-rabbitmq/pom.xml` | 新建 |

**验证**：`./mvnw -q -pl seatunnel-web-messaging-plugins -am validate` 通过。

### 步骤 2：契约层（messaging-api）

新建 10 个文件（见 §3 目录树）：

- `MessageClientFactory extends PrioritySPI`，声明 `name()` / `create()` / `params()` / `getIdentify()` —— **完全对照 `AlarmChannelFactory`**
- `MessageConnectionParam` 用 `@FormField` 标注（**对照 `KafkaConnectionParam`**），`toString()` 掩码密码
- `MessageClientFactory.getIdentify()` 默认实现：
  ```java
  @Override
  default SPIIdentify getIdentify() {
      return SPIIdentify.builder().name(name()).build();
  }
  ```

**验证**：`./mvnw -q -pl seatunnel-web-messaging-plugins/seatunnel-web-messaging-api -am compile` 通过。

### 步骤 3：RabbitMQ 实现（messaging-rabbitmq）

| 类 | 职责 |
| --- | --- |
| `RabbitMessageClientFactory` | `@AutoService(MessageClientFactory.class)`；`name()` 返回 `"RABBITMQ"`；`create()` 返回 `new RabbitMessageClient()`；`params()` 返回表单字段 |
| `RabbitMessageClient` | `push()` / `pull()` 实现，§6.2 §6.3 逻辑 |
| `RabbitConnectionResources` | 实现 `MessageConnectionResources`，持有 `Connection` + `Channel` |

**要点**

- 队列声明：`queueDeclare(queue, /* durable */ true, /* exclusive */ false, /* autoDelete */ false, null)` —— 幂等
- **不自动声明 exchange**（避免误建资源），仅 `basicPublish` 到调用方指定名称
- `persistent=true` → `MessageProperties.PERSISTENT_TEXT_PLAIN`，否则 `new BasicProperties()`
- `content-type` 固定 `application/json;charset=UTF-8`

**验证**：新增 `RabbitServiceLoaderTest`（**对照 `KafkaServiceLoaderTest`**），断言 `ServiceLoader.load(MessageClientFactory.class)` 能找到 `RABBITMQ`。

### 步骤 4：接口层（seatunnel-web-api）

| 文件 | 目录 |
| --- | --- |
| `MessageProperties` | `api/message/` —— `@ConfigurationProperties("seatunnel.message")` |
| `MessagePayloadValidator` | `api/message/` —— 解析防护 + 1MB 体积校验（可单测） |
| `MessagePluginManager` | `api/message/plugin/` —— 照搬 `AlarmPluginManager` |
| `MessagePushRequest` / `MessagePullRequest` | `api/controller/message/` |
| `MessageController` | `api/controller/` |

`seatunnel-web-api/pom.xml` 增加两个依赖（`messaging-api`、`messaging-all`）。

**实现阶段确认的两点**

1. **鉴权失败走 HTTP 401，其余一律 200。** 共享的 `Status` 枚举里没有鉴权相关条目（已核对全表），因此 `MessageController` 内定义了私有异常 `UnauthorizedException` + 局部 `@ExceptionHandler`，返回 `ResponseEntity.status(401)`。**不改动全局的 `ApiExceptionHandler` / `CustomGlobalExceptionHandler`** —— 那两个是 `@RestControllerAdvice`，改它们会影响全部 45 个 controller。局部 handler 只作用于本 controller。

2. **Jackson 解析防护落在接口层**（契约层刻意不引 Jackson）。实现在 `MessagePayloadValidator`：
`maxNestingDepth=200`、`maxStringLength=1MB`、`maxNumberLength=1000`。1MB 体积校验在**进入 MQ 逻辑之前**完成。

3. **`MessagePayloadValidator` 独立成类而非内联在 controller。** 体积上限与解析防护都是安全属性，
必须能被单元测试直接覆盖 —— 内联在 controller 里就测不到，而正是这个缺陷（1MB 上限未生效）
在没有测试的情况下被漏掉了。

### 步骤 5：编译与单元测试

1. 全量编译：`./mvnw -q -DskipTests compile`
2. 针对性测试：`./mvnw -pl seatunnel-web-messaging-plugins/seatunnel-web-messaging-api,seatunnel-web-messaging-plugins/seatunnel-web-messaging-rabbitmq -am test`
3. 测试用例（**均已实现并通过**）：

   **A. 不依赖 broker（49 个，随构建自动运行）**
   - `RabbitServiceLoaderTest`（4）：SPI 发现、identify 稳定、表单字段键名与 `MessageConnectionParam` 属性名对齐、`create()` 可用
   - `RabbitMessageClientTest`（13）：入参校验、`exchange`/`queue` 组合校验、不可达 broker 返回脱敏失败、错误信息不含 host 与凭据、Result 工厂、任意结构 JSON 往返
   - `MessageConnectionParamTest`（6）：`toString()` 不含明文密码、保留排查字段、默认值回退、null/空白/0 处理
   - `MessagePayloadValidatorTest`（11）：**1MB 上限回归防护**（超限/略超限/未超限/自定义上限）、null 与显式 JSON null、任意嵌套结构、数组根节点、空对象、序列化往返、嵌套深度上限低于 Jackson 默认
   - `MessageControllerTest`（15）：HTTP 层。鉴权（缺 key / 错 key / 对 key / 未配 key 时跳过）、1MB 超限在进入 MQ 逻辑前被拒、push 的 exchange/queue/routingKey/payload 透传、未知 clientType、pull 的 parseable/不可解析消息整形、`maxMessages` 上限钳制与非正数拒绝、失败不返回 5xx；**异常路径与日志详略**（见 §6.4.2）—— 无 cause 的 `MessageException` 记单行且 `getThrowableProxy()` 为 null，意外异常保留堆栈且内部细节不回传调用方

   **B. 依赖真实 broker（11 个，**默认不运行**，需显式开启）**

   | 测试类 | 用例数 | 覆盖 |
   | --- | --- | --- |
   | `RabbitMessageClientLiveIT` | 6 | MQ 层：任意嵌套 JSON 往返（含中文与 emoji）、空队列、`maxMessages` 边界且剩余消息仍在队列、错误凭据返回脱敏失败而非抛异常、队列不存在给出可读原因、非 JSON 消息降级为 `rawBody` |
   | `MessageEndpointLiveIT` | 5 | 全链路 HTTP：push→pull 往返、**超限消息体确实未进 broker（回读队列为 0 条）**、错误密码脱敏、缺 key 返回真实 401、未知队列不返回 5xx |

   开启方式（两个 IT 均以 `@EnabledIfSystemProperty` 门控，未开启时连测试选择都命中不到，绝不会让无 broker 的机器变红）：

   ```bash
   ./.mvn/mvn21.sh -pl seatunnel-web-api test -DskipTests=false \
       -Dtest=MessageEndpointLiveIT -DfailIfNoTests=false \
       -Dmessaging.it.enabled=true \
       -Dmessaging.it.user=<user> -Dmessaging.it.pass=<password>
   ```

   可选覆盖项：`messaging.it.host`（127.0.0.1）、`messaging.it.port`（5672）、`messaging.it.vhost`（/）、`messaging.it.queue-prefix`（seatunnel.it）。每个用例使用随机队列名并在结束后删除，不依赖也不污染 broker 既有状态。

> ⚠️ **`-pl` 指向 pom 聚合模块不会递归到子模块。** `-pl seatunnel-web-messaging-plugins` 只跑到聚合 pom 本身（2 个模块、0 个测试）却报 BUILD SUCCESS，容易造成「测试过了」的误判。必须显式列出叶子模块。
>
> 另：`seatunnel-web-api` 的 pom 里 `skipTests` 默认为 `true`，跑该模块测试需显式 `-DskipTests=false`；若同时用 `-Dtest=` 过滤，还需 `-DfailIfNoTests=false`，否则无匹配测试的模块会报错中断。

### 步骤 6：联调验收

依据 `AGENTS.md`「验收」条款，涉及用户可操作行为的新增必须在宣称完成前实际走通 happy path。

**已完成（真实 broker：RabbitMQ 4.3.6，`amqp-client` 5.36.0）**

| 验收项 | 方式 | 结果 |
| --- | --- | --- |
| push → pull 内容一致 | `MessageEndpointLiveIT.pushThenPullOverHttpRoundTripsArbitraryJson` | 通过。任意嵌套 JSON 逐字段一致（树级比对），中文与 emoji 无损 |
| 超限消息体被拒 **且未进 broker** | `MessageEndpointLiveIT.oversizePayloadIsRejectedBeforeItReachesTheBroker` | 通过。回读队列 0 条——这是 mock 无法伪造的证明 |
| 错误凭据返回脱敏文案 | `MessageEndpointLiveIT.wrongPasswordIsReportedAsASanitizedFailure` | 通过。响应不含密码、不含异常类名 |
| 配好 key 后不带 header → 401 | `MessageEndpointLiveIT.missingApiKeyIsRejectedWithAReal401` | 通过。真实 HTTP 401 |
| `maxMessages` 边界与剩余消息保留 | `RabbitMessageClientLiveIT.pullHonoursMaxMessagesAndLeavesTheRestBehind` | 通过。拉 3 留 2，再次拉取得 2 |
| 非 JSON 消息降级 | `RabbitMessageClientLiveIT.nonJsonBodyIsReturnedAsRawTextInsteadOfFailing` | 通过。`parseable=false`，`rawBody` 原样 |

**尚未完成（需要额外环境）**

后端全量启动路径未验收：`seatunnel-web-api` 依赖 MySQL，本机 3306 未运行、`.env` 不存在，
且 `AGENTS.md` 规定未经明确授权不得执行 Compose / 部署操作。`MessageEndpointLiveIT` 已把
「HTTP → 控制器 → 校验器 → SPI → RabbitMQ → HTTP」整条链路跑通（仅 HTTP 传输为模拟），
因此剩余未验证部分只有 Spring 容器装配与真实网络监听，而非业务逻辑。

若需补齐全量验收：

1. 准备 MySQL（或授权启动 Compose）并生成 `.env`
2. `scripts/dev-up.sh` 启动后端
3. 用 `scripts/message-api-demo.sh` 走通：`push` / `pull` / `push-err` / `oversize` / 无 header → 401

> 注：`scripts/dev-up.sh` 内部调用 `./mvnw`。仓库里 `mvnw` / `mvnw.cmd` 曾是 3.1.0 时代的脚本，
> 与 `maven-wrapper.properties` 声明的 `wrapperVersion=3.3.4` + `distributionType=only-script` 不一致
> （旧脚本忽略 `distributionType`，硬编码 `maven-wrapper-3.1.0.jar` 下载地址，还去读一个并不存在的
> `wrapperUrl` 键），已在 `8a4fc751` 中重新生成并对齐。生成后的 `mvnw` 不再引用任何 jar。
>
> 但在 Windows + Git Bash 环境下 `./mvnw` 仍跑不完：`only-script` 模式最终会 exec 出 Maven 发行包自带的
> `bin/mvn` 脚本，而该脚本在 MSYS 路径转换下把 POSIX 形式的 classpath 交给 Windows JVM，导致
> `找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`。这与仓库原本就无法直接使用
> 裸 `mvn` 是同一个原因，属既有本地环境问题。已实测确认 wrapper 自身的下载与解压是正常的：
> `~/.m2/wrapper/dists/apache-maven-3.9.16/<hash>` 可正常启动并输出 `Apache Maven 3.9.16`（JDK 21）。
> Linux / CI 不受影响。

---

## 9. 已知风险（均已 opt-in）

| # | 风险 | 状态 |
| --- | --- | --- |
| 1 | **at-most-once，可能丢消息**（B2）。HTTP 响应发出失败时消息已出队，永久丢失 | **已接受**。已写入使用说明 §6，要求对接方可重放 |
| 2 | **无 SSRF 防护**（A4）。调用方可传任意 host，含 `169.254.169.254` 等元数据地址；「可达性校验」拦不住 | **已接受** |
| 3 | **密码明文经 HTTP body 传输**（A3）。会进入 body 日志 / 异常堆栈（`toString()` 掩码只解决后者） | **已接受**。建议生产启用 HTTPS |
| 4 | 无消息持久化 / 审计记录。无法回答「谁在什么时候发了什么」 | 已接受（仅日志可查） |
| 5 | 每请求新建 AMQP 连接，高频场景会拖慢服务端 | 已接受（C1）。单次握手约 10–50 ms |
| 6 | API Key 为静态共享密钥，无法区分具体对接方 | 已接受。将来可扩展为多 Key |

> **这些不是遗漏，是有意为之。** 后续维护者请勿擅自「修复」，如需变更请先修订本文档 §2。

---

## 10. 交付物清单

| 类型 | 内容 |
| --- | --- |
| 新增模块 | 3 个（`messaging-api`、`messaging-rabbitmq`、`messaging-all`）+ 1 个聚合 pom |
| 新增 Java 文件 | 23 个（契约 10 + 实现 3 + 接口层 6 + 测试 4） |
| 修改既有文件 | 5 个（根 `pom.xml`、`seatunnel-web-api/pom.xml`、`application.yml`、`.env.example`、`.gitignore`） |
| 新增测试 | 6 个测试类：49 个用例随构建运行 + 11 个用例连真实 broker（默认关闭） |
| 新增配置 | `application.yml` 7 项 + `.env.example` 7 项 |
| 对外文档 | [`rabbitmq-messaging-api-usage.md`](rabbitmq-messaging-api-usage.md)（已产出） |
| 调试脚本 | `scripts/message-api-demo.sh`（已产出并验证） |

### 提交记录（`develop` 分支）

| commit | 类型 | 内容 |
| --- | --- | --- |
| `1fe824e4` | chore | `.gitignore` + `.env.example` |
| `25907cbb` | feat | messaging 插件族（3 模块）+ 根 `pom.xml` |
| `d51feaa1` | feat | 接口层 + `application.yml` |
| `38952bcc` | docs | 实现文档 + 使用说明 + 调试脚本 |
| `a5a4abb9` | fix | **1MB 上限真正生效**（此前从未被校验）+ 11 个回归用例 |
| `2b9c6fd9` | test | HTTP 层 MockMvc 覆盖（13 例） |
| `93fe0194` | test | 真实 broker 验收套件（6 例，opt-in） |
| `943523c4` | test | 全链路 HTTP 验收套件（5 例，opt-in） |
| `089df1b1` | fix | 验收套件改用持久化队列（RabbitMQ 4.x 拒绝 transient non-exclusive） |

---

## 11. 开工前检查清单

- [ ] 确认 `amqp-client` 版本 `5.36.0`
- [ ] 确认不纳入数据源体系（不修改 `DbType`）
- [ ] 向对接方确认其 RabbitMQ 的 `max_message_size` 未被调小到 1MB 以下
- [ ] 确认 `.env` 中已加入 `SEATUNNEL_MESSAGE_API_KEY`
- [ ] 确认本地/测试环境有可用的 RabbitMQ 实例（联调阶段需要）

---

## 附：实现参照索引

本项目已有高度相似的实现，编码时**优先对照而非自行设计**：

| 要写的东西 | 照抄对象 |
| --- | --- |
| 聚合模块 pom | `seatunnel-web-alarm-plugins/pom.xml` |
| 契约层 pom | `seatunnel-web-alarm-plugins/seatunnel-web-alarm-api/pom.xml` |
| SPI 工厂契约 | `seatunnel-web-alarm-plugins/seatunnel-web-alarm-api/.../AlarmChannelFactory.java` |
| 连接参数 + `@FormField` | `seatunnel-web-datasource-plugins/seatunnel-web-datasource-kafka/.../KafkaConnectionParam.java` |
| `@AutoService` 注册 | `seatunnel-web-datasource-plugins/seatunnel-web-datasource-kafka/.../KafkaDataSourceProcessor.java` |
| Spring 侧 SPI 管理器 | `seatunnel-web-api/src/main/java/org/apache/seatunnel/web/api/alarm/plugin/AlarmPluginManager.java` |
| ServiceLoader 测试 | `seatunnel-web-datasource-plugins/seatunnel-web-datasource-kafka/src/test/java/.../KafkaServiceLoaderTest.java` |
| 返回体 | `seatunnel-web-spi/src/main/java/org/apache/seatunnel/web/spi/bean/entity/Result.java` |
| 异常 | `seatunnel-web-core/src/main/java/org/apache/seatunnel/web/core/exceptions/ServiceException.java` |

> ⚠️ **注意 SPI 注册目录名是 `META-INF`，不是 `META-INFO`。** 现有三个 dao-plugin 模块存在该拼写错误（见分析报告 R3），新增模块时勿重犯；本模块依赖 `@AutoService` 自动生成，无需手写 services 文件。
