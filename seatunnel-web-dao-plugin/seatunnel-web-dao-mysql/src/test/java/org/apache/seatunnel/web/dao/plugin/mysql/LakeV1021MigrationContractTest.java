package org.apache.seatunnel.web.dao.plugin.mysql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeV1021MigrationContractTest {

    @Test
    void migrationCreatesExactlyTheEightV14ControlPlaneTables() throws IOException {
        String sql;
        try (InputStream stream = getClass().getResourceAsStream(
                "/db/migration/mysql/V1_0_21__init_lake_dual_mode_v14.sql")) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertEquals(8, count(sql, "CREATE TABLE"));
        assertTrue(sql.contains("t_seatunnel_web_lake_source_object_ref"));
        assertTrue(sql.contains("t_seatunnel_web_lake_ods_database_binding"));
        assertTrue(sql.contains("t_seatunnel_web_lake_ods_table_mapping"));
        assertTrue(sql.contains("t_seatunnel_web_lake_job_relation"));
        assertTrue(sql.contains("t_seatunnel_web_lake_lifecycle_policy"));
        assertTrue(sql.contains("t_seatunnel_web_lake_table_lifecycle_binding"));
        assertTrue(sql.contains("t_seatunnel_web_lake_external_catalog_binding"));
        assertTrue(sql.contains("t_seatunnel_web_lake_resource_operation"));
    }

    @Test
    void migrationPreservesConcurrencyAndBindingInvariants() throws IOException {
        String sql;
        try (InputStream stream = getClass().getResourceAsStream(
                "/db/migration/mysql/V1_0_21__init_lake_dual_mode_v14.sql")) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("`lock_version`"));
        assertTrue(sql.contains("`generation`"));
        assertTrue(sql.contains("`operation_token`"));
        assertTrue(sql.contains("uk_lake_ods_database_source"));
        assertTrue(sql.contains("uk_lake_ods_table_target"));
        assertTrue(sql.contains("uk_lake_ods_table_source"));
        assertTrue(sql.contains("uk_lake_job_relation_binding_job_scope"));
        assertTrue(sql.contains("uk_lake_table_lifecycle_table"));
        assertTrue(sql.contains("uk_lake_catalog_source"));
        assertTrue(sql.contains("uk_lake_operation_token"));
    }

    private static int count(String value, String token) {
        int count = 0;
        int start = 0;
        while ((start = value.indexOf(token, start)) >= 0) {
            count++;
            start += token.length();
        }
        return count;
    }
}
