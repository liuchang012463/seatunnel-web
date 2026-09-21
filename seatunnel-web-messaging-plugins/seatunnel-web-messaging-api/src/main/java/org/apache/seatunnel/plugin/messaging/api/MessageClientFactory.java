package org.apache.seatunnel.plugin.messaging.api;

import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.apache.seatunnel.web.spi.plugin.PrioritySPI;
import org.apache.seatunnel.web.spi.plugin.SPIIdentify;

import java.util.List;

/**
 * Factory SPI for message clients, mirroring {@code AlarmChannelFactory}.
 *
 * <p>
 * A factory declares its identity ({@link #name()} + {@link #getIdentify()}),
 * the config form ({@link #params()}), and produces {@link MessageClient}
 * workers via {@link #create()}. Factories are discovered through the project's
 * {@link org.apache.seatunnel.web.spi.plugin.PrioritySPIFactory} (ServiceLoader
 * + priority conflict resolution).
 * </p>
 *
 * <p>
 * Separating factory from client keeps the worker ({@link MessageClient}) as
 * thin as its two methods, exactly like the alarm family splits
 * {@code AlarmChannelFactory} from {@code AlarmChannel}.
 * </p>
 */
public interface MessageClientFactory extends PrioritySPI {

    /**
     * Unique, stable broker type name, e.g. {@code "RABBITMQ"}.
     * Used as the SPI registry key.
     */
    String name();

    /**
     * Human-readable name shown by management UIs.
     *
     * <p>The SPI key remains {@link #name()} so stored configuration stays
     * stable while a plugin can expose a localized display name.</p>
     */
    default String displayName() {
        return name();
    }

    /**
     * Create a (stateless) message client worker.
     */
    MessageClient create();

    /**
     * Configurable parameters rendered as a dynamic UI form.
     */
    List<FormFieldConfig> params();

    @Override
    default SPIIdentify getIdentify() {
        return SPIIdentify.builder().name(name()).build();
    }
}
