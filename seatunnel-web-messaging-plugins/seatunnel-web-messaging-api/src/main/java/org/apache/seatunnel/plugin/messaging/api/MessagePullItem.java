package org.apache.seatunnel.plugin.messaging.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One message retrieved from a queue.
 *
 * <p>
 * Retrieval is always auto-ack, so by the time a caller sees this object the
 * message has already left the queue. See {@link MessageClient} for the
 * at-most-once semantics this implies.
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MessagePullItem {

    /**
     * Broker delivery tag for this attempt. Diagnostic only — it is not a
     * stable message identifier and cannot be used to ack later.
     */
    private long deliveryTag;

    /**
     * Parsed payload when {@link #parseable} is {@code true}; {@code null}
     * otherwise.
     */
    private Object body;

    /**
     * Raw body text, populated only when parsing failed. Lets the caller see
     * non-JSON messages the queue happens to contain instead of silently
     * dropping them (design decision D3).
     */
    private String rawBody;

    /**
     * Whether {@link #body} holds a successfully parsed JSON tree.
     */
    private boolean parseable;

    /**
     * AMQP headers as returned by the broker, passed through unchanged.
     */
    private java.util.Map<String, Object> headers;

    /**
     * MIME content type recorded by the publisher, if any.
     */
    private String contentType;
}
