package org.apache.seatunnel.plugin.messaging.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What to pull and how long to wait.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MessagePullCommand {

    /**
     * Source queue. Required.
     */
    private String queue;

    /**
     * How many messages the caller wants. The service truncates this down to
     * the configured {@code seatunnel.message.max-batch-size} (default 100) to
     * bound memory per request (design decision B1).
     */
    @Builder.Default
    private int maxMessages = 10;

    /**
     * Deadline for the whole pull attempt, in milliseconds.
     *
     * <p>This is an upper bound, not a wait: implementations must return as
     * soon as the queue is empty rather than blocking for the full duration
     * (design decision B3).</p>
     */
    @Builder.Default
    private long timeoutMs = 3000L;
}
