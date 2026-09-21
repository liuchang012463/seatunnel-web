package org.apache.seatunnel.plugin.messaging.api;

/**
 * Connection resources owned by a single push/pull attempt.
 *
 * <p>
 * Per the design decision (C1) each request opens its own connection and
 * releases it before returning. Abstracting the resource handle behind this
 * interface keeps {@link MessageClient} implementations free of any assumption
 * about connection reuse: today every call constructs a fresh
 * implementation; later a pooled variant can be introduced without touching
 * business logic.
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
     * <p>Opening a socket is not proof of a working broker session, so the
     * reachability check (design decision A4) performs a minimal round-trip
     * through this method. Implementations should throw
     * {@link MessageException} when the check fails.</p>
     */
    void verify();

    /**
     * Release all underlying resources.
     *
     * <p>Must be idempotent and must swallow individual release failures so
     * that one failed close does not mask another.</p>
     */
    @Override
    void close();
}
