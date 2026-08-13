package org.apache.seatunnel.plugin.datasource.doris.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
