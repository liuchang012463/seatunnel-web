package org.apache.seatunnel.web.api.controller.message;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;

/**
 * Request body for {@code POST /api/v1/message/pull}.
 */
@Data
public class MessagePullRequest {

    /**
     * Source broker connection. Full credentials are supplied per request
     * (design decision A3).
     */
    @NotNull(message = "connection 不能为空")
    @Valid
    private MessageConnectionParam connection;

    /**
     * Broker type resolved against the SPI registry.
     * Defaults to {@code RABBITMQ} when omitted.
     */
    private String clientType;

    /**
     * Queue to read from.
     */
    @NotBlank(message = "queue 不能为空")
    private String queue;

    /**
     * Requested batch size. The service truncates this down to
     * {@code seatunnel.message.max-batch-size} (default 100) to bound memory
     * per request (design decision B1).
     */
    private Integer maxMessages;

    /**
     * Deadline for the attempt, in milliseconds. An upper bound on waiting, not
     * a fixed wait — an empty queue returns promptly (decision B3).
     */
    private Long timeoutMs;

    public String resolveClientType() {
        return clientType == null || clientType.isBlank() ? "RABBITMQ" : clientType.trim();
    }
}
