package org.apache.seatunnel.web.dao.plugin.mysql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeV1023MigrationContractTest {

    @Test
    void migrationAddsOnlyExternalCatalogObservationColumns() throws IOException {
        String sql = readMigration();

        assertTrue(sql.contains(
                "ALTER TABLE `t_seatunnel_web_lake_external_catalog_binding`"));
        assertTrue(sql.contains("`driver_checksum` char(64)"));
        assertTrue(sql.contains("AFTER `credential_revision`"));
        assertTrue(sql.contains("`actual_snapshot_json` longtext"));
        assertTrue(sql.contains("AFTER `validation_status`"));
        assertTrue(sql.contains("`last_observed_at` datetime"));
        assertTrue(sql.contains("AFTER `actual_snapshot_json`"));
        assertEquals(3, count(sql, "ADD COLUMN"));
        assertFalse(sql.toLowerCase().contains("create table"));
    }

    private static String readMigration() throws IOException {
        try (InputStream stream = LakeV1023MigrationContractTest.class.getResourceAsStream(
                "/db/migration/mysql/V1_0_23__add_lake_external_catalog_observation.sql")) {
            if (stream == null) {
                throw new IOException("Missing V1.0.23 migration");
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
