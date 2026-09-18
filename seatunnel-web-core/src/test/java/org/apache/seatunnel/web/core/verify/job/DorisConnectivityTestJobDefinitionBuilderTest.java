package org.apache.seatunnel.web.core.verify.job;

import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DorisConnectivityTestJobDefinitionBuilderTest {

    @Test
    void connectionOnlyProbeDoesNotNeedAVisibleBusinessTable() {
        assertEquals("SELECT 1 AS connectivity_check",
                DorisConnectivityTestJobDefinitionBuilder.buildProbeSql(
                        ConfigFactory.parseString("database = \"empty_db\""), null));
    }

    @Test
    void explicitTableProbeUsesTaskDatabaseAndLimitZero() {
        DatasourceVerifyScope scope = DatasourceVerifyScope.builder()
                .database("target_db")
                .table("orders")
                .build();

        assertEquals("SELECT * FROM `target_db`.`orders` LIMIT 0",
                DorisConnectivityTestJobDefinitionBuilder.buildProbeSql(
                        ConfigFactory.parseString("database = \"anchor_db\""), scope));
    }

}
