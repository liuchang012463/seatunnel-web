package org.apache.seatunnel.web.dao.plugin.h2;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class H2BootstrapMigrationTest {
    @Test
    void migratesAnEmptyDatabaseIncludingWebAndQuartzTables() throws Exception {
        String url = "jdbc:h2:mem:full_bootstrap;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        var flyway = Flyway.configure().dataSource(url, "sa", "")
                .table("t_seatunnel_web_schema_history").locations("classpath:db/migration/h2").outOfOrder(true).load();
        flyway.migrate();
        flyway.validate();
        assertEquals("1.0.58", flyway.info().current().getVersion().toString());
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var stmt = connection.createStatement()) {
            // Query real application columns and Quartz tables, not just SQL syntax.
            stmt.executeQuery("SELECT data_source_type, source_data_source_id, unit_id, business_system_id FROM t_seatunnel_web_lake_lifecycle_policy");
            stmt.executeQuery("SELECT exploration_pipeline_version, exploration_pipeline_retry_at FROM t_seatunnel_web_metadata_binding");
            stmt.executeQuery("SELECT system_managed, source_enabled, sink_enabled FROM t_seatunnel_web_datasource");
            stmt.executeQuery("SELECT schedule_config_json, target_database, datapack_config_json FROM t_seatunnel_web_offline_import_task");
            stmt.executeQuery("SELECT LOCK_NAME FROM QRTZ_LOCKS WHERE SCHED_NAME='test'");
            stmt.executeUpdate("INSERT INTO QRTZ_JOB_DETAILS (SCHED_NAME, JOB_NAME, JOB_GROUP, "
                    + "JOB_CLASS_NAME, IS_DURABLE, IS_NONCONCURRENT, IS_UPDATE_DATA, REQUESTS_RECOVERY) "
                    + "VALUES ('test', 'job', 'group', 'example.Job', TRUE, FALSE, FALSE, FALSE)");
            try (var rows = stmt.executeQuery("SELECT COUNT(*) FROM t_seatunnel_web_time_variable")) {
                assertTrue(rows.next());
                assertEquals(5, rows.getInt(1));
            }
        }
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void doesNotApplyBootstrapToAnExistingHistory() throws Exception {
        String url = "jdbc:h2:mem:existing_bootstrap;MODE=MySQL;DB_CLOSE_DELAY=-1";
        var flyway = Flyway.configure().dataSource(url, "sa", "")
                .table("t_seatunnel_web_schema_history").locations("classpath:db/migration/h2").baselineVersion("1.0.58").load();
        // Model a pre-existing history through Flyway's API, never edit history rows.
        flyway.baseline();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (var c = DriverManager.getConnection(url, "sa", "")) {
            assertFalse(c.getMetaData().getTables(null, null, "T_SEATUNNEL_WEB_DATASOURCE", null).next());
        }
    }

    @Test
    void releasesOnlyMatchingCompletedLeasesDuringUpgrade() throws Exception {
        String url = "jdbc:h2:mem:lake_completed_leases;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        var previous = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2").target("1.0.57").load();
        previous.migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            for (int id = 1; id <= 3; id++) {
                statement.executeUpdate("INSERT INTO t_seatunnel_web_lake_ods_database_binding "
                        + "(id, lake_data_source_id, source_data_source_id, unit_code, system_code, database_name, operation_token) "
                        + "VALUES (" + id + ", 10, " + id + ", 'unit', 'system', 'ods_" + id + "', 'lease_" + id + "')");
                statement.executeUpdate("INSERT INTO t_seatunnel_web_lake_resource_operation "
                        + "(id, resource_type, resource_id, generation, operation_type, operation_token, status) VALUES ("
                        + id + ", 'ODS_DATABASE_BINDING', " + (id == 3 ? 999 : id)
                        + ", 1, 'CREATE', 'lease_" + id + "', '" + (id == 2 ? "RUNNING" : "FAILED") + "')");
            }
            // Lifecycle creation uses the managed table's operation journal.
            statement.executeUpdate("INSERT INTO t_seatunnel_web_lake_table_lifecycle_binding "
                    + "(id, table_mapping_id, partition_column, granularity, operation_token) "
                    + "VALUES (10, 100, 'created_at', 'DAY', 'shared_lease')");
            statement.executeUpdate("INSERT INTO t_seatunnel_web_lake_resource_operation "
                    + "(id, resource_type, resource_id, generation, operation_type, operation_token, status) "
                    + "VALUES (10, 'ODS_TABLE_MAPPING', 100, 1, 'CREATE', 'shared_lease', 'FAILED')");
        }
        var current = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2").load();
        assertEquals(1, current.migrate().migrationsExecuted);
        current.validate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT id, operation_token, lock_version FROM t_seatunnel_web_lake_ods_database_binding ORDER BY id")) {
                assertTrue(rows.next());
                assertNull(rows.getString("operation_token"));
                assertEquals(2, rows.getInt("lock_version"));
                assertTrue(rows.next());
                assertEquals("lease_2", rows.getString("operation_token"));
                assertTrue(rows.next());
                assertEquals("lease_3", rows.getString("operation_token"));
            }
            try (var rows = statement.executeQuery("SELECT operation_token FROM t_seatunnel_web_lake_table_lifecycle_binding WHERE id=10")) {
                assertTrue(rows.next());
                assertNull(rows.getString(1));
            }
        }
    }
}
