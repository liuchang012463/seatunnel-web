package org.apache.seatunnel.plugin.messaging.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormField;

/**
 * Broker connection configuration supplied by the caller on every request.
 *
 * <p>
 * This deliberately carries the <b>full</b> configuration including username
 * and password (design decision A3): the calling system owns its own broker, so
 * the platform holds no credentials of its own and performs no host
 * allow-listing.
 * </p>
 *
 * <p>
 * Annotated with {@link FormField} so the same class can back a dynamic
 * configuration form, exactly like {@code KafkaConnectionParam}. Annotated with
 * Lombok getters/setters rather than {@code @Data} so that {@link #toString()}
 * can be hand-written to mask the password.
 * </p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageConnectionParam {

    @FormField(label = "主机", required = true, order = 1, placeholder = "10.0.0.1",
            description = "RabbitMQ 服务端地址。")
    private String host;

    @FormField(label = "端口", type = FieldType.NUMBER, order = 2, defaultValue = "5672",
            description = "AMQP 端口，默认 5672；启用 TLS 时通常为 5671。")
    private Integer port = 5672;

    @FormField(label = "虚拟主机", order = 3, defaultValue = "/",
            placeholder = "/",
            description = "RabbitMQ vhost，默认为 /。")
    private String virtualHost = "/";

    @FormField(label = "用户名", required = true, order = 4)
    private String username;

    @FormField(label = "密码", type = FieldType.PASSWORD, required = true, order = 5)
    private String password;

    @FormField(label = "启用 TLS", type = FieldType.SWITCH, order = 6, defaultValue = "false")
    private Boolean sslEnabled = Boolean.FALSE;

    @FormField(label = "连接超时（毫秒）", type = FieldType.NUMBER, order = 7, defaultValue = "10000")
    private Integer connectionTimeoutMs = 10000;

    /**
     * Effective port, applying the broker default when the caller omitted it.
     */
    public int resolvePort() {
        return port == null || port <= 0 ? 5672 : port;
    }

    /**
     * Effective virtual host, applying {@code /} when blank.
     */
    public String resolveVirtualHost() {
        return virtualHost == null || virtualHost.isBlank() ? "/" : virtualHost;
    }

    /**
     * Effective connection timeout, applying 10s when unset or non-positive.
     */
    public int resolveConnectionTimeoutMs() {
        return connectionTimeoutMs == null || connectionTimeoutMs <= 0 ? 10_000 : connectionTimeoutMs;
    }

    /**
     * Whether TLS was requested; {@code null} is treated as disabled.
     */
    public boolean isSslEnabled() {
        return Boolean.TRUE.equals(sslEnabled);
    }

    /**
     * Masked rendering. The password is never emitted, so this object is safe
     * to appear in logs and exception messages.
     *
     * <p>Note this only protects server-side logs: the password still travels
     * in cleartext inside the HTTP request body, which is an explicitly
     * accepted risk (see implementation doc §9 risk 3).</p>
     */
    @Override
    public String toString() {
        return "MessageConnectionParam{host='" + host + "', port=" + resolvePort()
                + ", virtualHost='" + resolveVirtualHost() + "', username='" + username
                + "', sslEnabled=" + isSslEnabled()
                + ", connectionTimeoutMs=" + resolveConnectionTimeoutMs()
                + ", password='******'}";
    }
}
