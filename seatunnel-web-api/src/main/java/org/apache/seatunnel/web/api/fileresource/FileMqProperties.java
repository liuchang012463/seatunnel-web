package org.apache.seatunnel.web.api.fileresource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Destination for best-effort MQ notifications after file-resource uploads.
 *
 * <p>Broker credentials stay in {@code seatunnel.message.broker}. This block
 * only selects whether to publish and which exchange / routing key to use.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "seatunnel.web.file-mq")
public class FileMqProperties {

    /**
     * When false, upload success never attempts MQ publish.
     */
    private boolean enabled = true;

    /**
     * AMQP exchange. Empty means the default exchange (routing key = queue name).
     */
    private String exchange = "";

    /**
     * Routing key. Required when {@link #enabled} is true.
     * For the default exchange, set this to the target queue name (e.g. {@code order.sync}).
     */
    private String routingKey = "";

    public boolean isDestinationConfigured() {
        return routingKey != null && !routingKey.isBlank();
    }
}
