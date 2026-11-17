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
        assertEquals("1.0.55", flyway.info().current().getVersion().toString());
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var stmt = connection.createStatement()) {
            // Query real application columns and Quartz tables, not just SQL syntax.
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
                .table("t_seatunnel_web_schema_history").locations("classpath:db/migration/h2").baselineVersion("1.0.55").load();
        // Model a pre-existing history through Flyway's API, never edit history rows.
        flyway.baseline();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (var c = DriverManager.getConnection(url, "sa", "")) {
            assertFalse(c.getMetaData().getTables(null, null, "T_SEATUNNEL_WEB_DATASOURCE", null).next());
        }
    }
}
