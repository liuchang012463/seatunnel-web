package org.apache.seatunnel.plugin.messaging.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * What to push and how, independent of the connection it travels over.
 *
 * <p>
 * Mirrors the {@code AlarmInfo} split (params = how to send, payload = what to
 * send): {@link MessageConnectionParam} says <i>where</i>, this says
 * <i>what</i>.
 * </p>
 *
 * <p>
 * <b>Payload typing.</b> {@link #payload} is an {@code Object} so the contract
 * layer does not depend on a JSON library. The API layer parses the incoming
 * request into Jackson's {@code JsonNode} tree and passes it through unchanged;
 * {@code RabbitMessageClient} serializes it with its own {@code ObjectMapper}.
 * Typing it as a concrete DTO is forbidden — the message structure is
 * caller-defined and unstable.
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MessagePushCommand {

    /**
     * Exchange to publish to.
     *
     * <p>Empty or {@code null} means "publish straight to {@link #queue} via
     * the default exchange" (design decision D4). A non-empty value routes
     * through the named exchange using {@link #routingKey}.</p>
     */
    private String exchange;

    /**
     * Target queue. Required when {@link #exchange} is empty, because it is
     * then used as the routing key against the default exchange.
     */
    private String queue;

    /**
     * Routing key used when {@link #exchange} is set.
     * Falls back to {@link #queue} when blank.
     */
    private String routingKey;

    /**
     * Caller-defined JSON payload. Serialized by the implementation; never
     * mapped onto a fixed schema.
     */
    private Object payload;

    /**
     * Whether the broker should persist the message to disk.
     * {@code true} maps to delivery mode 2 (persistent).
     */
    @Builder.Default
    private boolean persistent = true;

    /**
     * Optional AMQP headers passed through verbatim.
     */
    private Map<String, Object> headers;
}
