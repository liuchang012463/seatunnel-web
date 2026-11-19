package org.apache.seatunnel.plugin.datasource.doris.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisBatchBuilderTest {

    private final DorisBatchBuilder builder = new DorisBatchBuilder();

    @Test
    void mapsSingleTableWorkflowTargetNameToRequiredSinkTable() {
        Config config = builder.buildSinkHocon(context(
                "targetTableName = mock_http_mico_batch\n"
                        + "autoCreateTable = true"));

        assertEquals("mock_http_mico_batch", config.getString("table"));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST", config.getString("schema_save_mode"));
        assertEquals("json", config.getString("doris.config.format"));
    }

    @Test
    void keepsExplicitTableWhenTargetNameIsAbsent() {
        Config config = builder.buildSinkHocon(context("table = existing_table"));

        assertEquals("existing_table", config.getString("table"));
    }

    @Test
    void usesSelectedDatabaseAndDynamicTableNameForMultiTableSink() {
        Config config = builder.buildSinkHocon(context(
                "database = target_db\n"
                        + "multiTable = true\n"
                        + "table_list = [\"orders\", \"customers\"]"));

        assertEquals("target_db", config.getString("database"));
        assertEquals("${table_name}", config.getString("table"));
    }

    @Test
    void wholeDatabaseCreateTemplateAcceptsTablesWithDifferentKeyMetadata() {
        Config config = builder.buildSinkHocon(context("""
                multiTable = true
                matchMode = "4"
                schema_save_mode = CREATE_SCHEMA_WHEN_NOT_EXIST
                dorisReplicaCount = 3
                """));

        String template = config.getString("save_mode_create_template");
        assertEquals("${table_name}", config.getString("table"));
        assertTrue(template.contains("${rowtype_fields}"));
        assertFalse(template.contains("${rowtype_primary_key}"));
        assertFalse(template.contains("${rowtype_unique_key}"));
        assertFalse(template.contains("${rowtype_duplicate_key}"));
        assertTrue(template.contains("DISTRIBUTED BY RANDOM BUCKETS AUTO"));
        assertTrue(template.contains("\"enable_duplicate_without_keys_by_default\" = \"true\""));
        assertTrue(template.contains("replication_allocation\" = \"tag.location.default: 3"));
    }

    @Test
    void wholeDatabaseSinkHonorsExplicitCreateTemplateAndSaveMode() {
        Config custom = builder.buildSinkHocon(context("""
                multiTable = true
                matchMode = "4"
                save_mode_create_template = "custom DDL"
                """));
        assertEquals("custom DDL", custom.getString("save_mode_create_template"));

        Config existingOnly = builder.buildSinkHocon(context("""
                multiTable = true
                matchMode = "4"
                schema_save_mode = ERROR_WHEN_SCHEMA_NOT_EXIST
                """));
        assertFalse(existingOnly.hasPath("save_mode_create_template"));
    }

    @Test
    void wholeDatabaseWithOneQualifiedTableStillUsesSourceTableName() {
        Config config = builder.buildSinkHocon(context("""
                matchMode = "4"
                multiTable = false
                table_list = ["audit.orders"]
                targetTableName = "audit.orders"
                """));

        assertEquals("${table_name}", config.getString("table"));
        assertTrue(config.getString("save_mode_create_template").contains("DISTRIBUTED BY RANDOM"));
    }

    @Test
    void splitsQualifiedDorisTablesIntoDatabaseAndTableForMultiSource() {
        Config config = builder.buildSourceHocon(context(
                "database = ods\n"
                        + "multiTable = true\n"
                        + "table_list = [\"analytics.orders\", \"ods.customers\"]"));

        assertEquals("analytics", config.getConfigList("table_list").get(0).getString("database"));
        assertEquals("orders", config.getConfigList("table_list").get(0).getString("table"));
        assertEquals("ods", config.getConfigList("table_list").get(1).getString("database"));
        assertEquals("customers", config.getConfigList("table_list").get(1).getString("table"));
    }

    @Test
    void appliesConfiguredReplicaCountToDefaultCreateTableTemplate() {
        Config config = builder.buildSinkHocon(context(
                "targetTableName = orders\n"
                        + "dorisReplicaCount = 3"));

        assertTrue(config.getString("save_mode_create_template")
                .contains("replication_allocation\" = \"tag.location.default: 3"));
    }

    @Test
    void requiresStableLabelPrefixWhenTwoPhaseCommitIsEnabled() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildSinkHocon(context("sink.enable-2pc = true")));

        Config config = builder.buildSinkHocon(context(
                "sink.enable-2pc = true\n"
                        + "sink.label-prefix = stable_orders_job"));

        assertTrue(config.getBoolean("sink.enable-2pc"));
        assertEquals("stable_orders_job", config.getString("sink.label-prefix"));
    }

    private HoconBuildContext context(String nodeConfig) {
        return HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString(
                        "fenodes = \"82.157.22.233:8030\"\n"
                                + "user = test\n"
                                + "password = test\n"
                                + "database = ods"))
                .nodeConfig(ConfigFactory.parseString(nodeConfig))
                .build();
    }

    @Test
    void lakeJdbcProjectionResolvesStreamLoadEndpointWithoutUsingQueryPort() {
        Config config = builder.buildSinkHocon(HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString(
                        "url = \"jdbc:mysql://doris.example:19030/ods?useSSL=false\"\n"
                                + "user = test\npassword = test\ndatabase = ods"))
                .nodeConfig(ConfigFactory.parseString("table = orders"))
                .build());

        assertEquals("doris.example:8030", config.getString("fenodes"));
    }

    @Test
    void explicitStreamLoadEndpointOverridesJdbcProjectionDefault() {
        Config config = builder.buildSinkHocon(HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString(
                        "url = \"jdbc:mysql://doris.example:9030/ods\"\n"
                                + "fenodes = \"stream.example:18030\"\n"
                                + "user = test\npassword = test\ndatabase = ods"))
                .nodeConfig(ConfigFactory.parseString("table = orders"))
                .build());

        assertEquals("stream.example:18030", config.getString("fenodes"));
    }
}
