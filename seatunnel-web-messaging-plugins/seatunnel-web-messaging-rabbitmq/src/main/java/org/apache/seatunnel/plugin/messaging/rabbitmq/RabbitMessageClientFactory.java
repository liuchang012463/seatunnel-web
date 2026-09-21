package org.apache.seatunnel.plugin.messaging.rabbitmq;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.messaging.api.MessageClient;
import org.apache.seatunnel.plugin.messaging.api.MessageClientFactory;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory SPI entry point for the built-in RabbitMQ messaging client.
 *
 * <p>
 * Registration follows the project's existing convention: {@code @AutoService}
 * makes {@code auto-service} generate
 * {@code META-INF/services/org.apache.seatunnel.plugin.messaging.api.MessageClientFactory}
 * at compile time, so no services file is hand-written. Note the directory is
 * {@code META-INF}, not {@code META-INFO} — three legacy dao-plugin modules got
 * that spelling wrong (risk R3 in the code analysis report) and their entries
 * are never loaded.
 * </p>
 */
@AutoService(MessageClientFactory.class)
public class RabbitMessageClientFactory implements MessageClientFactory {

    public static final String RABBITMQ = "RABBITMQ";

    @Override
    public String name() {
        return RABBITMQ;
    }

    @Override
    public MessageClient create() {
        return new RabbitMessageClient();
    }

    /**
     * Descriptor for the connection form.
     *
     * <p>
     * Kept in sync with the {@code @FormField} annotations on
     * {@link org.apache.seatunnel.plugin.messaging.api.MessageConnectionParam}.
     * The annotations document the fields for anyone reading the model; this
     * list is what a dynamic UI actually renders. {@code key} values must match
     * the parameter's field names exactly.
     * </p>
     */
    @Override
    public List<FormFieldConfig> params() {
        List<FormFieldConfig> fields = new ArrayList<>();
        fields.add(field("host", "主机", FieldType.INPUT, "10.0.0.1", null,
                "RabbitMQ 服务端地址。注意：该地址由调用方指定，平台不做白名单限制。", 1));
        fields.add(field("port", "端口", FieldType.NUMBER, null, "5672",
                "AMQP 端口，默认 5672；启用 TLS 时通常为 5671。", 2));
        fields.add(field("virtualHost", "虚拟主机", FieldType.INPUT, "/", "/",
                "RabbitMQ vhost，默认为 /。", 3));
        fields.add(field("username", "用户名", FieldType.INPUT, null, null, null, 4));
        fields.add(field("password", "密码", FieldType.PASSWORD, null, null,
                "密码以明文随请求传入，请确保传输通道安全（建议 HTTPS）。", 5));
        fields.add(field("sslEnabled", "启用 TLS", FieldType.SWITCH, null, "false", null, 6));
        fields.add(field("connectionTimeoutMs", "连接超时（毫秒）", FieldType.NUMBER, null, "10000", null, 7));
        return fields;
    }

    private FormFieldConfig field(String key, String label, FieldType type,
                                  String placeholder, String defaultValue,
                                  String description, int order) {
        FormFieldConfig config = new FormFieldConfig();
        config.setKey(key);
        config.setLabel(label);
        config.setType(type);
        config.setPlaceholder(placeholder);
        config.setDefaultValue(defaultValue);
        config.setDescription(description);
        config.setOrder(order);
        return config;
    }
}
