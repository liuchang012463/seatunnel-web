package org.apache.seatunnel.web.api.message;

import lombok.Data;
import org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam;
import org.apache.seatunnel.plugin.messaging.api.MessageException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the generic message push/pull endpoints.
 *
 * <p>
 * Bound from the {@code seatunnel.message.*} block. Broker credentials live
 * here (and in environment overrides) — callers no longer pass {@code connection}
 * on each request.
 * </p>
 *
 * <p>
 * These endpoints do not use an API key. Access control, if needed, belongs
 * outside this service (network policy, reverse proxy, etc.).
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "seatunnel.message")
public class MessageProperties {

    /**
     * Platform-owned RabbitMQ broker used by push/pull.
     */
    private Broker broker = new Broker();

    /**
     * Maximum accepted payload size in bytes. Default 1 MB (decision D2).
     */
    private int maxBodyBytes = 1024 * 1024;

    /**
     * Hard ceiling on messages returned by one pull, regardless of what the
     * caller asked for. Default 100 (decision B1).
     */
    private int maxBatchSize = 100;

    /**
     * Default pull deadline in milliseconds when the caller omits one.
     */
    private long pullDefaultTimeoutMs = 3000L;

    /**
     * Maximum JSON nesting depth accepted in a payload. Default 200, down from
     * Jackson's own 1000, to bound recursion cost on attacker-controlled
     * structure (decision D1).
     */
    private int maxNestingDepth = 200;

    /**
     * Maximum length of a single JSON string token.
     */
    private int maxStringLength = 1024 * 1024;

    /**
     * Maximum length of a single JSON number token.
     */
    private int maxNumberLength = 1000;

    /**
     * Whether the configured broker has the minimum fields to attempt a connect.
     */
    public boolean isBrokerConfigured() {
        return broker != null
                && broker.getHost() != null && !broker.getHost().isBlank()
                && broker.getUsername() != null && !broker.getUsername().isBlank();
    }

    /**
     * Build the connection param used by the messaging SPI.
     *
     * @throws MessageException when host/username are missing
     */
    public MessageConnectionParam toConnectionParam() {
        if (!isBrokerConfigured()) {
            throw new MessageException("消息队列未配置");
        }
        MessageConnectionParam param = new MessageConnectionParam();
        param.setHost(broker.getHost().trim());
        param.setPort(broker.getPort());
        param.setVirtualHost(broker.getVirtualHost());
        param.setUsername(broker.getUsername().trim());
        param.setPassword(broker.getPassword());
        param.setSslEnabled(broker.getSslEnabled());
        param.setConnectionTimeoutMs(broker.getConnectionTimeoutMs());
        return param;
    }

    /**
     * RabbitMQ broker connection settings under {@code seatunnel.message.broker}.
     */
    @Data
    public static class Broker {

        private String host = "127.0.0.1";

        private Integer port = 5672;

        private String virtualHost = "/";

        private String username = "guest";

        private String password = "guest";

        private Boolean sslEnabled = Boolean.FALSE;

        private Integer connectionTimeoutMs = 10_000;
    }
}
