package org.apache.seatunnel.plugin.messaging.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionResources;
import org.apache.seatunnel.plugin.messaging.api.MessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ implementation of {@link MessageConnectionResources}: owns one
 * {@link Connection} plus one {@link Channel} for the lifetime of a single
 * push/pull attempt.
 *
 * <p>
 * Per design decision C1 there is <b>no connection pool</b>. Every request
 * performs its own AMQP handshake (roughly 10–50 ms against a healthy broker)
 * and releases it in {@code close()}. Abstracting behind
 * {@link MessageConnectionResources} means a pooled variant can be introduced
 * later without touching {@link RabbitMessageClient}.
 * </p>
 *
 * <p>
 * <b>Package-private on purpose.</b> Nothing outside this plugin should hold a
 * raw RabbitMQ handle; the contract layer deliberately exposes only
 * {@code Object channel()}.
 * </p>
 */
final class RabbitConnectionResources implements MessageConnectionResources {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitConnectionResources.class);

    private final Connection connection;

    private final Channel channel;

    private final MessageConnectionParam param;

    private boolean closed;

    RabbitConnectionResources(Connection connection, Channel channel, MessageConnectionParam param) {
        this.connection = connection;
        this.channel = channel;
        this.param = param;
    }

    @Override
    public Object channel() {
        return channel;
    }

    /**
     * Round-trip check proving the session is actually usable.
     *
     * <p>
     * Opening a TCP socket is not enough to conclude a broker is reachable:
     * authentication, vhost existence and permission are only exercised once a
     * method is issued. {@code channel.isOpen()} alone only reflects local
     * state, so this issues {@code queueDeclarePassive} against a well-known
     * queue name to force a real server call.
     * </p>
     *
     * <p>
     * The probe must not create resources, so it uses
     * {@code queueDeclarePassive} (a read-only check that fails with
     * {@code NOT_FOUND} when the queue is absent) rather than a declaring
     * variant. A "queue does not exist" outcome is deliberately <b>treated as
     * reachable</b>: the broker answered, which is exactly what the
     * reachability check (decision A4) is trying to establish. Whether a
     * specific queue exists is a per-operation concern, not a connectivity one.
     * </p>
     */
    @Override
    public void verify() {
        if (!connection.isOpen() || !channel.isOpen()) {
            // Attach a cause deliberately: RabbitMessageClient treats a
            // MessageException without a cause as a caller-fixable argument
            // error, but a dead socket is an operational failure that must come
            // back as a retryable result instead.
            throw new MessageException("MQ 连接不可用，请检查配置",
                    new java.io.IOException("connection or channel is already closed"));
        }
        try {
            // A passive declare that fails with NOT_FOUND still proves the
            // broker answered us, so only non-NOT_FOUND errors are fatal.
            channel.queueDeclarePassive(RabbitMessageClient.PROBE_QUEUE);
        } catch (java.io.IOException e) {
            if (isChannelLevelError(e)) {
                // 404 NOT_FOUND / 403 ACCESS_REFUSED: the broker responded, so
                // connectivity and authentication are fine.
                return;
            }
            throw new MessageException("MQ 连接不可用，请检查配置", e);
        } catch (Exception e) {
            throw new MessageException("MQ 连接不可用，请检查配置", e);
        }
    }

    /**
     * Detect AMQP channel-level errors that still prove a successful handshake.
     *
     * <p>
     * RabbitMQ signals these as {@link com.rabbitmq.client.ShutdownSignalException}
     * carried inside an {@code IOException}. Both codes below mean the broker
     * processed our request, so they are treated as "reachable".
     * </p>
     */
    private boolean isChannelLevelError(Throwable e) {
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor instanceof com.rabbitmq.client.ShutdownSignalException) {
                com.rabbitmq.client.ShutdownSignalException sse =
                        (com.rabbitmq.client.ShutdownSignalException) cursor;
                Object reason = sse.getReason();
                if (reason instanceof com.rabbitmq.client.AMQP.Connection.Close) {
                    return false;
                }
                if (reason instanceof com.rabbitmq.client.AMQP.Channel.Close) {
                    int replyCode = ((com.rabbitmq.client.AMQP.Channel.Close) reason).getReplyCode();
                    // 404 NOT_FOUND, 403 ACCESS_REFUSED, 405 RESOURCE_LOCKED, 406 PRECONDITION_FAILED
                    return replyCode == 403 || replyCode == 404 || replyCode == 405 || replyCode == 406;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * Release the channel then the connection.
     *
     * <p>
     * Idempotent and non-throwing, because it is called from a {@code finally}
     * block; a failure while closing must never mask the operation's real
     * outcome. Failures are logged, and the channel close is attempted even if
     * the connection close fails.
     * </p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (java.io.IOException | TimeoutException e) {
            LOG.warn("关闭 RabbitMQ Channel 失败, host={}", safeHost(), e);
        } catch (Exception e) {
            LOG.warn("关闭 RabbitMQ Channel 时发生未预期异常, host={}", safeHost(), e);
        }
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception e) {
            LOG.warn("关闭 RabbitMQ Connection 失败, host={}", safeHost(), e);
        }
    }

    /**
     * Host for log correlation only. Never includes credentials.
     */
    private String safeHost() {
        return param == null ? "unknown" : param.getHost() + ":" + param.resolvePort();
    }
}
