package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyDataConverterTest {

    private static Connection connection;

    @BeforeAll
    static void openH2() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:copyConverter;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE copy_target (id INT, name VARCHAR(100))");
            statement.execute("CREATE TABLE \"Copy Target\" (id INT, name VARCHAR(100))");
        }
    }

    @AfterAll
    static void closeH2() throws SQLException {
        connection.close();
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE copy_target");
        }
    }

    private int countRows() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM copy_target")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void convertsTextCopyBlockWithNullMarker() throws Exception {
        String copy = "COPY copy_target (id, name) FROM stdin;\n"
                + "1\tAlice\n"
                + "2\t\\N\n"
                + "\\.\n";
        try (Statement statement = connection.createStatement()) {
            assertEquals(2, CopyDataConverter.execute(statement, copy));
        }
        assertEquals(2, countRows());
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM copy_target WHERE id = 2")) {
            assertTrue(rs.next());
            assertEquals(null, rs.getString(1));
        }
    }

    @Test
    void convertsCsvCopyBlockWithQuotedCommaAndEmptyString() throws Exception {
        String copy = "COPY copy_target (id, name) FROM stdin WITH (FORMAT csv);\n"
                + "3,\"Bob, Jr.\"\n"
                + "4,\"\"\n"
                + "5,\n"
                + "\\.\n";
        try (Statement statement = connection.createStatement()) {
            assertEquals(3, CopyDataConverter.execute(statement, copy));
        }
        assertEquals(3, countRows());
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM copy_target WHERE id = 3")) {
            assertTrue(rs.next());
            assertEquals("Bob, Jr.", rs.getString(1));
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM copy_target WHERE id = 4")) {
            assertTrue(rs.next());
            assertEquals("", rs.getString(1));
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM copy_target WHERE id = 5")) {
            assertTrue(rs.next());
            assertEquals(null, rs.getString(1));
        }
    }

    @Test
    void batchesLargeCopyBlocks() throws Exception {
        StringBuilder copy = new StringBuilder("COPY copy_target (id, name) FROM stdin;\n");
        for (int i = 1; i <= 205; i++) {
            copy.append(i).append("\tname-").append(i).append('\n');
        }
        copy.append("\\.\n");
        try (Statement statement = connection.createStatement()) {
            assertEquals(205, CopyDataConverter.execute(statement, copy.toString()));
        }
        assertEquals(205, countRows());
    }

    @Test
    void supportsQuotedTableNameWithSpacesAndMultiLineHeader() throws Exception {
        String copy = "COPY \"Copy Target\" (\n"
                + "  id,\n"
                + "  name\n"
                + ") FROM stdin;\n"
                + "8\tDave\n"
                + "\\.\n";
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, CopyDataConverter.execute(statement, copy));
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM \"Copy Target\" WHERE id = 8")) {
            assertTrue(rs.next());
            assertEquals("Dave", rs.getString(1));
        }
    }

    @Test
    void supportsDelimiterNullMarkerAndHeaderOptions() throws Exception {
        String copy = "COPY copy_target (id, name) FROM stdin "
                + "WITH (DELIMITER '|', NULL 'NULL', HEADER);\n"
                + "id|name\n"
                + "6|Carol\n"
                + "7|NULL\n"
                + "\\.\n";
        try (Statement statement = connection.createStatement()) {
            assertEquals(2, CopyDataConverter.execute(statement, copy));
        }
        assertEquals(2, countRows());
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM copy_target WHERE id = 7")) {
            assertTrue(rs.next());
            assertEquals(null, rs.getString(1));
        }
    }

    @Test
    void headerFalseKeepsTheFirstDataRow() throws Exception {
        String copy = "COPY copy_target (id, name) FROM stdin "
                + "WITH (FORMAT csv, HEADER false);\n"
                + "9,Eve\n"
                + "\\.\n";
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, CopyDataConverter.execute(statement, copy));
        }
        assertEquals(1, countRows());
    }

    @Test
    void decodesEscapedDelimiterOption() throws Exception {
        String copy = "COPY copy_target (id, name) FROM stdin "
                + "WITH (DELIMITER E'\\x7c');\n"
                + "10|Frank\n"
                + "\\.\n";
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, CopyDataConverter.execute(statement, copy));
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM copy_target WHERE id = 10")) {
            assertTrue(rs.next());
            assertEquals("Frank", rs.getString(1));
        }
    }

    @Test
    void doesNotTreatCopyWordsInsideAStringAsACopyBlock() {
        assertFalse(CopyDataConverter.isCopyFromStdin(
                "INSERT INTO audit(msg) VALUES ('COPY t FROM stdin')"));
        assertTrue(CopyDataConverter.isCopyFromStdin("COPY t FROM stdin;\n1\n\\.\n"));
    }

    @Test
    void rejectsTruncatedCopyBlock() {
        String copy = "COPY copy_target (id, name) FROM stdin;\n1\tAlice\n";
        try (Statement statement = connection.createStatement()) {
            SQLException exception = assertThrows(
                    SQLException.class, () -> CopyDataConverter.execute(statement, copy));
            assertTrue(exception.getMessage().contains("结束标记"));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void rejectsBinaryCopyBlock() throws Exception {
        String copy = "COPY copy_target (id, name) FROM stdin WITH (FORMAT binary);\n\\.\n";
        try (Statement statement = connection.createStatement()) {
            SQLException exception = assertThrows(
                    SQLException.class, () -> CopyDataConverter.execute(statement, copy));
            assertTrue(exception.getMessage().contains("BINARY"));
        }
    }
}
