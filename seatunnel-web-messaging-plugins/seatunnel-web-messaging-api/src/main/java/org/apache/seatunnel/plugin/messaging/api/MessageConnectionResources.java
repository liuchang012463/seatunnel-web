package org.apache.seatunnel.plugin.messaging.api;

/**
 * Connection resources owned by a single push/pull attempt.
 *
 * <p>
 * Implementations may open a fresh transport or borrow from a shared pool.
 * The RabbitMQ plugin shares one AMQP connection per broker fingerprint and
 * lends a channel per call; {@link #close()} releases only what that attempt
 * owns (typically the channel).
 * </p>
 *
 * <p>
 * Intended usage is always {@code try-with-resources}:
 * </p>
 *
 * <pre>{@code
 * try (MessageConnectionResources res = open(param)) {
 *     Channel channel = res.channel();
 *     ...
 * }
 * }</pre>
 *
 * <p>
 * <b>{@link #close()} must be idempotent</b> and must not throw. It is invoked
 * from a {@code finally} block and must release every underlying resource even
 * if one of those releases fails.
 * </p>
 */
public interface MessageConnectionResources extends AutoCloseable {

    /**
     * The live transport handle for this attempt.
     *
     * <p>The concrete type is intentionally unspecified ({@code Object}) so
     * that the contract layer stays free of any third-party broker dependency;
     * implementations cast to their own handle type.</p>
     *
     * @return the transport handle, never {@code null} for a successfully
     *         opened resource object
     */
    Object channel();

    /**
     * Verify the connection is actually usable.
     *
     * <p>Pooled implementations may no-op here when the shared session was
     * already probed at connect time. Opening a socket alone is not proof of a
     * working broker session, so a first-time connect still performs a minimal
     * round-trip. Implementations should throw {@link MessageException} when
     * the check fails.</p>
     */
    void verify();

    /**
     * Release resources owned by this attempt (not necessarily the shared
     * connection underneath).
     *
     * <p>Must be idempotent and must swallow individual release failures so
     * that one failed close does not mask another.</p>
     */
    @Override
    void close();
}
