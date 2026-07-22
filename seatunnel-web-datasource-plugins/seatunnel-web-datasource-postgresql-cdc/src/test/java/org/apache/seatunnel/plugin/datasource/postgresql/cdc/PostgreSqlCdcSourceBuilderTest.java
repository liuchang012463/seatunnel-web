package org.apache.seatunnel.plugin.datasource.postgresql.cdc;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.postgresql.cdc.builder.PostgreSqlCdcSourceBuilder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgreSqlCdcSourceBuilderTest {

    private final PostgreSqlCdcSourceBuilder builder = new PostgreSqlCdcSourceBuilder();

    @Test
    void buildsPgoutputConfigFromPostgresqlDatasource() {
        Config config = builder.buildSourceHocon(HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString("""
                        url = \"jdbc:postgresql://localhost:5432/orders\"
                        user = \"cdc\"
                        password = \"secret\"
                        database = \"orders\"
                        schemaName = \"sales\"
                        """))
                .nodeConfig(ConfigFactory.parseString("""
                        table = \"invoices\"
                        slot.name = \"orders_slot\"
                        publicationName = \"orders_publication\"
                        startupMode = \"earliest\"
                        server-id = \"5400-5408\"
                        dataSourceId = 12
                        dbType = \"POSTGRE_SQL\"
                        pluginName = \"POSTGRESQL-CDC\"
                        connectorType = \"Postgres-CDC\"
                        """))
                .build());

        assertEquals("jdbc:postgresql://localhost:5432/orders", config.getString("url"));
        assertEquals("cdc", config.getString("username"));
        assertEquals("orders.sales.invoices", config.getStringList("table-names").get(0));
        assertEquals("sales", config.getStringList("schema-names").get(0));
        assertEquals("orders_slot", config.getString("slot.name"));
        assertEquals("pgoutput", config.getString("decoding.plugin.name"));
        assertEquals("earliest", config.getString("startup.mode"));
        Map<String, Object> debezium = config.getObject("debezium").unwrapped();
        assertEquals("orders_publication", debezium.get("publication.name"));
        assertEquals("disabled", debezium.get("publication.autocreate.mode"));
        assertFalse(debezium.containsKey("publication"));
        assertFalse(debezium.values().stream().anyMatch(Map.class::isInstance));
        assertFalse(config.hasPath("dataSourceId"));
        assertFalse(config.hasPath("pluginName"));
        assertFalse(config.hasPath("server-id"));
    }

    @Test
    void rejectsNonPgoutputStartupModesAndMissingPublication() {
        Config connection = ConfigFactory.parseString("""
                url = \"jdbc:postgresql://localhost:5432/orders\"
                user = \"cdc\"
                password = \"secret\"
                database = \"orders\"
                """);

        assertThrows(IllegalArgumentException.class, () -> builder.buildSourceHocon(
                HoconBuildContext.builder().connectionConfig(connection)
                        .nodeConfig(ConfigFactory.parseString("""
                                table = \"public.invoices\"
                                slot.name = \"orders_slot\"
                                startupMode = \"specific\"
                                publicationName = \"orders_publication\"
                                """)).build()));

        assertThrows(IllegalArgumentException.class, () -> builder.buildSourceHocon(
                HoconBuildContext.builder().connectionConfig(connection)
                        .nodeConfig(ConfigFactory.parseString("""
                                table = \"public.invoices\"
                                slot.name = \"orders_slot\"
                                """)).build()));
    }

    @Test
    void buildsSchemaQualifiedWholeDatabaseTableListAsExactSelection() {
        Config config = builder.buildSourceHocon(HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString("""
                        url = \"jdbc:postgresql://localhost:5432/orders\"
                        user = \"cdc\"
                        password = \"secret\"
                        database = \"orders\"
                        """))
                .nodeConfig(ConfigFactory.parseString("""
                        multiTable = true
                        matchMode = \"1\"
                        source_table_list = [\"public.invoices\", \"audit.events\"]
                        slot.name = \"orders_slot\"
                        publicationName = \"orders_publication\"
                        startup.mode = \"initial\"
                        """))
                .build());

        assertEquals(
                Arrays.asList("orders.public.invoices", "orders.audit.events"),
                config.getStringList("table-names"));
        assertEquals(Arrays.asList("public", "audit"), config.getStringList("schema-names"));
        assertFalse(config.hasPath("table-pattern"));
    }

    @Test
    void flattensExistingNestedDebeziumOptionsToStringMap() {
        Config config = builder.buildSourceHocon(HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString("""
                        url = \"jdbc:postgresql://localhost:5432/orders\"
                        user = \"cdc\"
                        password = \"secret\"
                        database = \"orders\"
                        """))
                .nodeConfig(ConfigFactory.parseString("""
                        table = \"public.invoices\"
                        slot.name = \"orders_slot\"
                        publicationName = \"orders_publication\"
                        extraParams = [{
                          key = \"debezium\"
                          value = {
                            heartbeat {
                              interval.ms = \"5000\"
                            }
                          }
                        }]
                        """))
                .build());

        Map<String, Object> debezium = config.getObject("debezium").unwrapped();
        assertEquals("5000", debezium.get("heartbeat.interval.ms"));
        assertEquals("orders_publication", debezium.get("publication.name"));
        assertTrue(debezium.values().stream().allMatch(String.class::isInstance));
    }
}
