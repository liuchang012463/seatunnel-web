package org.apache.seatunnel.web.api.controller.message;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * Request body for {@code POST /api/v1/message/push}.
 *
 * <p>
 * Broker credentials come from {@code seatunnel.message.broker} on the server.
 * The caller only supplies the destination and payload.
 * </p>
 *
 * <p>
 * {@link #message} is typed as {@link JsonNode} on purpose: the payload
 * structure is caller-defined and unstable, so it must never be bound to a
 * fixed DTO. Jackson parses it into a tree and the messaging layer re-serializes
 * that tree when publishing.
 * </p>
 */
@Data
public class MessagePushRequest {

    /**
     * Broker type resolved against the SPI registry.
     * Defaults to {@code RABBITMQ} when omitted.
     */
    private String clientType;

    /**
     * Exchange to publish to. Empty or omitted means "publish straight to
     * {@link #queue} via the default exchange" (decision D4).
     */
    private String exchange;

    /**
     * Target queue. Required when {@link #exchange} is empty.
     */
    private String queue;

    /**
     * Routing key used when {@link #exchange} is set; falls back to
     * {@link #queue}.
     */
    private String routingKey;

    /**
     * Arbitrary JSON payload. Never mapped to a fixed schema.
     */
    @NotNull(message = "message 不能为空")
    private JsonNode message;

    /**
     * Whether the broker should persist the message. Defaults to {@code true}.
     */
    private Boolean persistent;

    /**
     * Optional AMQP headers passed through verbatim.
     */
    private Map<String, Object> headers;

    /**
     * Default broker type when the caller does not specify one.
     *
     * <p>Not annotated with {@code NotBlank} because a blank value is meant to
     * fall back rather than fail.</p>
     */
    public String resolveClientType() {
        return clientType == null || clientType.isBlank() ? "RABBITMQ" : clientType.trim();
    }
}
