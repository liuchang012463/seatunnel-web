package org.apache.seatunnel.plugin.datasource.vastbase.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VastbaseBatchBuilderTest {

    @Test
    void sinkUsesPostgresDialectAndSchemaQualifiedTable() {
        Config config = new VastbaseBatchBuilder().buildSinkHocon(context(
                "targetTableName = vastbase_copy\nautoCreateTable = true"));

        assertEquals("Postgres", config.getString("dialect"));
        assertEquals("public.vastbase_copy", config.getString("table"));
        assertEquals("org.postgresql.Driver", config.getString("driver"));
    }

    private HoconBuildContext context(String nodeConfig) {
        return HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString(
                        "url = \"jdbc:postgresql://localhost:25432/postgres\"\n"
                                + "driver = \"org.postgresql.Driver\"\n"
                                + "user = postgres\npassword = test\n"
                                + "database = postgres\nschemaName = public"))
                .nodeConfig(ConfigFactory.parseString(nodeConfig))
                .build();
    }
}
