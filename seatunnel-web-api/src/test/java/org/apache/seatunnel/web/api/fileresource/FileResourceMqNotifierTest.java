package org.apache.seatunnel.web.api.fileresource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.plugin.messaging.api.MessageClient;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;
import org.apache.seatunnel.plugin.messaging.api.MessagePushCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePushResult;
import org.apache.seatunnel.web.api.message.MessageProperties;
import org.apache.seatunnel.web.api.message.plugin.MessagePluginManager;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileResourceMqNotifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FileMqProperties fileMqProperties;
    private MessageProperties messageProperties;
    private MessagePluginManager pluginManager;
    private RecordingClient client;
    private FileResourceMqNotifier notifier;

    @BeforeEach
    void setUp() {
        fileMqProperties = new FileMqProperties();
        fileMqProperties.setEnabled(true);
        fileMqProperties.setExchange("file.events");
        fileMqProperties.setRoutingKey("file.uploaded");

        messageProperties = new MessageProperties();
        messageProperties.getBroker().setHost("127.0.0.1");
        messageProperties.getBroker().setUsername("guest");
        messageProperties.getBroker().setPassword("guest");

        client = new RecordingClient();
        pluginManager = mock(MessagePluginManager.class);
        when(pluginManager.getClient("RABBITMQ")).thenReturn(client);

        notifier = new FileResourceMqNotifier();
        ReflectionTestUtils.setField(notifier, "fileMqProperties", fileMqProperties);
        ReflectionTestUtils.setField(notifier, "messageProperties", messageProperties);
        ReflectionTestUtils.setField(notifier, "messagePluginManager", pluginManager);
        ReflectionTestUtils.setField(notifier, "objectMapper", objectMapper);
    }

    @Test
    void publishesOneMessagePerFileWithExpectedDestinationAndPayload() {
        FileResource first = sampleResource(11L, "docs/a.txt");
        FileResource second = sampleResource(12L, "docs/b.txt");

        notifier.notifyUploaded(first, 100L);
        notifier.notifyUploaded(second, 100L);

        assertEquals(2, client.commands.size());
        MessagePushCommand cmd1 = client.commands.get(0);
        MessagePushCommand cmd2 = client.commands.get(1);
        assertEquals("file.events", cmd1.getExchange());
        assertEquals("file.uploaded", cmd1.getRoutingKey());
        assertTrue(cmd1.isPersistent());

        JsonNode p1 = objectMapper.valueToTree(cmd1.getPayload());
        JsonNode p2 = objectMapper.valueToTree(cmd2.getPayload());
        assertEquals("FILE_UPLOADED", p1.path("eventType").asText());
        assertEquals("FILE_RESOURCE", p1.path("source").asText());
        assertEquals("docs/a.txt", p1.path("logicalPath").asText());
        assertEquals("docs/b.txt", p2.path("logicalPath").asText());
        assertEquals(11L, p1.path("resourceId").asLong());
        assertEquals(12L, p2.path("resourceId").asLong());
        assertEquals(100L, p1.path("uploadRecordId").asLong());
        assertEquals("demo-bucket", p1.path("bucket").asText());
        assertEquals("S3_COMPATIBLE", p1.path("providerType").asText());
        assertNotEquals(p1.path("eventId").asText(), p2.path("eventId").asText());
        assertFalse(p1.path("eventId").asText().isBlank());
    }

    @Test
    void mqFailureDoesNotThrow() {
        client.pushResult = MessagePushResult.fail("broker down");
        assertDoesNotThrow(() -> notifier.notifyUploaded(sampleResource(1L, "x.csv"), 9L));
        assertEquals(1, client.commands.size());

        client.pushThrows = new RuntimeException("connection reset");
        assertDoesNotThrow(() -> notifier.notifyUploaded(sampleResource(2L, "y.csv"), 9L));
        assertEquals(2, client.commands.size());
    }

    @Test
    void skipsWhenDisabledOrDestinationMissing() {
        fileMqProperties.setEnabled(false);
        notifier.notifyUploaded(sampleResource(1L, "a.txt"), 1L);
        assertTrue(client.commands.isEmpty());
        verify(pluginManager, never()).getClient(any());

        fileMqProperties.setEnabled(true);
        fileMqProperties.setExchange("file.events");
        fileMqProperties.setRoutingKey("");
        notifier.notifyUploaded(sampleResource(3L, "c.txt"), 1L);
        assertTrue(client.commands.isEmpty());
        verify(pluginManager, never()).getClient(eq("RABBITMQ"));
    }

    @Test
    void emptyExchangePublishesToDefaultExchangeViaQueueName() {
        fileMqProperties.setExchange("");
        fileMqProperties.setRoutingKey("order.sync");

        notifier.notifyUploaded(sampleResource(5L, "z.txt"), 42L);

        assertEquals(1, client.commands.size());
        MessagePushCommand cmd = client.commands.get(0);
        assertEquals("", cmd.getExchange());
        assertEquals("order.sync", cmd.getRoutingKey());
        assertEquals("order.sync", cmd.getQueue());
    }

    private static FileResource sampleResource(Long id, String logicalPath) {
        FileResource resource = new FileResource();
        resource.setId(id);
        resource.setOwnerId(7);
        resource.setProviderType("S3_COMPATIBLE");
        resource.setBucket("demo-bucket");
        resource.setObjectKey("seatunnel-web-resource/7/" + logicalPath);
        resource.setLogicalPath(logicalPath);
        resource.setName(logicalPath.substring(logicalPath.lastIndexOf('/') + 1));
        resource.setSize(42L);
        resource.setContentType("text/plain");
        resource.setEtag("etag-1");
        resource.setStatus("ACTIVE");
        return resource;
    }

    private static final class RecordingClient implements MessageClient {

        private final List<MessagePushCommand> commands = new ArrayList<>();
        private MessagePushResult pushResult;
        private RuntimeException pushThrows;

        @Override
        public MessagePushResult push(MessageConnectionParam param, MessagePushCommand command) {
            commands.add(command);
            if (pushThrows != null) {
                throw pushThrows;
            }
            if (pushResult != null) {
                return pushResult;
            }
            return MessagePushResult.success(
                    command.getExchange() == null ? "" : command.getExchange(),
                    command.getRoutingKey(),
                    16);
        }

        @Override
        public org.apache.seatunnel.plugin.messaging.api.MessagePullResult pull(
                MessageConnectionParam param,
                org.apache.seatunnel.plugin.messaging.api.MessagePullCommand command) {
            throw new UnsupportedOperationException();
        }
    }
}
