package org.apache.seatunnel.web.api.offlineimport;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlImportPlanTest {

    @Test
    void plansKingbaseCopyAndAllowsOnlySafeDumpMetadataDirectives() {
        String script = "\\restrict token123\n"
                + "COPY SRC.t (id, note) FROM stdin;\n"
                + "1\tsemicolon; and\\connect still data\n"
                + "\\.\n"
                + "\\unrestrict\n";
        SqlImportPlan.Plan plan = SqlImportPlan.prepare(script, DbType.KINGBASE, "SRC", "DST");

        assertEquals(1, plan.executableCount());
        assertEquals(2, plan.ignoredMetadata());
        SqlScriptSplitter.Statement copy = plan.statements().get(0);
        assertTrue(copy.isCopy());
        assertEquals("\"DST\".t", copy.sql().substring("COPY ".length(), copy.sql().indexOf(" (")));
        assertTrue(copy.copyData().contains("semicolon; and\\connect still data"));
    }

    @Test
    void rejectsClientCommandsBeforeExecution() {
        for (String directive : List.of("\\! touch /tmp/not-run", "\\connect other_db", "\\i /tmp/extra.sql",
                "@/tmp/extra.sql", "\\copy t from program 'echo bad'")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlImportPlan.prepare(directive + "\nSELECT 1;", DbType.KINGBASE, null, "DST"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare("CREATE DATABASE other;", DbType.KINGBASE, null, "DST"));
    }

    @Test
    void mapsSchemaSwitchesAndSequenceReferencesButPreservesDataAndOwnerMetadata() {
        String script = "CREATE SCHEMA SRC;\n"
                + "SET search_path = 'SRC, pg_catalog';\n"
                + "CREATE TABLE SRC.t(id INT DEFAULT nextval('SRC.seq'::regclass), note TEXT);\n"
                + "INSERT INTO SRC.t VALUES (1, 'SRC.seq');\n"
                + "ALTER TABLE SRC.t OWNER TO old_user;\n"
                + "GRANT SELECT ON SRC.t TO old_user;\n";
        SqlImportPlan.Plan plan = SqlImportPlan.prepare(script, DbType.KINGBASE, "SRC", "DST");

        assertEquals(3, plan.executableCount());
        assertEquals(3, plan.ignoredMetadata());
        assertTrue(plan.statements().get(0).sql().contains("SET search_path TO \"DST\", pg_catalog"));
        assertTrue(plan.statements().get(1).sql().contains("CREATE TABLE \"DST\".t"));
        assertTrue(plan.statements().get(1).sql().contains("nextval('\"DST\".seq'::regclass)"));
        assertTrue(plan.statements().get(2).sql().contains("'SRC.seq'"));
    }

    @Test
    void mapsQuotedSearchPathEntriesAndCatalogSetConfig() {
        SqlImportPlan.Plan kingbase = SqlImportPlan.prepare(
                "SET search_path = \"SRC\", \"pg_catalog\"; SELECT 1;",
                DbType.KINGBASE, "SRC", "DST");
        assertEquals("SET search_path TO \"DST\", pg_catalog", kingbase.statements().get(0).sql());

        SqlImportPlan.Plan setConfig = SqlImportPlan.prepare(
                "SELECT sys_catalog.set_config('search_path', 'SRC, pg_catalog', false); SELECT 1;",
                DbType.KINGBASE, "SRC", "DST");
        assertTrue(setConfig.statements().get(0).sql()
                .startsWith("SELECT sys_catalog.set_config('search_path', '\"DST\", pg_catalog'"));
    }

    @Test
    void mapsNativeDumpDefaultPublicSearchPathToSelectedTarget() {
        SqlImportPlan.Plan plan = SqlImportPlan.prepare(
                "SELECT pg_catalog.set_config('search_path', 'public', false);",
                DbType.KINGBASE, "codex_sql_src_20261027_01", "codex_sql_dst_20261027_01");

        assertEquals("SELECT pg_catalog.set_config('search_path', '\"codex_sql_dst_20261027_01\"', false)",
                plan.statements().get(0).sql());
    }

    @Test
    void rejectsAnExplicitNonTargetSchemaWhenSourceIsMissingOrMismatched() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare("CREATE TABLE SRC.t(id INT);", DbType.KINGBASE, null, "DST"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare("CREATE TABLE OTHER.t(id INT);", DbType.KINGBASE, "SRC", "DST"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare("SET SCHEMA SRC; CREATE TABLE SRC.t(id INT);",
                        DbType.DAMENG, null, "DST"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare("CREATE FUNCTION f() RETURNS trigger AS $$ BEGIN "
                                + "INSERT INTO SRC.t VALUES (NEW.id); RETURN NEW; END $$ LANGUAGE plpgsql;",
                        DbType.KINGBASE, null, "DST"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare("TRUNCATE TABLE DST.t, SRC.t;", DbType.KINGBASE, null, "DST"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare("SELECT sys_catalog.setval('SRC.seq', 42, true);",
                        DbType.KINGBASE, null, "DST"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare("COMMENT ON TABLE SRC.t IS 'note';",
                        DbType.KINGBASE, null, "DST"));
    }

    @Test
    void rejectsDynamicRoutineMappingAndNonKingbaseCopyBeforeWriting() {
        String dynamic = "CREATE FUNCTION SRC.f() RETURNS void AS $$ BEGIN EXECUTE 'SELECT * FROM SRC.t'; END $$ LANGUAGE plpgsql;";
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare(dynamic, DbType.KINGBASE, "SRC", "DST"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare(dynamic, DbType.KINGBASE, null, "DST"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlImportPlan.prepare("COPY SRC.t FROM stdin;\n1\n\\.\n", DbType.DAMENG, "SRC", "DST"));
    }

    @Test
    void rewritesSchemaSwitchesAfterLeadingCommentsAndDoesNotScanDataForCommands() {
        SqlImportPlan.Plan dameng = SqlImportPlan.prepare(
                "-- exported schema\nSET SCHEMA SRC; CREATE TABLE t(id INT);",
                DbType.DAMENG, "SRC", "DST");
        assertTrue(dameng.statements().get(0).sql().contains("SET SCHEMA \"DST\""));

        SqlImportPlan.Plan kingbase = SqlImportPlan.prepare(
                "/* export header */ SET search_path=SRC; CREATE TABLE t(id INT);",
                DbType.KINGBASE, "SRC", "DST");
        assertTrue(kingbase.statements().get(0).sql().contains("SET search_path TO \"DST\""));

        SqlImportPlan.Plan data = SqlImportPlan.prepare(
                "INSERT INTO SRC.t VALUES ('SET ROLE admin');",
                DbType.KINGBASE, "SRC", "DST");
        assertTrue(data.statements().get(0).sql().contains("'SET ROLE admin'"));
    }
}
