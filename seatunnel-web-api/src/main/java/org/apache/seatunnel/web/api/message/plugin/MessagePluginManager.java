package org.apache.seatunnel.web.api.message.plugin;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.plugin.messaging.api.MessageClient;
import org.apache.seatunnel.plugin.messaging.api.MessageClientFactory;
import org.apache.seatunnel.web.spi.plugin.PrioritySPIFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers and holds all registered {@link MessageClientFactory} plugins and
 * their {@link MessageClient} workers.
 *
 * <p>
 * Direct counterpart of {@code AlarmPluginManager}: reuses the project's
 * {@link PrioritySPIFactory} (ServiceLoader + priority conflict resolution)
 * rather than a hand-rolled loader, and is a Spring component so it integrates
 * with DI instead of being a static singleton.
 * </p>
 *
 * <p>
 * Clients are created once at startup and cached. This is safe because
 * {@link MessageClient} implementations are stateless — every push/pull opens
 * and releases its own connection (design decision C1).
 * </p>
 */
@Component
@Slf4j
public class MessagePluginManager {

    private final Map<String, MessageClientFactory> factoryMap = new ConcurrentHashMap<>();

    private final Map<String, MessageClient> clientMap = new ConcurrentHashMap<>();

    public MessagePluginManager() {
        PrioritySPIFactory<MessageClientFactory> spiFactory =
                new PrioritySPIFactory<>(MessageClientFactory.class);
        spiFactory.getSPIMap().forEach((name, factory) -> {
            factoryMap.put(name, factory);
            clientMap.put(name, factory.create());
            log.info("Registered messaging client plugin -> {}", name);
        });
        if (clientMap.isEmpty()) {
            log.warn("No messaging client plugin discovered. Check that seatunnel-web-messaging-all "
                    + "is on the runtime classpath and that the SPI services file lives under META-INF.");
        }
    }

    /**
     * Resolve a client by broker type name.
     *
     * @param name broker type, e.g. {@code "RABBITMQ"}; case-insensitive
     * @return the client, or {@code null} when no plugin matches
     */
    public MessageClient getClient(String name) {
        if (name == null) {
            return null;
        }
        MessageClient direct = clientMap.get(name);
        if (direct != null) {
            return direct;
        }
        // Tolerate lowercase input from callers.
        return clientMap.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public Map<String, MessageClientFactory> getFactoryMap() {
        return factoryMap;
    }
}
