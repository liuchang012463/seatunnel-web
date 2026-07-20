package org.apache.seatunnel.plugin.datasource.mysql.cdc;

import org.apache.seatunnel.plugin.datasource.api.cdc.CdcDatasourcePrecheckProvider;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.plugin.datasource.mysql.cdc.builder.MysqlCdcSourceBuilder;
import org.apache.seatunnel.plugin.datasource.mysql.cdc.option.MySQLCDCSourceOptionRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code MYSQL-CDC} plugin contract:
 * <ul>
 *   <li>{@code pluginName()} on both builder and option-rule returns the uppercase
 *       identifier that {@code DataSourceSourceBuilder#getRequiredPluginName()} keys its
 *       lookup by.</li>
 *   <li>JDK {@link ServiceLoader} discovers the {@code @AutoService}-generated SPI
 *       registrations at runtime, so the Spring Boot fat-jar / LaunchedClassLoader
 *       regression reported in the spec cannot silently reappear.</li>
 * </ul>
 */
class MysqlCdcServiceLoaderTest {

    @Test
    void sourceBuilderExposesUppercasePluginName() {
        assertEquals("MYSQL-CDC", new MysqlCdcSourceBuilder().pluginName());
    }

    @Test
    void sourceOptionRuleExposesUppercasePluginName() {
        assertEquals("MYSQL-CDC", new MySQLCDCSourceOptionRule().pluginName());
    }

    @Test
    void dataSourceHoconBuilderIsRegistered() {
        List<DataSourceHoconBuilder> builders =
                ServiceLoader.load(DataSourceHoconBuilder.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList();

        assertNotNull(builders);
        assertTrue(
                builders.stream().anyMatch(b -> b instanceof MysqlCdcSourceBuilder),
                "ServiceLoader must discover MysqlCdcSourceBuilder for DataSourceHoconBuilder; "
                        + "this fails if the SPI file is missing or blocked by the nested jar loader.");
    }

    @Test
    void sourceOptionRuleIsRegistered() {
        List<SourceOptionRule> rules =
                ServiceLoader.load(SourceOptionRule.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList();

        assertNotNull(rules);
        assertTrue(
                rules.stream().anyMatch(r -> r instanceof MySQLCDCSourceOptionRule),
                "ServiceLoader must discover MySQLCDCSourceOptionRule for SourceOptionRule.");
    }

    @Test
    void cdcDatasourcePrecheckProviderIsRegistered() {
        List<CdcDatasourcePrecheckProvider> providers =
                ServiceLoader.load(CdcDatasourcePrecheckProvider.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList();

        assertNotNull(providers);
        assertTrue(
                providers.stream()
                        .anyMatch(p -> "org.apache.seatunnel.plugin.datasource.mysql.cdc.MySQLCDCPrecheckProvider"
                                .equals(p.getClass().getName())),
                "ServiceLoader must discover MySQLCDCPrecheckProvider for CdcDatasourcePrecheckProvider.");
    }
}