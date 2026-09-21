package org.apache.seatunnel.plugin.messaging.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;
import org.apache.seatunnel.plugin.messaging.api.MessageException;
import org.apache.seatunnel.plugin.messaging.api.MessagePullCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePullResult;
import org.apache.seatunnel.plugin.messaging.api.MessagePushCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePushResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for {@link RabbitMessageClient} that need <b>no broker</b>.
 *
 * <p>
 * Every case below fails before any network work happens — argument validation,
 * target resolution, and error sanitization. That matters because the sandbox
 * has no RabbitMQ, and a test that silently skipped would give false
 * confidence.
 * </p>
 *
 * <p>
 * The unreachable-broker case uses a deliberately invalid port so the failure
 * is produced by the connection layer rather than by an assertion.
 * </p>
 */
class RabbitMessageClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RabbitMessageClient client = new RabbitMessageClient();

    /**
     * Points at an address that cannot be reached, so any code path that
     * attempts I/O fails fast and predictably.
     */
    private MessageConnectionParam unreachable() {
        MessageConnectionParam param = new MessageConnectionParam();
        param.setHost("127.0.0.1");
        // Port 1 is reserved and never listening.
        param.setPort(1);
        param.setUsername("guest");
        param.setPassword("guest");
        param.setConnectionTimeoutMs(1000);
        return param;
    }

    private MessageConnectionParam valid() {
        MessageConnectionParam param = new MessageConnectionParam();
        param.setHost("127.0.0.1");
        param.setPort(5672);
        param.setUsername("guest");
        param.setPassword("guest");
        return param;
    }

    // -------------------- argument validation --------------------

    @Test
    void pushShouldRejectNullConnection() {
        MessagePushCommand command = MessagePushCommand.builder().queue("q").payload(payload()).build();
        assertThrows(MessageException.class, () -> client.push(null, command));
    }

    @Test
    void pushShouldRejectNullCommand() {
        assertThrows(MessageException.class, () -> client.push(valid(), null));
    }

    @Test
    void pullShouldRejectNullConnection() {
        MessagePullCommand command = MessagePullCommand.builder().queue("q").build();
        assertThrows(MessageException.class, () -> client.pull(null, command));
    }

    @Test
    void pullShouldRejectNullCommand() {
        assertThrows(MessageException.class, () -> client.pull(valid(), null));
    }

    // -------------------- target resolution (no I/O) --------------------

    @Test
    void pushShouldFailWhenExchangeEmptyAndQueueMissing() {
        MessagePushCommand command = MessagePushCommand.builder()
                .exchange("")
                .payload(payload())
                .build();

        MessagePushResult result = client.push(valid(), command);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("queue"),
                "缺少 queue 时错误信息应指明该字段，实际: " + result.getMessage());
    }

    @Test
    void openShouldRejectUnreachableBrokerWithoutLeakingHost() {
        MessagePushCommand command = MessagePushCommand.builder()
                .queue("some.queue")
                .payload(payload())
                .build();

        MessagePushResult result = client.push(unreachable(), command);

        assertFalse(result.isSuccess(), "不可达的 broker 应返回失败而非抛出");
        assertNotNull(result.getMessage());
        // Sanitization (decision C2): the caller must not learn the target host.
        assertFalse(result.getMessage().contains("127.0.0.1"),
                "错误信息泄露了目标主机，实际: " + result.getMessage());
        assertFalse(result.getMessage().contains("guest"),
                "错误信息泄露了凭据，实际: " + result.getMessage());
    }

    @Test
    void pullShouldFailOnUnreachableBrokerWithSanitizedMessage() {
        MessagePullCommand command = MessagePullCommand.builder()
                .queue("some.queue")
                .maxMessages(5)
                .timeoutMs(500)
                .build();

        MessagePullResult result = client.pull(unreachable(), command);

        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
        assertFalse(result.getMessage().contains("127.0.0.1"));
        // A failed pull must still carry a usable (non-null) list.
        assertNotNull(result.getMessages());
        assertEquals(0, result.getReturned());
    }

    // -------------------- pull validation --------------------

    @Test
    void pullShouldFailForBlankQueue() {
        MessagePullCommand command = MessagePullCommand.builder()
                .queue("   ")
                .maxMessages(5)
                .build();

        MessagePullResult result = client.pull(valid(), command);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("queue"));
    }

    @Test
    void pullShouldFailForNonPositiveMaxMessages() {
        MessagePullCommand command = MessagePullCommand.builder()
                .queue("q")
                .maxMessages(0)
                .build();

        MessagePullResult result = client.pull(valid(), command);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("maxMessages"));
    }

    // -------------------- result object contracts --------------------

    @Test
    void pushResultFactoriesShouldBehave() {
        MessagePushResult ok = MessagePushResult.success("", "order.sync", 42);
        assertTrue(ok.isSuccess());
        assertEquals("order.sync", ok.getRoutingKey());
        assertEquals(42, ok.getBodyBytes());

        MessagePushResult bad = MessagePushResult.fail("boom");
        assertFalse(bad.isSuccess());
        assertEquals("boom", bad.getMessage());
    }

    @Test
    void pullResultOfShouldComputeDerivedFields() {
        MessagePullResult empty = MessagePullResult.of(java.util.List.of(), false, 7L);
        assertTrue(empty.isSuccess(), "空队列是正常结果，不是错误");
        assertEquals(0, empty.getReturned());
        assertFalse(empty.isTruncated());
        assertEquals(7L, empty.getElapsedMs());
        assertNotNull(empty.getMessages());
    }

    /**
     * A caller-supplied payload must survive as valid JSON without a fixed
     * schema. This exercises the Jackson tree round-trip the push path relies
     * on, minus the transport.
     */
    @Test
    void arbitraryJsonPayloadShouldSerializeWithoutSchema() throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("orderId", "A001");
        node.put("amount", 100);
        node.putObject("extra").put("nested", true);
        node.putArray("tags").add("urgent");

        byte[] bytes = MAPPER.writeValueAsBytes(node);

        assertTrue(bytes.length > 0);
        assertEquals(node, MAPPER.readTree(bytes), "任意结构的 JSON 应能无损往返");
    }

    @Test
    void headersShouldBeAcceptedWhenProvided() {
        MessagePushCommand command = MessagePushCommand.builder()
                .queue("q")
                .payload(payload())
                .headers(Map.of("source", "erp"))
                .build();

        assertEquals("erp", command.getHeaders().get("source"));
        assertTrue(command.isPersistent(), "persistent 应默认 true");
    }

    private ObjectNode payload() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("k", "v");
        return node;
    }
}
