package org.apache.seatunnel.plugin.messaging.rabbitmq;

import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;
import org.apache.seatunnel.plugin.messaging.api.MessagePushCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePushResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manager behaviour that can be asserted without a live broker.
 */
class RabbitConnectionManagerTest {

    private final RabbitConnectionManager manager = new RabbitConnectionManager();

    private final RabbitMessageClient client = new RabbitMessageClient(manager);

    @AfterEach
    void tearDown() {
        manager.clear();
    }

    @Test
    void fingerprintIsStableForSameBroker() {
        MessageConnectionParam a = param("127.0.0.1", 5672, "guest", "guest");
        MessageConnectionParam b = param("127.0.0.1", 5672, "guest", "guest");
        assertEquals(RabbitConnectionManager.fingerprint(a), RabbitConnectionManager.fingerprint(b));
    }

    @Test
    void fingerprintChangesWhenPasswordChanges() {
        MessageConnectionParam a = param("127.0.0.1", 5672, "guest", "guest");
        MessageConnectionParam b = param("127.0.0.1", 5672, "guest", "other");
        assertFalse(RabbitConnectionManager.fingerprint(a)
                .equals(RabbitConnectionManager.fingerprint(b)));
    }

    @Test
    void failedConnectDoesNotLeaveCachedConnection() {
        MessageConnectionParam unreachable = param("127.0.0.1", 1, "guest", "guest");
        unreachable.setConnectionTimeoutMs(500);

        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("x", 1);
        MessagePushResult result = client.push(unreachable, MessagePushCommand.builder()
                .queue("q")
                .payload(payload)
                .build());

        assertFalse(result.isSuccess());
        assertEquals(0, manager.cachedConnectionCount(),
                "失败的握手不得把坏连接留在缓存里");
    }

    @Test
    void clearEmptiesCacheAfterFailedAttempts() {
        MessageConnectionParam unreachable = param("127.0.0.1", 1, "guest", "guest");
        unreachable.setConnectionTimeoutMs(500);
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("x", 1);

        client.push(unreachable, MessagePushCommand.builder().queue("q").payload(payload).build());
        manager.clear();
        assertEquals(0, manager.cachedConnectionCount());
        assertTrue(true);
    }

    private static MessageConnectionParam param(String host, int port, String user, String pass) {
        MessageConnectionParam p = new MessageConnectionParam();
        p.setHost(host);
        p.setPort(port);
        p.setUsername(user);
        p.setPassword(pass);
        return p;
    }
}
