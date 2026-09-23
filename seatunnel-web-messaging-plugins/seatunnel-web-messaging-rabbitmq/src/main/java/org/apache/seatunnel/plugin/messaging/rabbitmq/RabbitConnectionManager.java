package org.apache.seatunnel.plugin.messaging.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionResources;
import org.apache.seatunnel.plugin.messaging.api.MessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares one AMQP {@link Connection} per broker fingerprint and lends a fresh
 * {@link Channel} for each push/pull.
 *
 * <p>
 * Replaces the previous per-request handshake (design C1) now that the platform
 * owns a single YAML-configured broker. Channel create/close stays cheap; the
 * expensive TCP + AMQP handshake happens only on first use or after invalidate.
 * </p>
 *
 * <p>
 * {@code verify()} runs <b>only when a Connection is (re)established</b>, not on
 * every borrow.
 * </p>
 */
final class RabbitConnectionManager {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitConnectionManager.class);

    private static final RabbitConnectionManager SHARED = new RabbitConnectionManager();

    private final ConcurrentHashMap<String, SharedConnection> connections = new ConcurrentHashMap<>();

    static RabbitConnectionManager shared() {
        return SHARED;
    }

    /**
     * Borrow a channel-backed resource. {@link MessageConnectionResources#close()}
     * closes only the channel; the shared connection stays open.
     */
    MessageConnectionResources borrow(MessageConnectionParam param) {
        Objects.requireNonNull(param, "param");
        String key = fingerprint(param);
        SharedConnection shared = connections.computeIfAbsent(key, k -> new SharedConnection(param));
        try {
            Channel channel = shared.openChannel();
            return new BorrowedChannelResources(this, key, shared, channel, param);
        } catch (MessageException e) {
            invalidate(key);
            throw e;
        } catch (Exception e) {
            invalidate(key);
            LOG.warn("建立 RabbitMQ 连接失败, host={}:{}, vhost={}",
                    param.getHost(), param.resolvePort(), param.resolveVirtualHost(), e);
            throw new MessageException("MQ 连接失败，请检查配置", e);
        }
    }

    void invalidate(String fingerprint) {
        SharedConnection removed = connections.remove(fingerprint);
        if (removed != null) {
            removed.closeQuietly();
        }
    }

    /** Visible for tests: how many shared connections are currently cached. */
    int cachedConnectionCount() {
        return connections.size();
    }

    /** Drop every cached connection. Visible for tests. */
    void clear() {
        for (String key : connections.keySet()) {
            invalidate(key);
        }
    }

    static String fingerprint(MessageConnectionParam param) {
        // Password is part of identity so a credential rotation forces reconnect.
        return param.getHost() + '|'
                + param.resolvePort() + '|'
                + param.resolveVirtualHost() + '|'
                + nullToEmpty(param.getUsername()) + '|'
                + nullToEmpty(param.getPassword()) + '|'
                + param.isSslEnabled();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * One live connection for a fingerprint. Synchronized so create/verify/
     * recreate cannot race.
     */
    static final class SharedConnection {

        private final MessageConnectionParam param;

        private Connection connection;

        SharedConnection(MessageConnectionParam param) {
            this.param = param;
        }

        synchronized Channel openChannel() throws Exception {
            ensureConnected();
            try {
                return connection.createChannel();
            } catch (Exception e) {
                // Connection may have died under us; rebuild once then retry.
                closeQuietly();
                ensureConnected();
                return connection.createChannel();
            }
        }

        synchronized void ensureConnected() throws Exception {
            if (connection != null && connection.isOpen()) {
                return;
            }
            closeQuietly();
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(param.getHost());
            factory.setPort(param.resolvePort());
            factory.setVirtualHost(param.resolveVirtualHost());
            factory.setUsername(param.getUsername());
            factory.setPassword(param.getPassword());
            factory.setConnectionTimeout(param.resolveConnectionTimeoutMs());
            if (param.isSslEnabled()) {
                try {
                    factory.useSslProtocol();
                } catch (Exception e) {
                    LOG.warn("初始化 TLS 失败", e);
                    throw new MessageException("启用 TLS 失败，请检查配置");
                }
            }
            Connection opened = factory.newConnection("seatunnel-web-messaging");
            Channel probe = null;
            try {
                probe = opened.createChannel();
                verify(probe);
                this.connection = opened;
            } catch (Exception e) {
                closeQuietly(probe, opened);
                if (e instanceof MessageException) {
                    throw e;
                }
                throw new MessageException("MQ 连接失败，请检查配置", e);
            } finally {
                // Probe channel is discarded; each borrow gets its own channel.
                closeChannelQuietly(probe);
            }
        }

        synchronized boolean isConnectionOpen() {
            return connection != null && connection.isOpen();
        }

        synchronized void closeQuietly() {
            if (connection != null) {
                try {
                    if (connection.isOpen()) {
                        connection.close();
                    }
                } catch (Exception e) {
                    LOG.warn("关闭共享 RabbitMQ Connection 失败, host={}:{}",
                            param.getHost(), param.resolvePort(), e);
                }
                connection = null;
            }
        }

        private static void closeQuietly(Channel channel, Connection connection) {
            closeChannelQuietly(channel);
            if (connection != null) {
                try {
                    if (connection.isOpen()) {
                        connection.close();
                    }
                } catch (Exception ignored) {
                    // best effort during failed open
                }
            }
        }

        private static void closeChannelQuietly(Channel channel) {
            if (channel == null) {
                return;
            }
            try {
                if (channel.isOpen()) {
                    channel.close();
                }
            } catch (Exception ignored) {
                // best effort
            }
        }

        /**
         * Reachability probe — same semantics as the former per-request verify.
         */
        private static void verify(Channel channel) {
            if (channel == null || !channel.isOpen()) {
                throw new MessageException("MQ 连接不可用，请检查配置",
                        new java.io.IOException("channel is already closed"));
            }
            try {
                channel.queueDeclarePassive(RabbitMessageClient.PROBE_QUEUE);
            } catch (java.io.IOException e) {
                if (isChannelLevelError(e)) {
                    // Broker answered; connectivity/auth are fine. Channel is
                    // dead after a channel exception — caller discards it.
                    return;
                }
                throw new MessageException("MQ 连接不可用，请检查配置", e);
            } catch (Exception e) {
                throw new MessageException("MQ 连接不可用，请检查配置", e);
            }
        }

        private static boolean isChannelLevelError(Throwable e) {
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
                        return replyCode == 403 || replyCode == 404
                                || replyCode == 405 || replyCode == 406;
                    }
                }
                cursor = cursor.getCause();
            }
            return false;
        }
    }

    /**
     * Per-request handle: owns one Channel; {@link #close()} does not tear down
     * the shared Connection.
     */
    static final class BorrowedChannelResources implements MessageConnectionResources {

        private final RabbitConnectionManager manager;

        private final String fingerprint;

        private final SharedConnection shared;

        private final Channel channel;

        private final MessageConnectionParam param;

        private boolean closed;

        BorrowedChannelResources(RabbitConnectionManager manager, String fingerprint,
                                 SharedConnection shared, Channel channel,
                                 MessageConnectionParam param) {
            this.manager = manager;
            this.fingerprint = fingerprint;
            this.shared = shared;
            this.channel = channel;
            this.param = param;
        }

        @Override
        public Object channel() {
            return channel;
        }

        @Override
        public void verify() {
            // Already verified when the shared connection was established.
        }

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
            } catch (Exception e) {
                LOG.warn("归还 RabbitMQ Channel 失败, host={}:{}",
                        param.getHost(), param.resolvePort(), e);
            }
            // If the connection died during the request, drop it so the next
            // borrow reconnects instead of handing out a dead session.
            if (!shared.isConnectionOpen()) {
                manager.invalidate(fingerprint);
            }
        }

        /** Force the shared connection out of the cache (e.g. after transport failure). */
        void invalidateShared() {
            manager.invalidate(fingerprint);
        }
    }
}
