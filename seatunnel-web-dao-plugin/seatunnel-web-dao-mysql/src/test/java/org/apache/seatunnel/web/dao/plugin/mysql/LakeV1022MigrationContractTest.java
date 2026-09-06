package org.apache.seatunnel.web.dao.plugin.mysql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeV1022MigrationContractTest {

    @Test
    void mysqlMigrationAddsOnlyLifecycleObservationColumns() throws IOException {
        String sql = readMigration();

        assertTrue(sql.contains("ALTER TABLE `t_seatunnel_web_lake_table_lifecycle_binding`"));
        assertTrue(sql.contains("`actual_partition_summary_json` longtext"));
        assertTrue(sql.contains("`last_observed_at` datetime"));
        assertTrue(sql.contains("AFTER `actual_retention_count`"));
        assertTrue(sql.contains("AFTER `actual_partition_summary_json`"));
        assertTrue(count(sql, "ADD COLUMN") == 2);
    }

    private static String readMigration() throws IOException {
        try (InputStream stream = LakeV1022MigrationContractTest.class.getResourceAsStream(
                "/db/migration/mysql/V1_0_22__add_lake_lifecycle_partition_observation.sql")) {
            if (stream == null) {
                throw new IOException("Missing V1.0.22 migration");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
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
