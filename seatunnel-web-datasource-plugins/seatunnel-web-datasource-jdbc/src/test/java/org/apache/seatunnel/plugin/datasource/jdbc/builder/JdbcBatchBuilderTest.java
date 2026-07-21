package org.apache.seatunnel.plugin.datasource.jdbc.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcBatchBuilderTest {

    private final JdbcBatchBuilder builder = new JdbcBatchBuilder();

    @Test
    void buildsJdbcSourceFromCustomQuery() {
        Config config = builder.buildSourceHocon(context("sql = \"select * from orders\""));

        assertEquals("jdbc:vendor://localhost:1234/catalog", config.getString("url"));
        assertEquals("com.vendor.Driver", config.getString("driver"));
        assertEquals("select * from orders", config.getString("query"));
    }

    @Test
    void buildsJdbcSinkFromTargetTable() {
        Config config = builder.buildSinkHocon(
                context("targetTableName = orders\nautoCreateTable = true"));

        assertTrue(config.getString("table").endsWith("orders"));
        assertTrue(config.getBoolean("generate_sink_sql"));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST", config.getString("schema_save_mode"));
        assertEquals("APPEND_DATA", config.getString("data_save_mode"));
    }

    private HoconBuildContext context(String nodeConfig) {
        return HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString(
                        "url = \"jdbc:vendor://localhost:1234/catalog\"\n"
                                + "driver = \"com.vendor.Driver\"\n"
                                + "user = test\n"
                                + "password = test\n"
                                + "database = catalog\n"
                                + "schemaName = public"))
                .nodeConfig(ConfigFactory.parseString(nodeConfig))
                .build();
    }
}
