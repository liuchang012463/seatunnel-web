package org.apache.seatunnel.plugin.messaging.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a pull attempt.
 *
 * <p>An empty queue yields {@code messages = []} and {@code returned = 0} with
 * {@code success = true} — that is a normal outcome, not an error.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MessagePullResult {

    /**
     * Whether the pull attempt itself completed. Note this says nothing about
     * whether any message was found.
     */
    private boolean success;

    /**
     * Messages retrieved, in delivery order. Never {@code null}.
     */
    @Builder.Default
    private List<MessagePullItem> messages = new ArrayList<>();

    /**
     * Convenience count, equal to {@code messages.size()}.
     */
    private int returned;

    /**
     * {@code true} when the batch hit its size limit, meaning the queue may
     * still hold more messages and the caller should pull again.
     */
    private boolean truncated;

    /**
     * Wall-clock duration of the attempt.
     */
    private long elapsedMs;

    /**
     * Human-readable detail; already sanitized for external consumption.
     */
    private String message;

    public static MessagePullResult of(List<MessagePullItem> items, boolean truncated, long elapsedMs) {
        List<MessagePullItem> safe = items == null ? new ArrayList<>() : items;
        return MessagePullResult.builder()
                .success(true)
                .messages(safe)
                .returned(safe.size())
                .truncated(truncated)
                .elapsedMs(elapsedMs)
                .build();
    }

    public static MessagePullResult fail(String message) {
        return MessagePullResult.builder()
                .success(false)
                .messages(new ArrayList<>())
                .returned(0)
                .message(message)
                .build();
    }
}
