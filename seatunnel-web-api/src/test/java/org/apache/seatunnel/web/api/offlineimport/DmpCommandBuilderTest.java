package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmpCommandBuilderTest {

    private static final Path DMP = Paths.get("/work/task-1/export.dmp");
    private static final Path LOG = Paths.get("/work/task-1/export.log");

    @Test
    void damengCommandUsesUseridFirstAndSchemaRemap() {
        DmpCommandBuilder.DmpCommand command = DmpCommandBuilder.dameng(
                "/opt/dmdbms/bin/dimp", "192.168.100.95", "5236", "test", "Pwd@123",
                DMP, LOG, "SRC", "DST", true);

        assertEquals("/opt/dmdbms/bin/dimp", command.command().get(0));
        assertEquals("USERID=test/Pwd@123@192.168.100.95:5236", command.command().get(1));
        assertEquals("FILE=export.dmp", command.command().get(2));
        assertEquals("DIRECTORY=/work/task-1", command.command().get(4));
        assertEquals("IGNORE=Y", command.command().get(5));
        assertTrue(command.command().contains("SCHEMAS=SRC"));
        assertTrue(command.command().contains("REMAP_SCHEMA=SRC:DST"));
        assertTrue(command.environment().isEmpty());
    }

    @Test
    void damengCommandWithOnlyTargetSchemaSkipsRemap() {
        DmpCommandBuilder.DmpCommand command = DmpCommandBuilder.dameng(
                "/opt/dmdbms/bin/dimp", "h", "5236", "u", "p", DMP, LOG, null, "TEST", false);

        assertTrue(command.command().contains("SCHEMAS=TEST"));
        assertFalse(command.command().toString().contains("REMAP_SCHEMA"));
        assertEquals("IGNORE=N", command.command().get(5));
    }

    @Test
    void kingbaseCommandPassesPasswordThroughEnvironmentOnly() {
        DmpCommandBuilder.DmpCommand command = DmpCommandBuilder.kingbase(
                "/opt/Kingbase/ES/V8/bin/sys_restore", "192.168.100.91", "54321", "kingbase",
                "system", "Secret#9", DMP, "public");

        assertEquals("/opt/Kingbase/ES/V8/bin/sys_restore", command.command().get(0));
        int userIndex = command.command().indexOf("-U");
        assertEquals("system", command.command().get(userIndex + 1));
        int dbIndex = command.command().indexOf("-d");
        assertEquals("kingbase", command.command().get(dbIndex + 1));
        assertTrue(command.command().contains("--no-owner"));
        assertTrue(command.command().contains("--schema=public"));
        assertEquals(DMP.toAbsolutePath().toString(),
                command.command().get(command.command().size() - 1));
        assertEquals(Map.of("PGPASSWORD", "Secret#9"), command.environment());
        assertFalse(String.join(" ", command.command()).contains("Secret#9"));
    }

    @Test
    void kingbaseCommandOmitsSchemaFlagWhenBlank() {
        DmpCommandBuilder.DmpCommand command = DmpCommandBuilder.kingbase(
                "/usr/bin/sys_restore", "h", "5432", "db", "u", "p", DMP, " ");

        assertFalse(String.join(" ", command.command()).contains("--schema"));
    }

    @Test
    void maskHidesDamengPassword() {
        DmpCommandBuilder.DmpCommand command = DmpCommandBuilder.dameng(
                "/opt/dmdbms/bin/dimp", "h", "5236", "test", "TopSecret", DMP, LOG, null, null, true);

        String masked = DmpCommandBuilder.mask(command, "TopSecret");
        assertTrue(masked.contains("USERID=test/******@h:5236"));
        assertFalse(masked.contains("TopSecret"));
    }
}
