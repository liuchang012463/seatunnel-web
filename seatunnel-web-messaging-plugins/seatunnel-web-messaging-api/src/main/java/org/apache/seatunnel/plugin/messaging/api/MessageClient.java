package org.apache.seatunnel.plugin.messaging.api;

/**
 * Message worker: pushes one payload to a broker, or pulls a batch back.
 *
 * <p>
 * Implementations are <b>stateless</b>: all connection state is opened and
 * released inside each call (design decision C1, see
 * {@link MessageConnectionResources}). That mirrors the alarm family, where
 * {@code AlarmChannel} holds no resources between invocations. As a result a
 * single client instance is safe to reuse and share across requests.
 * </p>
 *
 * <h3>Delivery semantics — read before relying on these methods</h3>
 * <p>
 * <b>Push</b> does not wait for a publisher confirm (decision E), so a
 * successful {@link MessagePushResult} means only that the broker accepted the
 * publish call on an open channel — not that the message was persisted.
 * </p>
 * <p>
 * <b>Pull</b> uses auto-ack, so a returned message has already left the queue.
 * If the caller never receives the response, the message is lost
 * (at-most-once). This is an explicitly accepted trade-off (decision B2); see
 * the risk register in {@code docs/rabbitmq-messaging-implementation.md} §9.
 * </p>
 *
 * <h3>Error conventions</h3>
 * <ul>
 *   <li><b>Expected operational failures</b> — unreachable broker, bad
 *       credentials, missing queue — are reported through
 *       {@link MessagePushResult#fail(String)} /
 *       {@link MessagePullResult#fail(String)} and <b>never</b> throw.</li>
 *   <li><b>Programming errors</b> — null arguments, invalid option
 *       combinations — throw {@link MessageException}.</li>
 * </ul>
 * <p>
 * Failure messages must already be sanitized (decision C2): no host names, no
 * credentials, no internal topology. Full detail belongs in the server log.
 * </p>
 */
public interface MessageClient {

    /**
     * Publish one payload.
     *
     * @param param   connection configuration; must not be {@code null}
     * @param command what to send and how; must not be {@code null}
     * @return the outcome; never {@code null}
     * @throws MessageException if an argument is {@code null} or malformed
     */
    MessagePushResult push(MessageConnectionParam param, MessagePushCommand command);

    /**
     * Retrieve up to {@code command.maxMessages} messages.
     *
     * <p>Returns as soon as the queue is drained rather than blocking for the
     * full timeout, so an empty queue yields a fast successful result with
     * {@code returned = 0}.</p>
     *
     * @param param   connection configuration; must not be {@code null}
     * @param command what to pull; must not be {@code null}
     * @return the outcome; never {@code null}
     * @throws MessageException if an argument is {@code null} or malformed
     */
    MessagePullResult pull(MessageConnectionParam param, MessagePullCommand command);
}
