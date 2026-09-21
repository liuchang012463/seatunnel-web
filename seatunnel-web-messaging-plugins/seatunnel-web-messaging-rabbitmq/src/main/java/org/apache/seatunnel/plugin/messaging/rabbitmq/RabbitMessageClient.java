package org.apache.seatunnel.plugin.messaging.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.MessageProperties;
import org.apache.seatunnel.plugin.messaging.api.MessageClient;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionResources;
import org.apache.seatunnel.plugin.messaging.api.MessageException;
import org.apache.seatunnel.plugin.messaging.api.MessagePullCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePullItem;
import org.apache.seatunnel.plugin.messaging.api.MessagePullResult;
import org.apache.seatunnel.plugin.messaging.api.MessagePushCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePushResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RabbitMQ worker: {@code basicPublish} for push and a {@code basicGet} loop
 * for pull.
 *
 * <p>
 * <b>Stateless by design.</b> Mirrors the alarm family, where the channel
 * worker holds no resources and receives everything per call. All connection
 * state lives in {@link RabbitConnectionResources}, created and released inside
 * each method.
 * </p>
 *
 * <h3>Delivery semantics — read before "fixing"</h3>
 * <p>
 * Pull uses {@code autoAck = true}, so a message leaves the queue the moment it
 * is read. If the HTTP response then fails to reach the caller, that message is
 * gone (at-most-once). This is an explicitly accepted trade-off — see design
 * decision B2 and the risk register in
 * {@code docs/rabbitmq-messaging-implementation.md} §9. Switching to manual ack
 * would require rethinking the whole request/response contract (the ack would
 * have to survive an HTTP hop), so do not make that change locally.
 * </p>
 *
 * <p>
 * Publisher confirms are likewise <b>not</b> awaited (decision E): a successful
 * push means "handed to an open channel", not "persisted". This is consistent
 * with the at-most-once stance.
 * </p>
 */
public class RabbitMessageClient implements MessageClient {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitMessageClient.class);

    /**
     * Queue probed by {@link RabbitConnectionResources#verify()}.
     *
     * <p>A library-provided, virtually always-present queue, used only for a
     * read-only reachability probe. It is never read from or written to.</p>
     */
    static final String PROBE_QUEUE = "amq.rabbitmq.reply-to";

    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    /**
     * Own mapper instance. The contract layer is deliberately Jackson-free, so
     * serialization of the caller-defined payload happens here.
     *
     * <p>Plain rather than a shared bean: this module must not depend on
     * {@code core} or Spring, matching how the datasource plugins stay
     * self-contained.</p>
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public MessagePushResult push(MessageConnectionParam param, MessagePushCommand command) {
        if (param == null) {
            throw new MessageException("connection 参数不能为空");
        }
        if (command == null) {
            throw new MessageException("push command 不能为空");
        }

        String queue = trimToNull(command.getQueue());
        String exchange = command.getExchange() == null ? "" : command.getExchange().trim();

        if (exchange.isEmpty() && queue == null) {
            return MessagePushResult.fail("exchange 为空时 queue 必填");
        }
        // When publishing to the default exchange the queue name IS the routing
        // key (decision D4); otherwise fall back to the queue name if the caller
        // gave no explicit routing key.
        String routingKey = trimToNull(command.getRoutingKey());
        if (routingKey == null) {
            routingKey = queue;
        }
        if (exchange.isEmpty()) {
            routingKey = queue;
        }
        if (routingKey == null) {
            return MessagePushResult.fail("routingKey 不能为空");
        }

        byte[] body;
        try {
            body = OBJECT_MAPPER.writeValueAsBytes(command.getPayload());
        } catch (Exception e) {
            LOG.warn("消息体序列化失败, messageType={}", typeName(command.getPayload()), e);
            return MessagePushResult.fail("消息体不是合法的 JSON");
        }

        AMQP.BasicProperties properties = buildProperties(command);

        try (MessageConnectionResources res = open(param)) {
            Channel channel = (Channel) res.channel();
            try {
                channel.basicPublish(exchange, routingKey, properties, body);
            } catch (java.io.IOException e) {
                LOG.warn("RabbitMQ 投递失败, host={}:{}, queue={}, exchange={}, routingKey={}",
                        param.getHost(), param.resolvePort(), queue, exchange, routingKey, e);
                return MessagePushResult.fail(mapIoFailure(e));
            }
            return MessagePushResult.success(exchange, routingKey, body.length);
        } catch (Exception e) {
            // Covers both a failed open() and a failed publish. open() throws
            // MessageException with no cause for its own validation failures, so
            // treat it as retryable only when it wraps an underlying cause;
            // otherwise it is a configuration problem the caller must fix.
            if (isCallerFixable(e)) {
                throw (MessageException) e;
            }
            LOG.warn("RabbitMQ 投递失败, host={}:{}", param.getHost(), param.resolvePort(), e);
            return MessagePushResult.fail("MQ 连接失败，请检查配置");
        }
    }

    @Override
    public MessagePullResult pull(MessageConnectionParam param, MessagePullCommand command) {
        if (param == null) {
            throw new MessageException("connection 参数不能为空");
        }
        if (command == null) {
            throw new MessageException("pull command 不能为空");
        }

        String queue = trimToNull(command.getQueue());
        if (queue == null) {
            return MessagePullResult.fail("queue 不能为空");
        }

        int limit = command.getMaxMessages();
        if (limit <= 0) {
            return MessagePullResult.fail("maxMessages 必须大于 0");
        }
        long timeoutMs = command.getTimeoutMs() <= 0 ? 3000L : command.getTimeoutMs();

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMs;
        List<MessagePullItem> items = new ArrayList<>();

        try (MessageConnectionResources res = open(param)) {
            Channel channel = (Channel) res.channel();
            try {
                // Loop until the batch is full or the queue is drained. Returning
                // early on an empty queue is intentional (decision B3): blocking
                // for the full timeout would waste a worker thread per request.
                while (items.size() < limit && System.currentTimeMillis() < deadline) {
                    GetResponse response = channel.basicGet(queue, /* autoAck = */ true);
                    if (response == null) {
                        break;
                    }
                    items.add(toItem(response));
                }
            } catch (java.io.IOException e) {
                LOG.warn("RabbitMQ 拉取失败, host={}:{}, queue={}",
                        param.getHost(), param.resolvePort(), queue, e);
                return MessagePullResult.fail(mapIoFailure(e));
            }
        } catch (Exception e) {
            if (isCallerFixable(e)) {
                throw (MessageException) e;
            }
            LOG.warn("RabbitMQ 拉取失败, host={}:{}, queue={}",
                    param.getHost(), param.resolvePort(), queue, e);
            return MessagePullResult.fail("MQ 连接失败，请检查配置");
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        return MessagePullResult.of(items, items.size() == limit, elapsed);
    }

    /**
     * Open a fresh connection + channel, then prove the session works before
     * handing it to the caller.
     */
    private MessageConnectionResources open(MessageConnectionParam param) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(param.getHost());
        factory.setPort(param.resolvePort());
        factory.setVirtualHost(param.resolveVirtualHost());
        factory.setUsername(param.getUsername());
        factory.setPassword(param.getPassword());
        factory.setConnectionTimeout(param.resolveConnectionTimeoutMs());
        if (param.isSslEnabled()) {
            try {
                factory.useSslProtocol();
            } catch (Exception e) {
                // Caller-fixable: the JVM lacks a usable TLS setup. No cause is
                // attached so this surfaces as an exception, not a result.
                LOG.warn("初始化 TLS 失败", e);
                throw new MessageException("启用 TLS 失败，请检查配置");
            }
        }

        com.rabbitmq.client.Connection connection = null;
        Channel channel = null;
        try {
            connection = factory.newConnection("seatunnel-web-messaging");
            channel = connection.createChannel();
            RabbitConnectionResources resources =
                    new RabbitConnectionResources(connection, channel, param);
            resources.verify();
            return resources;
        } catch (Exception e) {
            // Clean up the partial open; otherwise a failed verify leaks a live
            // connection until the broker's heartbeat timeout kills it.
            closeQuietly(channel, connection);
            if (e instanceof MessageException) {
                throw (MessageException) e;
            }
            LOG.warn("建立 RabbitMQ 连接失败, host={}:{}, vhost={}",
                    param.getHost(), param.resolvePort(), param.resolveVirtualHost(), e);
            throw new MessageException("MQ 连接失败，请检查配置", e);
        }
    }

    private void closeQuietly(Channel channel, com.rabbitmq.client.Connection connection) {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception ignored) {
            // best effort during failure cleanup
        }
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception ignored) {
            // best effort during failure cleanup
        }
    }

    /**
     * Build the AMQP properties for a publish.
     *
     * <p>Returns the concrete {@link AMQP.BasicProperties} rather than the
     * {@code com.rabbitmq.client.BasicProperties} interface, because the
     * {@code basicPublish} overload requires the concrete type.</p>
     */
    private AMQP.BasicProperties buildProperties(MessagePushCommand command) {
        Map<String, Object> headers = command.getHeaders();
        boolean hasHeaders = headers != null && !headers.isEmpty();
        if (!command.isPersistent() && !hasHeaders) {
            return new AMQP.BasicProperties.Builder()
                    .contentType(CONTENT_TYPE_JSON)
                    .deliveryMode(1)
                    .build();
        }
        AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties.Builder()
                .contentType(CONTENT_TYPE_JSON)
                // deliveryMode 2 = persistent (decision: `persistent` defaults true)
                .deliveryMode(command.isPersistent() ? 2 : 1);
        if (hasHeaders) {
            builder.headers(new LinkedHashMap<>(headers));
        }
        return builder.build();
    }

    /**
     * Convert a broker response into a contract-layer item.
     *
     * <p>
     * A body that is not valid JSON is <b>not</b> dropped: {@code parseable} is
     * set to {@code false} and the original text is returned in
     * {@code rawBody}, so a caller can still see what was in the queue (design
     * decision D3). This matters because the queue is a shared resource that
     * other producers may also write to.
     * </p>
     */
    private MessagePullItem toItem(GetResponse response) {
        byte[] bodyBytes = response.getBody();
        String text = bodyBytes == null ? "" : new String(bodyBytes, StandardCharsets.UTF_8);

        Object parsed = null;
        boolean parseable = false;
        try {
            parsed = OBJECT_MAPPER.readTree(text);
            parseable = true;
        } catch (Exception e) {
            LOG.debug("队列中的消息不是合法 JSON，降级为 rawBody, queue 中可能存在其他生产者的消息");
        }

        AMQP.BasicProperties props = response.getProps();
        Map<String, Object> headers = props == null ? null : props.getHeaders();

        return MessagePullItem.builder()
                .deliveryTag(response.getEnvelope().getDeliveryTag())
                .body(parsed)
                .rawBody(parseable ? null : text)
                .parseable(parseable)
                .headers(headers)
                .contentType(props == null ? null : props.getContentType())
                .build();
    }

    /**
     * Decide whether a failed attempt should surface as an exception rather
     * than a failure result.
     *
     * <p>
     * {@link MessageException} carries two very different kinds of problem:
     * </p>
     * <ul>
     *   <li><b>No cause</b> — raised by this class's own validation or by
     *       {@code open()}'s argument checks (e.g. "启用 TLS 失败"). The caller
     *       must change the request; retrying the same input is pointless.</li>
     *   <li><b>With a cause</b> — wraps a transport failure such as
     *       {@code Connection refused}. This is an expected operational
     *       outcome, so it is reported as a failure result instead.</li>
     * </ul>
     *
     * <p>
     * Both kinds are safe to surface: their messages are authored here and
     * contain no host or credentials.
     * </p>
     */
    private boolean isCallerFixable(Exception e) {
        return e instanceof MessageException && e.getCause() == null;
    }

    /**
     * Translate an AMQP failure into caller-safe text.
     *
     * <p>
     * Full detail stays in the server log (decision C2); the caller receives a
     * message that reveals no internal topology.
     * </p>
     */
    private String mapIoFailure(Exception e) {
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor instanceof com.rabbitmq.client.ShutdownSignalException) {
                Object reason = ((com.rabbitmq.client.ShutdownSignalException) cursor).getReason();
                if (reason instanceof com.rabbitmq.client.AMQP.Channel.Close) {
                    int code = ((com.rabbitmq.client.AMQP.Channel.Close) reason).getReplyCode();
                    if (code == 404) {
                        return "目标队列不存在";
                    }
                    if (code == 403) {
                        return "没有操作该队列的权限";
                    }
                    if (code == 406) {
                        return "队列声明与既有属性冲突";
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return "MQ 连接失败，请检查配置";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String typeName(Object payload) {
        return payload == null ? "null" : payload.getClass().getSimpleName();
    }
}
