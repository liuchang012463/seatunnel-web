package org.apache.seatunnel.plugin.messaging.rabbitmq;

import org.apache.seatunnel.plugin.messaging.api.MessageClientFactory;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the RabbitMQ plugin is discoverable through the SPI, and that the
 * generated services file lands in the correct {@code META-INF} directory.
 *
 * <p>
 * Modelled on {@code KafkaServiceLoaderTest}. This test runs without a broker:
 * it only exercises registration and factory metadata.
 * </p>
 */
class RabbitServiceLoaderTest {

    @Test
    void shouldRegisterRabbitMqFactory() {
        assertTrue(has(MessageClientFactory.class, item -> "RABBITMQ".equals(item.name())),
                "ServiceLoader 未能发现 RABBITMQ 工厂；请检查 @AutoService 是否生效，"
                        + "以及 META-INF/services 目录拼写是否为 META-INF（不是 META-INFO）");
    }

    @Test
    void shouldExposeStableIdentify() {
        MessageClientFactory factory = load();
        assertEquals("RABBITMQ", factory.getIdentify().getName());
        assertEquals(0, factory.getIdentify().getPriority(),
                "默认优先级应为 0，与项目其他 SPI 保持一致");
    }

    @Test
    void shouldExposeFormFieldsMatchingConnectionParam() {
        MessageClientFactory factory = load();
        assertFalse(factory.params().isEmpty(), "连接配置表单不应为空");

        // Field keys must line up with MessageConnectionParam property names,
        // otherwise a dynamic form renders values into fields nobody reads.
        assertTrue(factory.params().stream().anyMatch(f -> "host".equals(f.getKey())));
        assertTrue(factory.params().stream().anyMatch(f -> "port".equals(f.getKey())));
        assertTrue(factory.params().stream().anyMatch(f -> "virtualHost".equals(f.getKey())));
        assertTrue(factory.params().stream().anyMatch(f -> "username".equals(f.getKey())));
        assertTrue(factory.params().stream().anyMatch(f -> "password".equals(f.getKey())));
        assertTrue(factory.params().stream().anyMatch(f -> "sslEnabled".equals(f.getKey())));
        assertTrue(factory.params().stream().anyMatch(f -> "connectionTimeoutMs".equals(f.getKey())));
    }

    @Test
    void shouldCreateClient() {
        assertNotNull(load().create(), "工厂应能创建客户端实例");
    }

    private MessageClientFactory load() {
        return StreamSupport.stream(ServiceLoader.load(MessageClientFactory.class).spliterator(), false)
                .filter(item -> "RABBITMQ".equals(item.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("RABBITMQ 工厂未注册"));
    }

    private <T> boolean has(Class<T> type, java.util.function.Predicate<T> predicate) {
        return StreamSupport.stream(ServiceLoader.load(type).spliterator(), false).anyMatch(predicate);
    }
}
