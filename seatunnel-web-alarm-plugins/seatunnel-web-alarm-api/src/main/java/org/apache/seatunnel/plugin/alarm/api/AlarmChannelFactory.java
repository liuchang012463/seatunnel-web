package org.apache.seatunnel.plugin.alarm.api;

import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.apache.seatunnel.web.spi.plugin.PrioritySPI;
import org.apache.seatunnel.web.spi.plugin.SPIIdentify;

import java.util.List;

/**
 * Factory SPI for alarm channels, mirroring DolphinScheduler's
 * {@code AlertChannelFactory}.
 *
 * <p>
 * A factory declares its identity ({@link #name()} + {@link #getIdentify()}),
 * the config form ({@link #params()}), and produces {@link AlarmChannel}
 * workers via {@link #create()}. Factories are discovered through the
 * project's {@link org.apache.seatunnel.web.spi.plugin.PrioritySPIFactory}
 * (ServiceLoader + priority conflict resolution).
 * </p>
 *
 * <p>
 * Separating factory from channel keeps the worker ({@link AlarmChannel}) as
 * thin as a single {@code process(AlarmInfo)} method, exactly like DS.
 * </p>
 */
public interface AlarmChannelFactory extends PrioritySPI {

    /**
     * Unique, stable channel name, e.g. "WEBHOOK", "DINGTALK".
     * Used as the SPI registry key and the persisted channel type.
     */
    String name();

    /**
     * Human-readable name shown by the alarm management UI.
     *
     * <p>The SPI key remains {@link #name()} so stored channel records stay
     * stable while a plugin can expose a localized display name.</p>
     */
    default String displayName() {
        return name();
    }

    /**
     * Create a (stateless) alarm channel worker.
     */
    AlarmChannel create();

    /**
     * Configurable parameters rendered as a dynamic UI form.
     */
    List<FormFieldConfig> params();

    @Override
    default SPIIdentify getIdentify() {
        return SPIIdentify.builder().name(name()).build();
    }
}
