package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSchemaMapperTest {

    @Test
    void mapsQualifiedObjectsAndSequenceReferencesWithoutChangingDataStringsOrComments() {
        String sql = "ALTER TABLE ONLY SRC.orders ALTER COLUMN id SET DEFAULT nextval('SRC.orders_id_seq'::regclass);\n"
                + "SELECT pg_catalog.setval('SRC.orders_id_seq', 42, true);\n"
                + "INSERT INTO SRC.orders(note) VALUES ('SRC.orders_id_seq'); -- SRC.keep\n";

        String mapped = SqlSchemaMapper.rewrite(sql, "SRC", "DST");

        assertTrue(mapped.contains("ALTER TABLE ONLY \"DST\".orders"));
        assertTrue(mapped.contains("nextval('\"DST\".orders_id_seq'::regclass)"));
        assertTrue(mapped.contains("setval('\"DST\".orders_id_seq', 42, true)"));
        assertTrue(mapped.contains("INSERT INTO \"DST\".orders(note) VALUES ('SRC.orders_id_seq')"));
        assertTrue(mapped.contains("-- SRC.keep"));
    }

    @Test
    void rewritesKnownRoutineBodiesButLeavesDollarQuotedDataAndAliasesAlone() {
        String function = "CREATE FUNCTION SRC.f() RETURNS text AS $$ "
                + "SELECT SRC.t.name FROM SRC.t; -- SRC.comment\n"
                + "$$ LANGUAGE sql";
        String mapped = SqlSchemaMapper.rewrite(function, "SRC", "DST");
        assertTrue(mapped.contains("CREATE FUNCTION \"DST\".f()"));
        assertTrue(mapped.contains("SELECT \"DST\".t.name FROM \"DST\".t"));
        assertTrue(mapped.contains("-- SRC.comment"));

        String data = "INSERT INTO DST.t(value) VALUES ($$SRC.t$$)";
        assertEquals(data, SqlSchemaMapper.rewrite(data, "SRC", "DST"));

        String alias = "SELECT SRC.id FROM SRC.table_name SRC";
        assertEquals("SELECT SRC.id FROM \"DST\".table_name SRC",
                SqlSchemaMapper.rewrite(alias, "SRC", "DST"));
    }

    @Test
    void mapsSingleQuotedSqlFunctionBodyAndRejectsDynamicRemapping() {
        String function = "CREATE FUNCTION SRC.f() RETURNS integer AS 'SELECT SRC.t.id FROM SRC.t' LANGUAGE sql";
        assertTrue(SqlSchemaMapper.rewrite(function, "SRC", "DST")
                .contains("AS 'SELECT \"DST\".t.id FROM \"DST\".t'"));

        String dynamic = "CREATE FUNCTION SRC.f() RETURNS void AS $$ BEGIN EXECUTE 'SELECT * FROM SRC.t'; END $$ LANGUAGE plpgsql";
        assertThrows(IllegalArgumentException.class, () -> SqlSchemaMapper.rewrite(dynamic, "SRC", "DST"));
    }

    @Test
    void detectsExplicitWriteSchemasIncludingSchemaDeclarations() {
        assertEquals(Set.of("SRC"), SqlSchemaMapper.writeSchemas("CREATE SCHEMA IF NOT EXISTS SRC"));
        assertEquals(Set.of("SRC"), SqlSchemaMapper.writeSchemas("ALTER SCHEMA SRC OWNER TO old_user"));
        assertEquals(Set.of("SRC"), SqlSchemaMapper.writeSchemas("CREATE INDEX idx ON SRC.table_name(id)"));
        assertEquals(Set.of("SRC"), SqlSchemaMapper.writeSchemas("TRUNCATE SRC.table_name"));
        assertEquals(Set.of("SRC"), SqlSchemaMapper.writeSchemas("TRUNCATE TABLE SRC.table_name"));
    }

    @Test
    void mapsQuotedSchemaAndQuotedSequenceReferencesToCaseSensitiveTarget() {
        String schema = SqlSchemaMapper.rewrite("CREATE SCHEMA \"SRC\"", "SRC", "MiXeD");
        String sequence = SqlSchemaMapper.rewrite(
                "SELECT pg_catalog.setval('\"SRC\".\"SEQ\"', 7, true)", "SRC", "MiXeD");

        assertEquals("CREATE SCHEMA \"MiXeD\"", schema);
        assertTrue(sequence.contains("setval('\"MiXeD\".\"SEQ\"'"));
    }

    @Test
    void bodylessTriggerIsNotMistakenForRoutineWhenCondition() {
        String sql = "CREATE TRIGGER trg AFTER INSERT ON SRC.t FOR EACH ROW "
                + "WHEN (NEW.id IS NOT NULL) EXECUTE FUNCTION SRC.fn()";

        String mapped = SqlSchemaMapper.rewrite(sql, "SRC", "DST");

        assertTrue(mapped.contains("WHEN (NEW.id IS NOT NULL)"));
        assertTrue(mapped.contains("EXECUTE FUNCTION \"DST\".fn()"));
    }

    @Test
    void detectsExplicitWritesInsideDollarQuotedRoutineBodies() {
        String sql = "CREATE FUNCTION f() RETURNS trigger AS $$ BEGIN "
                + "INSERT INTO SRC.t VALUES (NEW.id); RETURN NEW; END $$ LANGUAGE plpgsql";

        assertEquals(Set.of("SRC"), SqlSchemaMapper.writeSchemas(sql));
    }
}
