package org.apache.seatunnel.web.api.fileresource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.messaging.api.MessageClient;
import org.apache.seatunnel.plugin.messaging.api.MessagePushCommand;
import org.apache.seatunnel.plugin.messaging.api.MessagePushResult;
import org.apache.seatunnel.web.api.message.MessageProperties;
import org.apache.seatunnel.web.api.message.plugin.MessagePluginManager;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Best-effort RabbitMQ publisher for file-resource upload events.
 *
 * <p>Failures are logged only — callers must not treat MQ delivery as part of
 * the upload transaction.</p>
 */
@Slf4j
@Component
public class FileResourceMqNotifier {

    private static final String EVENT_TYPE = "FILE_UPLOADED";
    private static final String SOURCE = "FILE_RESOURCE";
    private static final String CLIENT_TYPE = "RABBITMQ";

    @Resource
    private FileMqProperties fileMqProperties;

    @Resource
    private MessageProperties messageProperties;

    @Resource
    private MessagePluginManager messagePluginManager;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * Publish one metadata message for a successfully stored file resource.
     *
     * @param resource       persisted resource row
     * @param uploadRecordId optional batch upload record id; may be null
     */
    public void notifyUploaded(FileResource resource, Long uploadRecordId) {
        if (resource == null) {
            return;
        }
        try {
            if (!fileMqProperties.isEnabled()) {
                log.debug("Skip file-resource MQ notify: seatunnel.web.file-mq.enabled=false");
                return;
            }
            if (!fileMqProperties.isDestinationConfigured()) {
                log.warn("Skip file-resource MQ notify: exchange/routing-key not configured, "
                        + "resourceId={}, logicalPath={}",
                        resource.getId(), resource.getLogicalPath());
                return;
            }
            if (!messageProperties.isBrokerConfigured()) {
                log.warn("Skip file-resource MQ notify: seatunnel.message.broker not configured, "
                        + "resourceId={}", resource.getId());
                return;
            }

            MessageClient client = messagePluginManager.getClient(CLIENT_TYPE);
            if (client == null) {
                log.warn("Skip file-resource MQ notify: messaging client {} not available, resourceId={}",
                        CLIENT_TYPE, resource.getId());
                return;
            }

            ObjectNode payload = buildPayload(resource, uploadRecordId);
            String exchange = fileMqProperties.getExchange() == null
                    ? "" : fileMqProperties.getExchange().trim();
            String routingKey = fileMqProperties.getRoutingKey().trim();
            // Default exchange: queue name is the routing key.
            MessagePushCommand.MessagePushCommandBuilder commandBuilder = MessagePushCommand.builder()
                    .exchange(exchange)
                    .routingKey(routingKey)
                    .payload(payload)
                    .persistent(true);
            if (exchange.isEmpty()) {
                commandBuilder.queue(routingKey);
            }
            MessagePushCommand command = commandBuilder.build();

            MessagePushResult result = client.push(messageProperties.toConnectionParam(), command);
            if (result == null || !result.isSuccess()) {
                log.warn("File-resource MQ notify failed (best-effort), resourceId={}, reason={}",
                        resource.getId(),
                        result == null ? "null result" : StringUtils.defaultIfBlank(
                                result.getMessage(), "unknown"));
            }
        } catch (Exception e) {
            log.warn("File-resource MQ notify failed (best-effort), resourceId={}, logicalPath={}",
                    resource.getId(), resource.getLogicalPath(), e);
        }
    }

    ObjectNode buildPayload(FileResource resource, Long uploadRecordId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventType", EVENT_TYPE);
        node.put("source", SOURCE);
        node.put("eventId", UUID.randomUUID().toString());
        putText(node, "bucket", resource.getBucket());
        putText(node, "objectKey", resource.getObjectKey());
        putText(node, "logicalPath", resource.getLogicalPath());
        putText(node, "name", resource.getName());
        if (resource.getSize() != null) {
            node.put("size", resource.getSize());
        } else {
            node.putNull("size");
        }
        node.put("uploadedAt", Instant.now().toString());
        putText(node, "contentType", resource.getContentType());
        putText(node, "etag", resource.getEtag());
        if (resource.getOwnerId() != null) {
            node.put("ownerId", resource.getOwnerId());
        } else {
            node.putNull("ownerId");
        }
        if (resource.getId() != null) {
            node.put("resourceId", resource.getId());
        } else {
            node.putNull("resourceId");
        }
        putText(node, "providerType", resource.getProviderType());
        if (uploadRecordId != null) {
            node.put("uploadRecordId", uploadRecordId);
        } else {
            node.putNull("uploadRecordId");
        }
        return node;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
