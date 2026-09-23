# RabbitMQ 消息推送 / 拉取接口 · 使用说明

> 面向对象：**外部系统对接方**  
> 版本：v1.1　|　日期：2026-09-22  
> 接口规划依据：[`rabbitmq-messaging-implementation.md`](rabbitmq-messaging-implementation.md)



---

## 一、这个接口是做什么的

本平台提供两个通用接口，帮你操作平台配置的 RabbitMQ：

| 接口                                 | 作用              |
| ---------------------------------- | --------------- |
| **推送** `POST /api/v1/message/push` | 把一段 JSON 消息发进队列 |
| **拉取** `POST /api/v1/message/pull` | 从队列里取出一批消息      |

你可以把本平台当成一个**消息中继**：你告诉它「发到哪、发什么」，它用服务端配置好的 broker 替你完成操作。

**你不需要安装 RabbitMQ 客户端，也不需要直接连 MQ。** Broker 地址与账号由平台管理员在 `seatunnel.message.broker`（或环境变量）中配置，请求体里不再携带连接信息。

---

## 二、调用前准备

需要向平台管理员获取：

1. **接口地址**（例如 `http://<平台地址>:9527`）
2. 确认平台侧已配置好 `seatunnel.message.broker`（主机、端口、用户名、密码等）

需要你自己准备好 **MQ 侧的目标拓扑**。平台**只做中继，从不替你创建队列、交换机或绑定**，所以下面这些必须已经在 broker 上存在：

| 拓扑          | 什么时候需要                      | 缺失时的表现                         |
| ----------- | --------------------------- | ------------------------------ |
| 队列          | 两个接口都要                      | `push` 静默丢弃；`pull` 报 `目标队列不存在` |
| 交换机         | 仅 `push` 且填了 `exchange` 时需要 | `push` 静默丢弃                    |
| 绑定（binding） | 同上，且要求 routing key 能匹配上     | `push` 静默丢弃                    |

「静默丢弃」的意思是：**消息哪儿都没去，但接口仍返回成功**。详见第四节。

---

## 三、请求头

两个接口都需要携带：

```http
Content-Type: application/json
```

本接口**不要求**登录或 `X-Api-Key`。

---

## 四、推送消息

### 请求

```http
POST /api/v1/message/push
Content-Type: application/json
```

**形态一：直接发到队列（推荐）**

```json
{
  "queue": "order.sync",
  "message": {
    "orderId": "A001",
    "amount": 100,
    "items": [ { "sku": "X1" } ]
  },
  "persistent": true
}
```

**形态二：走交换机路由**

```json
{
  "exchange": "entity.schema.exchange",
  "routingKey": "entity.schema",
  "queue": "order.sync",
  "message": { "orderId": "A001" }
}
```

走交换机时建议把 `queue` 一并填上：`routingKey` 缺省时会回退成它的值。若你显式给了 `routingKey`，`queue` 就不再参与投递——消息能否到达队列，完全取决于该交换机上有没有匹配的绑定。

如果你只是想把消息投到某个队列，用形态一更简单，也不依赖任何绑定。


### 参数说明

| 参数                               | 必填 | 默认值         | 说明                                         |
| -------------------------------- | -- | ----------- | ------------------------------------------ |
| `clientType`                     | 否  | `RABBITMQ`  | broker 类型。**大小写敏感**，当前仅 `RABBITMQ` 可用      |
| `queue`                          | 是  | —           | 目标队列名。`exchange` 为空时必填，此时它同时充当 routing key |
| `message`                        | 是  | —           | **任意 JSON 对象**，结构不限，最大 1 MB                |
| `exchange`                       | 否  | `""`        | 交换机名。**一般留空不填**                            |
| `routingKey`                     | 否  | 回退为 `queue` | 仅当填了 `exchange` 时才有意义；不填则回退成 `queue` 的值    |
| `persistent`                     | 否  | `true`      | 是否持久化消息                                    |
| `headers`                        | 否  | —           | 自定义消息头（键值对）                                |

> Broker 连接由服务端 `seatunnel.message.broker` 配置，请求体**不再**接受 `connection`。

### 走 `exchange` 时的前提（重要）

**不填 `exchange`（推荐）** 时，消息经默认交换机直达 `queue`，不需要你建任何绑定——broker 会为**每个队列**自动维护一条不可删、不可改的绑定，routing key 就是队列名本身。这正是「直发队列不用建绑定」的原因。

**一旦填了 `exchange`，下面两个前提必须都满足**，否则消息会被 broker **静默丢弃**——而本接口**仍会返回 `code: 0` / `success: true`**，看起来完全成功：

1. **交换机必须已存在**
2. **队列上必须已有一条从该交换机出发、routing key 匹配的绑定（binding）**

原因：平台投递用的是 `basicPublish` 的非 mandatory 形式，也不注册 ReturnListener，未路由的消息 broker 不会退回，平台无从得知。而且 `basicPublish` 不等服务端应答，**连「交换机根本不存在」这种情况也会返回成功**（已实测确认）。

**还有第三种静默丢弃**：即使不填 `exchange`，只要 `queue` 指定的**队列不存在**，消息同样被丢弃，接口同样返回成功。

所以平台**从不替你声明队列、交换机或绑定**——它只是中继，拓扑需要你自己维护。

**自查方式**（RabbitMQ 管理接口）：

```bash
# 1) 交换机是否存在（不存在会返回 404）
curl -u <user>:<pass> "http://<host>:15672/api/exchanges/%2F/<exchange>"

# 2) 该交换机的出向绑定
#    注意：交换机不存在时这里也返回 200 + []，不能用来判断存在性
curl -u <user>:<pass> "http://<host>:15672/api/exchanges/%2F/<exchange>/bindings/source"
```

能否路由还要看交换机类型：`direct` 精确相等、`topic` 支持 `*` 与 `#` 通配、`fanout` 忽略 routing key、`headers` 看 arguments。

> 建议联调阶段先跑一次上面两条命令，确认交换机存在、绑定匹配，再去调接口。

### 成功响应

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "success": true,
    "exchange": "",
    "routingKey": "order.sync",
    "bodyBytes": 37
  }
}
```

`bodyBytes` 是本次实际投递的字节数。它只说明消息已交给 channel，**不代表已进入队列**——见第六节。

### 关于 `message`

`message` 直接写成 **JSON 对象**即可，不要转成字符串：

```json
// 正确
"message": { "orderId": "A001", "amount": 100 }

// 错误（不要这样做）
"message": "{\"orderId\":\"A001\",\"amount\":100}"
```

结构随意，嵌套、数组、中文都支持，只要不超过 **1 MB**。

---

## 五、拉取消息

### 请求

```http
POST /api/v1/message/pull
Content-Type: application/json
```

```json
{
  "queue": "order.sync",
  "maxMessages": 10,
  "timeoutMs": 3000
}
```

### 参数说明

| 参数            | 必填 | 默认值        | 说明                                                |
| ------------- | -- | ---------- | ------------------------------------------------- |
| `clientType`  | 否  | `RABBITMQ` | 同推送，**大小写敏感**                                     |
| `queue`       | 是  | —          | 从哪个队列取                                            |
| `maxMessages` | 否  | `10`       | 最多取几条。**服务端上限 100 条**                             |
| `timeoutMs`   | 否  | `3000`     | 本次尝试的**截止上限**（毫秒）。**队列为空会立即返回**，不会等满这个时间，所以它不是长轮询 |

> **`pull` 没有 `exchange` 概念。** 消费端永远只认队列——即使消息是走交换机投递的，最终也落在某个队列里，你只能从队列取。

### 成功响应

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


### 响应字段说明

| 字段                       | 含义                                             |
| ------------------------ | ---------------------------------------------- |
| `messages`               | 本次取到的消息数组                                      |
| `messages[].body`        | 消息内容（已解析为 JSON 对象）。与 `rawBody` **二选一出现**       |
| `messages[].parseable`   | `true` = `body` 是合法 JSON；`false` 时改看 `rawBody` |
| `messages[].rawBody`     | 内容不是合法 JSON 时的原文。与 `body` **二选一出现**            |
| `messages[].headers`     | 消息头。**仅在消息携带自定义头时出现**                          |
| `messages[].contentType` | 消息的 content type。**仅在消息携带该属性时出现**              |
| `messages[].deliveryTag` | 消息在本次投递中的序号，仅供排查参考                             |
| `returned`               | 本次实际取到几条                                       |
| `truncated`              | `true` = 队列里还有消息没取完，可再调用一次                     |
| `elapsedMs`              | 本次耗时（毫秒）                                       |

`headers` 与 `contentType` 是**条件出现**的字段：消息本身没带，响应里就没有。反过来，响应里没有 `rawBody`，就说明 `body` 是解析好的 JSON。

> **队列为空是正常的**：此时 `messages` 是 `[]`、`returned` 为 `0`、`elapsedMs` 接近 `0`，`code` 仍为 `0`，不是错误。

---

## 六、⚠️ 必读：消息可能丢失

**这是本接口最重要的限制，请务必完整理解。** 丢失分两类，成因完全不同。

### 类型一 · 推送未路由导致的静默丢弃

`push` 返回成功**不代表消息进了队列**。以下三种情况消息会被 broker 直接丢弃，而接口照样返回 `success: true`：

- 填了 `exchange`，但该交换机**不存在**
- 填了 `exchange`，交换机存在但**没有任何 routing key 匹配的绑定**
- 没填 `exchange`，但 `queue` 指定的**队列不存在**

原因与自查方式见第四节「走 `exchange` 时的前提」。**这类丢失是「假成功」，你从响应里看不出来。**

### 类型二 · 拉取取走即删

消息一旦被你拉走（`pull` 返回成功），它**就从这个队列永久删除了**，不会再回来。

如果出现以下情况，**这条消息就永久丢失，任何人都找不回来**：

- 你发出了 `pull` 请求，但网络中断，响应没收到
- 你的程序收到响应后处理失败
- 你的程序崩溃、重启

### 你需要做的事

1. **不要靠接口返回值判断消息是否送达** —— 见类型一。要确认请查 MQ 管理台的队列深度，或直接 `pull` 回读
2. **保证自己能重放** —— 例如消息本身来自你的业务表，丢失后你能重新推一次
3. **取到消息先落地再处理** —— 先写入本地存储或数据库，再做业务处理
4. **不要用这个接口传输不能丢失的数据** —— 如金融交易凭证、不可重现的日志

### 如果你需要「绝对不丢」

当前接口**不提供**这个保证。请联系平台管理员评估其他方案（例如直接在 MQ 侧消费）。

---

## 七、错误处理

### 所有业务错误都返回 HTTP 200

请通过响应体中的 `code` 判断成败。`code != 0` 即为失败。

```json
{ "code": 500, "msg": "MQ 连接失败，请检查配置", "data": null }
```


### 错误对照表

| 情况                        | HTTP | `msg`                             |
| ------------------------- | ---- | --------------------------------- |
| 连接不上 / 认证失败               | 200  | `MQ 连接失败，请检查配置`                   |
| 队列不存在（**pull**）           | 200  | `目标队列不存在`                         |
| 队列不存在（**push**）           | 200  | **不报错**，返回 `success: true` 但消息被丢弃 |
| 没有操作该队列的权限                | 200  | `没有操作该队列的权限`                      |
| 队列声明与既有属性冲突               | 200  | `队列声明与既有属性冲突`                     |
| `clientType` 填错（含大小写不符）   | 200  | `不支持的消息类型: <你填的值>`                |
| 消息超过 1 MB                 | 200  | `消息体超过 1MB 限制，实际 123KB`           |
| `message` 为空              | 200  | `message 不能为空`                    |
| `message` 不是合法 JSON       | 200  | `消息体不是合法的 JSON`                   |
| 未填 `exchange` 且未填 `queue` | 200  | `exchange 为空时 queue 必填`           |
| `pull` 未填 `queue`         | 200  | `queue 不能为空`                      |
| `maxMessages` 小于等于 0      | 200  | `maxMessages 必须大于 0`              |

> **注意「不报错」那一行。** `push` 的失败可能完全不体现在响应里。判断消息是否真的送达，只能查 MQ 管理台的队列深度，或用 `pull` 回读。

### 两点提醒

1. **不要对 HTTP 5xx 做自动重试** —— 本接口的业务错误都返回 200，不会出现 5xx。如果拿到了 5xx，那是平台自身故障，重试无益，请联系管理员。
2. **错误信息不含网络细节** —— 出于安全考虑，服务端不会返回具体的 IP、端口或底层异常。需要排查时请联系管理员查看服务端日志。

---


## 八、常见问题

**Q：`message` 里能放多复杂的结构？**  
任意合法 JSON。嵌套、数组、中文都可以，只要总大小不超过 **1 MB**。

**Q：一次能推多条消息吗？**  
不能，一次调用推一条。需要推多条就多次调用。

**Q：`maxMessages` 填 500 行不行？**  
填了也会被服务端截断到 100 条。请查看返回的 `truncated`：

- `truncated = true` → 还有消息，继续调 `pull`
- `truncated = false` → 已取完

**Q：`exchange` 到底要不要填？**  
**一般不填。** 不填就是直接发到 `queue` 指定的队列。只有当你明确需要走交换机的路由能力（如 fanout、topic）时才填。

**一旦填了，你就得自己保证交换机存在、且队列上有匹配的绑定**，否则消息会被静默丢弃，而接口仍返回成功。详见第四节。

**Q：接口返回成功，怎么确认消息真的到了？**  
`push` 的返回值**不能**用来判断。两个办法：一是到 MQ 管理台看目标队列的 `Ready` 数量；二是用 `pull` 把消息读回来比对。联调阶段建议每次 `push` 后都验证一次。

**Q：连接信息每次都要传吗？**  
是的，每次调用都要传完整配置。

**Q：消息在里面会存多久？**  
本接口不做保留。不取就一直堆在队列里，直到被取走或队列被清理。保留策略由你自己的 RabbitMQ 决定。

**Q：密码明文传输安全吗？**  
**请务必在生产环境使用 HTTPS。** 当前接口的密码在请求体中明文传输，HTTP 环境下可能被中间人截获。

**Q：对我的 RabbitMQ 版本有要求吗？**  
**基本没有。** 平台内部使用标准 AMQP 0-9-1 协议客户端，该协议已经冻结稳定，可连接 RabbitMQ **3.x ~ 4.x 全系版本**，也兼容阿里云 / 腾讯云 / Amazon MQ 等托管 RabbitMQ 服务。

但请确认你的服务端**这两项配置没有被调小**：

| 配置                 | 要求         | 说明                                |
| ------------------ | ---------- | --------------------------------- |
| `max_message_size` | **≥ 1 MB** | 默认 128MB。若被管理员调小到 1MB 以下，你发的消息会被拒 |
| `channel_max`      | 无特殊要求      | 平台每次请求只用 1 个 channel              |

如果发现消息发送失败且错误提示与消息体大小相关，请联系你的 RabbitMQ 管理员检查 `max_message_size`。

**Q：必须用标准 RabbitMQ 吗？**  
标准 RabbitMQ 或兼容 AMQP 0-9-1 的实现都可以。若你使用 Apache Qpid 等非 RabbitMQ 的 broker，建议先做一次联调验证。

---

## 九、快速接入清单

- [ ] 向管理员索取接口地址
- [ ] 准备好 RabbitMQ 地址、端口、虚拟主机、账号、密码
- [ ] **确认目标队列已存在**（走 `exchange` 时还要确认交换机与绑定已存在）
- [ ] 按需导出 `MQ_*` 环境变量后跑调试脚本（详见文末「调试脚本」）
- [ ] 用 `scripts/message-api-demo.sh` 或 Postman 调一次 `push`
- [ ] **接口返回成功不代表消息已到达** —— 到 MQ 管理台确认队列深度，或调 `pull` 回读比对
- [ ] 调一次 `pull`，确认能取回消息且内容与推入时一致
- [ ] **在自己的代码中实现「消息可重放」机制**
- [ ] 确认生产环境使用 HTTPS
- [ ] 确认已理解并接受「消息可能丢失」（见第六节）

---

## 附：调试脚本

仓库内提供了 `scripts/message-api-demo.sh`，可直接发请求验证。

**运行前按需导出 MQ 相关环境变量。** 脚本刻意不内置凭据（避免明文密钥进入 git 历史）：

```bash
export MQ_USER=admin MQ_PASSWORD=admin123              # 换成你自己 broker 的账号密码
# 按需再覆盖：MQ_HOST / MQ_PORT / MQ_VHOST / MQ_QUEUE / SEATUNNEL_WEB_BASE_URL
```

然后：

```bash
# 推送
./scripts/message-api-demo.sh push

# 拉取
./scripts/message-api-demo.sh pull

# 推送一条必然失败的消息（错误的密码），看错误响应长什么样
./scripts/message-api-demo.sh push-err

# 推送一条超过 1 MB 的消息，验证服务端会拒绝
./scripts/message-api-demo.sh oversize

# 查看用法（含上面这些前置说明）
./scripts/message-api-demo.sh help
```

> 关于 `MQ_USER` / `MQ_PASSWORD`：脚本默认值是 `guest` / `guest`，那是 RabbitMQ 的开箱账号，  
> 默认只允许从 localhost 登录。本项目本地联调用的 broker 通常是 `admin` / `admin123`，  
> 所以务必按上面的方式覆盖，否则会连不上你自己的 MQ。
>
> 脚本中的接口地址与 MQ 配置全部可用环境变量覆盖，完整清单见脚本头部注释。
