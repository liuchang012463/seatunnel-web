package org.apache.seatunnel.web.api.lake.operation;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies that the production boundary really commits/rolls back one local phase. */
class SpringLakeOperationTransactionBoundaryTest {

    @Test
    void resourceAndJournalUpdatesRollbackTogether() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:lake_operation_boundary;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS resource_state (id INT PRIMARY KEY, state VARCHAR(32))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS operation_journal (id INT PRIMARY KEY, state VARCHAR(32))");
        jdbc.update("MERGE INTO resource_state KEY(id) VALUES (1, 'CREATING')");
        jdbc.update("MERGE INTO operation_journal KEY(id) VALUES (1, 'RUNNING')");

        SpringLakeOperationTransactionBoundary boundary = new SpringLakeOperationTransactionBoundary(
                new DataSourceTransactionManager(dataSource));
        assertThrows(LakeOperationException.class, () -> boundary.requiresNew(() -> {
            jdbc.update("UPDATE resource_state SET state = 'READY' WHERE id = 1");
            jdbc.update("UPDATE operation_journal SET state = 'SUCCEEDED' WHERE id = 1");
            throw new LakeOperationException("force rollback");
        }));

        assertEquals("CREATING", jdbc.queryForObject(
                "SELECT state FROM resource_state WHERE id = 1", String.class));
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT state FROM operation_journal WHERE id = 1", String.class));
    }
}
