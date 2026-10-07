package org.apache.seatunnel.plugin.datasource.mysql.cdc;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.cdc.CdcDatasourcePrecheckProvider;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.plugin.datasource.mysql.cdc.builder.MysqlCdcSourceBuilder;
import org.apache.seatunnel.plugin.datasource.mysql.cdc.option.MySQLCDCSourceOptionRule;
import org.apache.seatunnel.web.common.config.ConfigValidator;
import org.apache.seatunnel.web.common.config.ReadonlyConfig;
import org.junit.jupiter.api.Test;
import org.apache.seatunnel.web.common.utils.NonNullFunctions;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void sourceConfigIncludesRequiredJdbcUrlAndOnlyDocumentedConnectionFields() {
        Config connection = ConfigFactory.parseString("""
                url = "jdbc:mysql://127.0.0.1:3306/testdb"
                username = "test_user"
                password = "test_password"
                hostname = "127.0.0.1"
                port = 3306
                database = "testdb"
                """);
        Config node = ConfigFactory.parseString("""
                table = "testdb.orders"
                tableNames = ["testdb.orders"]
                startupMode = "initial"
                serverId = "5400-5408"
                serverTimeZone = "Asia/Shanghai"
                """);

        Config source = new MysqlCdcSourceBuilder().buildSourceHocon(HoconBuildContext.builder()
                .connectionParam(connection.root().render())
                .connectionConfig(connection)
                .nodeConfig(node)
                .build());

        assertEquals("jdbc:mysql://127.0.0.1:3306/testdb", source.getString("url"));
        assertTrue(source.hasPath("table-names"));
        assertFalse(source.hasPath("hostname"));
        assertFalse(source.hasPath("port"));
        ConfigValidator.of(ReadonlyConfig.fromConfig(source))
                .validate(new MySQLCDCSourceOptionRule().sourceOptionRule());
    }

    @Test
    void dataSourceHoconBuilderIsRegistered() {
        List<DataSourceHoconBuilder> builders =
                ServiceLoader.load(DataSourceHoconBuilder.class).stream()
                        .map(NonNullFunctions.from(ServiceLoader.Provider::get)).filter(item -> item != null)
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
                        .map(NonNullFunctions.from(ServiceLoader.Provider::get)).filter(item -> item != null)
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
                        .map(NonNullFunctions.from(ServiceLoader.Provider::get)).filter(item -> item != null)
                        .toList();

        assertNotNull(providers);
        assertTrue(
                providers.stream()
                        .anyMatch(p -> "org.apache.seatunnel.plugin.datasource.mysql.cdc.MySQLCDCPrecheckProvider"
                                .equals(p.getClass().getName())),
                "ServiceLoader must discover MySQLCDCPrecheckProvider for CdcDatasourcePrecheckProvider.");
    }
}
