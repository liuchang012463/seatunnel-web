package org.apache.seatunnel.plugin.datasource.pgsql.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PgSQLBatchBuilderTest {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/test";
    private final PgSQLBatchBuilder builder = new PgSQLBatchBuilder();

    @Test
    void generatedSinkLetsPostgresqlInferTheTypeOfStringParameters() {
        Config sink = builder.buildSinkHocon(context(JDBC_URL, "targetTableName = events"));

        assertEquals("unspecified", Driver.parseURL(sink.getString("url"), null).getProperty("stringtype"));
    }

    @Test
    void preservesOtherJdbcUrlOptions() {
        String url = JDBC_URL + "?ApplicationName=import%26stringtype%3Dvarchar&ssl=false";
        Config sink = builder.buildSinkHocon(context(url, "targetTableName = events"));

        assertEquals(url + "&stringtype=unspecified", sink.getString("url"));
    }

    @Test
    void preservesExplicitStringTypeInUrlOrProperties() {
        String url = JDBC_URL + "?ssl=false&stringtype=varchar";
        Config sinkWithUrlOption = builder.buildSinkHocon(context(url, "targetTableName = events"));
        Config sinkWithProperty = builder.buildSinkHocon(context(
                JDBC_URL, "targetTableName = events\nconfig.properties.stringtype = varchar"));

        assertEquals(url, sinkWithUrlOption.getString("url"));
        assertEquals(JDBC_URL, sinkWithProperty.getString("url"));
        assertEquals("varchar", sinkWithProperty.getString("properties.stringtype"));
    }

    @Test
    void preservesDriverDefaultsForCustomSql() {
        Config sink = builder.buildSinkHocon(context(JDBC_URL, "sql = \"insert into events values (?)\""));

        assertEquals(JDBC_URL, sink.getString("url"));
    }

    private HoconBuildContext context(String url, String nodeConfig) {
        return HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseMap(Map.of(
                        "url", url, "user", "tester", "database", "test")))
                .nodeConfig(ConfigFactory.parseString(nodeConfig))
                .build();
    }
}
