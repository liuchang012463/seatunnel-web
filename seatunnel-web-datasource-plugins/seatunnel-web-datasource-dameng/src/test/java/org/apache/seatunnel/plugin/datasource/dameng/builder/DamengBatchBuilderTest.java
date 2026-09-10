package org.apache.seatunnel.plugin.datasource.dameng.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.dameng.param.DamengDataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamengBatchBuilderTest {

    private final DamengBatchBuilder builder = new DamengBatchBuilder();

    @Test
    void sinkHoconAddsDamengDialectByDefault() {
        Config config = builder.buildSinkHocon(context("targetTableName = sys_user\nautoCreateTable = true"));

        assertEquals("Dameng", config.getString("dialect"));
        // Without a live Dameng, falls back to uppercased form database for v$database match.
        assertEquals("TEST", config.getString("database"));
        assertEquals("SYSDBA.sys_user", config.getString("table"));
        assertTrue(config.getBoolean("generate_sink_sql"));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST", config.getString("schema_save_mode"));
    }

    @Test
    void sinkHoconUppercasesAlreadyUpperDatabaseUnchanged() {
        Config config =
                builder.buildSinkHocon(
                        HoconBuildContext.builder()
                                .connectionConfig(
                                        ConfigFactory.parseString(
                                                "url = \"jdbc:dm://localhost:5236/DAMENG\"\n"
                                                        + "driver = \"dm.jdbc.driver.DmDriver\"\n"
                                                        + "user = test\n"
                                                        + "password = test\n"
                                                        + "database = DAMENG\n"
                                                        + "schemaName = SYSDBA"))
                                .nodeConfig(
                                        ConfigFactory.parseString(
                                                "targetTableName = sys_user\nautoCreateTable = true"))
                                .build());

        assertEquals("Dameng", config.getString("dialect"));
        assertEquals("DAMENG", config.getString("database"));
    }

    @Test
    void sinkHoconKeepsExplicitDialectFromExtraParams() {
        Config config =
                builder.buildSinkHocon(
                        context(
                                "targetTableName = sys_user\n"
                                        + "autoCreateTable = true\n"
                                        + "extraParams = [{ key = \"dialect\", value = \"KingBase\" }]"));

        assertEquals("KingBase", config.getString("dialect"));
    }

    @Test
    void usesBundledDamengDriverJarByDefault() {
        List<FormFieldConfig> fields = new DamengDataSourceProcessor().generateFormFields();

        assertEquals(
                "DmJdbcDriver18-8.1.2.141.jar",
                fields.stream()
                        .filter(field -> "driverLocation".equals(field.getKey()))
                        .findFirst()
                        .orElseThrow()
                        .getDefaultValue());
    }

    private HoconBuildContext context(String nodeConfig) {
        return HoconBuildContext.builder()
                .connectionConfig(
                        ConfigFactory.parseString(
                                "url = \"jdbc:dm://localhost:5236/test\"\n"
                                        + "driver = \"dm.jdbc.driver.DmDriver\"\n"
                                        + "user = test\n"
                                        + "password = test\n"
                                        + "database = test\n"
                                        + "schemaName = SYSDBA"))
                .nodeConfig(ConfigFactory.parseString(nodeConfig))
                .build();
    }
}
