package org.apache.seatunnel.plugin.messaging.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of a single push attempt.
 *
 * <p>Modelled on {@code AlarmResult}: implementations return a result object
 * for expected failures (unreachable broker, missing queue) and reserve
 * exceptions for programming errors. The API layer turns a failed result into a
 * sanitized error response.</p>
 *
 * <p>
 * Because publisher confirms are <b>not</b> awaited (design decision E), a
 * success here means only "handed to the broker on an open channel" — not
 * "persisted". This matches the accepted at-most-once stance.
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MessagePushResult {

    /**
     * Whether the broker accepted the publish call.
     */
    private boolean success;

    /**
     * Exchange actually used; empty string for the default exchange.
     */
    private String exchange;

    /**
     * Routing key actually used (after falling back to the queue name).
     */
    private String routingKey;

    /**
     * Number of bytes handed to the broker. Useful for correlating with the
     * 1 MB limit during troubleshooting.
     */
    private int bodyBytes;

    /**
     * Human-readable detail. <b>Must already be sanitized</b> — no host names,
     * no credentials, no internal topology. Full detail belongs in the server
     * log only (design decision C2).
     */
    private String message;

    public static MessagePushResult success(String exchange, String routingKey, int bodyBytes) {
        return MessagePushResult.builder()
                .success(true)
                .exchange(exchange)
                .routingKey(routingKey)
                .bodyBytes(bodyBytes)
                .build();
    }

    public static MessagePushResult fail(String message) {
        return MessagePushResult.builder()
                .success(false)
                .message(message)
                .build();
    }
}
