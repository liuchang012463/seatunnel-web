package org.apache.seatunnel.plugin.messaging.rabbitmq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;
import org.apache.seatunnel.plugin.messaging.api.MessagePullCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePullItem;
import org.apache.seatunnel.plugin.messaging.api.MessagePullResult;
import org.apache.seatunnel.plugin.messaging.api.MessagePushCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePushResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end acceptance against a <b>real</b> RabbitMQ broker.
 *
 * <p>
 * Disabled by default — this suite is opt-in so it can never turn a normal
 * build red on a machine without a broker:
 * </p>
 *
 * <pre>
 * ./.mvn/mvn21.sh -pl seatunnel-web-messaging-plugins/seatunnel-web-messaging-rabbitmq \
 *     test -DskipTests=false -Dtest=RabbitMessageClientLiveIT -DfailIfNoTests=false \
 *     -Dmessaging.it.enabled=true \
 *     -Dmessaging.it.user=&lt;user&gt; -Dmessaging.it.pass=&lt;password&gt;
 * </pre>
 *
 * <p>
 * Supported overrides (all optional): {@code messaging.it.host} (127.0.0.1),
 * {@code messaging.it.port} (5672), {@code messaging.it.vhost} (/),
 * {@code messaging.it.queue-prefix} (seatunnel.it).
 * </p>
 *
 * <p>
 * Every case runs on its own randomly named queue and deletes it afterwards,
 * so a run never depends on — or pollutes — pre-existing broker state.
 * </p>
 */
@EnabledIfSystemProperty(named = "messaging.it.enabled", matches = "true")
class RabbitMessageClientLiveIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RabbitMessageClient client = new RabbitMessageClient();

    private String queue;

    @BeforeEach
    void createQueue() throws Exception {
        queue = System.getProperty("messaging.it.queue-prefix", "seatunnel.it")
                + "." + UUID.randomUUID().toString().substring(0, 8);
        try (Connection connection = rawFactory().newConnection("seatunnel-web-it-setup");
                Channel channel = connection.createChannel()) {
            // Durable on purpose: RabbitMQ 4.x rejects transient non-exclusive
            // queues (reply-code 541, "transient_nonexcl_queues is deprecated")
            // unless the broker is explicitly reconfigured to allow them.
            channel.queueDeclare(queue, true, false, false, null);
        }
    }

    @AfterEach
    void deleteQueue() {
        try (Connection connection = rawFactory().newConnection("seatunnel-web-it-cleanup");
                Channel channel = connection.createChannel()) {
            channel.queueDelete(queue);
        } catch (Exception e) {
            // Cleanup is best-effort: a failed delete must not mask the test result.
        }
    }

    // -------------------- happy path --------------------

    @Test
    void pushThenPullRoundTripsArbitraryJson() {
        String payload = "{\"orderId\":\"A001\",\"amount\":12.5,\"note\":\"中文与 emoji 🚀\","
                + "\"items\":[{\"sku\":\"X1\",\"qty\":2},{\"sku\":\"X2\",\"qty\":1}],"
                + "\"nested\":{\"deep\":{\"flag\":true,\"nothing\":null}}}";

        MessagePushResult pushed = client.push(connection(), MessagePushCommand.builder()
                .queue(queue)
                .payload(readTree(payload))
                .build());
        assertTrue(pushed.isSuccess(), "推送应成功: " + pushed.getMessage());
        assertEquals(queue, pushed.getRoutingKey(), "exchange 为空时应直接投递到队列");

        MessagePullResult pulled = client.pull(connection(), MessagePullCommand.builder()
                .queue(queue)
                .maxMessages(10)
                .timeoutMs(5000)
                .build());
        assertTrue(pulled.isSuccess(), "拉取应成功: " + pulled.getMessage());
        assertEquals(1, pulled.getReturned());
        assertFalse(pulled.isTruncated());

        MessagePullItem item = pulled.getMessages().get(0);
        assertTrue(item.isParseable(), "合法 JSON 应标记为可解析");
        assertEquals(readTree(payload), item.getBody(),
                "任意结构 JSON 必须逐字段无损往返，不得被展平或改写");
        assertEquals("application/json;charset=UTF-8", item.getContentType());
    }

    @Test
    void pullFromEmptyQueueReportsZeroMessages() {
        MessagePullResult pulled = client.pull(connection(), MessagePullCommand.builder()
                .queue(queue)
                .maxMessages(5)
                .timeoutMs(2000)
                .build());

        assertTrue(pulled.isSuccess(), "空队列不是错误: " + pulled.getMessage());
        assertEquals(0, pulled.getReturned());
        assertTrue(pulled.getMessages().isEmpty());
    }

    // -------------------- bounds --------------------

    @Test
    void pullHonoursMaxMessagesAndLeavesTheRestBehind() {
        for (int i = 1; i <= 5; i++) {
            MessagePushResult result = client.push(connection(), MessagePushCommand.builder()
                    .queue(queue)
                    .payload(readTree("{\"seq\":" + i + "}"))
                    .build());
            assertTrue(result.isSuccess(), "第 " + i + " 条推送应成功: " + result.getMessage());
        }

        MessagePullResult first = client.pull(connection(), MessagePullCommand.builder()
                .queue(queue)
                .maxMessages(3)
                .timeoutMs(5000)
                .build());
        assertTrue(first.isSuccess(), first.getMessage());
        assertEquals(3, first.getReturned(), "maxMessages 应限制本次返回条数");
        assertTrue(first.isTruncated(), "队列仍有剩余消息时应标记 truncated");

        // The messages that were not pulled must still be there.
        MessagePullResult second = client.pull(connection(), MessagePullCommand.builder()
                .queue(queue)
                .maxMessages(10)
                .timeoutMs(5000)
                .build());
        assertTrue(second.isSuccess(), second.getMessage());
        assertEquals(2, second.getReturned(), "未被拉取的消息必须保留在队列中");
        assertFalse(second.isTruncated());

        MessagePullResult third = client.pull(connection(), MessagePullCommand.builder()
                .queue(queue)
                .maxMessages(10)
                .timeoutMs(2000)
                .build());
        assertEquals(0, third.getReturned(), "拉空后队列应无残留消息");
    }

    // -------------------- failure handling --------------------

    @Test
    void wrongCredentialsFailWithoutLeakingSecrets() {
        MessageConnectionParam bad = connection();
        bad.setPassword("definitely-not-the-password");

        MessagePushResult result = client.push(bad, MessagePushCommand.builder()
                .queue(queue)
                .payload(readTree("{\"a\":1}"))
                .build());

        assertFalse(result.isSuccess(), "错误凭据必须返回失败结果而不是抛出异常");
        assertNotNull(result.getMessage());
        assertFalse(result.getMessage().contains("definitely-not-the-password"),
                "失败原因不得回显密码");
        assertFalse(result.getMessage().contains(bad.getUsername() + ":"),
                "失败原因不得回显用户名密码组合");
    }

    @Test
    void missingQueueFailsWithAnActionableReason() {
        MessagePullResult result = client.pull(connection(), MessagePullCommand.builder()
                .queue("seatunnel.it.does-not-exist." + UUID.randomUUID().toString().substring(0, 8))
                .maxMessages(1)
                .timeoutMs(3000)
                .build());

        assertFalse(result.isSuccess(), "不存在的队列必须返回失败结果");
        assertNotNull(result.getMessage());
        assertFalse(result.getMessage().contains("Exception"),
                "失败原因应是可读中文提示，而不是异常类名");
    }

    // -------------------- non-JSON coexistence --------------------

    @Test
    void nonJsonBodyIsReturnedAsRawTextInsteadOfFailing() throws Exception {
        String raw = "this is not json at all";
        try (Connection connection = rawFactory().newConnection("seatunnel-web-it-raw");
                Channel channel = connection.createChannel()) {
            channel.basicPublish("", queue, null, raw.getBytes(StandardCharsets.UTF_8));
        }

        MessagePullResult pulled = client.pull(connection(), MessagePullCommand.builder()
                .queue(queue)
                .maxMessages(5)
                .timeoutMs(5000)
                .build());

        assertTrue(pulled.isSuccess(), "非 JSON 消息不应让整个拉取失败");
        assertEquals(1, pulled.getReturned());

        MessagePullItem item = pulled.getMessages().get(0);
        assertFalse(item.isParseable(), "非 JSON 消息应标记为不可解析");
        assertEquals(raw, item.getRawBody(), "原始文本必须原样保留，便于调用方自行处理");
    }

    // -------------------- helpers --------------------

    private MessageConnectionParam connection() {
        MessageConnectionParam param = new MessageConnectionParam();
        param.setHost(System.getProperty("messaging.it.host", "127.0.0.1"));
        param.setPort(Integer.getInteger("messaging.it.port", 5672));
        param.setVirtualHost(System.getProperty("messaging.it.vhost", "/"));
        param.setUsername(System.getProperty("messaging.it.user", "guest"));
        param.setPassword(System.getProperty("messaging.it.pass", "guest"));
        return param;
    }

    private ConnectionFactory rawFactory() {
        MessageConnectionParam param = connection();
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(param.getHost());
        factory.setPort(param.resolvePort());
        factory.setVirtualHost(param.resolveVirtualHost());
        factory.setUsername(param.getUsername());
        factory.setPassword(param.getPassword());
        factory.setConnectionTimeout(8000);
        return factory;
    }

    private JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试数据不是合法 JSON", e);
        }
    }
}
