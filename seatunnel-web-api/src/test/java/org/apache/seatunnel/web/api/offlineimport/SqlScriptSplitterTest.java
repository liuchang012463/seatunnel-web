package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlScriptSplitterTest {

    private List<String> split(String script) {
        return SqlScriptSplitter.split(script);
    }

    @Test
    void splitsPlainStatementsOnSemicolon() {
        List<String> statements = split("CREATE TABLE a (id int);\nINSERT INTO a VALUES (1);\n\nINSERT INTO a VALUES (2)");
        assertEquals(3, statements.size());
        assertEquals("CREATE TABLE a (id int)", statements.get(0));
        assertEquals("INSERT INTO a VALUES (2)", statements.get(2));
    }

    @Test
    void keepsSemicolonsInsideLineComments() {
        List<String> statements = split("INSERT INTO a VALUES (1); -- tricky ; comment\nINSERT INTO a VALUES (2);");
        assertEquals(2, statements.size());
    }

    @Test
    void keepsSemicolonsInsideBlockCommentsIncludingNested() {
        List<String> statements = split("/* outer ; /* nested ; */ still comment ; */ INSERT INTO a VALUES (1);\nSELECT 1;");
        assertEquals(2, statements.size());
    }

    @Test
    void keepsSemicolonsInsideSingleQuotedStrings() {
        List<String> statements = split("INSERT INTO a VALUES ('it''s ; here');\nSELECT 1;");
        assertEquals(2, statements.size());
        assertEquals("INSERT INTO a VALUES ('it''s ; here')", statements.get(0));
    }

    @Test
    void keepsSemicolonsInsideQuotedIdentifiers() {
        List<String> statements = split("SELECT \"col ; name\" FROM a;\nSELECT 1;");
        assertEquals(2, statements.size());
    }

    @Test
    void keepsDollarQuotedFunctionBodyAsOneStatement() {
        String script = "CREATE FUNCTION f() RETURNS void AS $$\n"
                + "BEGIN\n"
                + "  PERFORM 1;\n"
                + "  PERFORM 2;\n"
                + "END;\n"
                + "$$ LANGUAGE plpgsql;\n"
                + "SELECT 1;";
        List<String> statements = split(script);
        assertEquals(2, statements.size());
    }

    @Test
    void sqlFunctionBodyInSingleQuotedStringEndsBeforeFollowingStatement() {
        List<String> statements = split(
                "CREATE FUNCTION f() RETURNS integer AS 'SELECT 1' LANGUAGE sql; SELECT 2;");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE FUNCTION f()"));
        assertEquals("SELECT 2", statements.get(1));
    }

    @Test
    void keepsTaggedDollarQuotesSeparateFromUntagged() {
        String script = "DO $body$\n BEGIN\n  NULL;\n END\n $body$;\nSELECT '$$';";
        List<String> statements = split(script);
        assertEquals(2, statements.size());
    }

    @Test
    void keepsDamengProcedureWithDeclareSectionAsOneStatement() {
        String script = "CREATE OR REPLACE PROCEDURE p1 AS\n"
                + "v_id INT;\n"
                + "BEGIN\n"
                + "  INSERT INTO a VALUES (1);\n"
                + "  IF v_id > 0 THEN\n"
                + "    UPDATE a SET id = 2;\n"
                + "  END IF;\n"
                + "  FOR r IN (SELECT 1) LOOP\n"
                + "    DELETE FROM a;\n"
                + "  END LOOP;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("v_id INT;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsMultipleDamengDeclarationsAndTheirSemicolonsInOneProcedure() {
        String script = "CREATE PROCEDURE p AS a INT; b INT; BEGIN NULL; END; SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("a INT; b INT; BEGIN"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsAnonymousBlockWithDeclareAsOneStatement() {
        String script = "DECLARE v INT; BEGIN INSERT INTO a VALUES (v); END;\nSELECT 1;";
        List<String> statements = split(script);
        assertEquals(2, statements.size());
    }

    @Test
    void caseExpressionInsidePlainStatementDoesNotConsumeNextSplit() {
        List<String> statements = split("SELECT CASE WHEN a = 1 THEN 'x' ELSE 'y' END FROM t;\nSELECT 2;");
        assertEquals(2, statements.size());
        assertEquals("SELECT 2", statements.get(1));
    }

    @Test
    void endCaseDoesNotReopenTheCaseBlock() {
        List<String> statements = split(
                "CREATE PROCEDURE p AS BEGIN CASE WHEN 1 = 1 THEN NULL; END CASE; END; SELECT 1;");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("END CASE;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void kingbaseTransactionBeginDoesNotSwallowFollowingStatements() {
        List<String> statements = split(
                "BEGIN;\nINSERT INTO a VALUES (1);\nINSERT INTO b VALUES (2);\nCOMMIT;\nSELECT 1;");
        assertEquals(5, statements.size());
        assertEquals("BEGIN", statements.get(0));
        assertEquals("COMMIT", statements.get(3));
        assertEquals("SELECT 1", statements.get(4));
    }

    @Test
    void beginWorkAndBeginTransactionAreAlsoTransactionControl() {
        assertEquals(3, split("BEGIN WORK; SELECT 1; BEGIN TRANSACTION;").size());
    }

    @Test
    void pgStyleBareTriggerReleasesThePendingUnit() {
        List<String> statements = split(
                "CREATE TABLE x (id int); "
                        + "CREATE TRIGGER trg AFTER INSERT ON x FOR EACH ROW EXECUTE PROCEDURE fn(); "
                        + "INSERT INTO x VALUES (1); "
                        + "INSERT INTO x VALUES (2);");
        assertEquals(4, statements.size());
        assertTrue(statements.get(1).startsWith("CREATE TRIGGER"));
        assertEquals("INSERT INTO x VALUES (1)", statements.get(2));
    }

    @Test
    void bodylessTriggerDetectionUsesTokensAcrossWhitespaceAndComments() {
        List<String> statements = split("CREATE\nTRIGGER trg AFTER INSERT ON x "
                + "/* AS this is only a comment */ FOR EACH ROW EXECUTE FUNCTION fn(); SELECT 2;");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE\nTRIGGER"));
        assertEquals("SELECT 2", statements.get(1));
    }

    @Test
    void triggerWhenIsNullDoesNotLookLikeAnInlineTriggerBody() {
        List<String> statements = split("CREATE TRIGGER trg AFTER INSERT ON t FOR EACH ROW "
                + "WHEN (NEW.id IS NOT NULL) EXECUTE FUNCTION fn(); SELECT 2;");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("WHEN (NEW.id IS NOT NULL)"));
        assertEquals("SELECT 2", statements.get(1));
    }

    @Test
    void sqlProcedureSingleQuotedBodyEndsBeforeFollowingStatement() {
        List<String> statements = split(
                "CREATE PROCEDURE p() LANGUAGE SQL AS 'INSERT INTO t VALUES (1)'; SELECT 2;");

        assertEquals(2, statements.size());
        assertEquals("SELECT 2", statements.get(1));
    }

    @Test
    void damengTriggerWithRealBodyStillStaysOneStatement() {
        String script = "CREATE TRIGGER trg BEFORE INSERT ON x FOR EACH ROW "
                + "DECLARE v INT; BEGIN IF NEW.id > 0 THEN NEW.id := 1; END IF; END;";
        List<String> statements = split(script + "\nSELECT 1;");
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE TRIGGER"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void standaloneDamengSlashTerminatesInsteadOfBeingSentToJdbc() {
        List<String> statements = split("CREATE PROCEDURE p AS BEGIN NULL; END;\n/\nSELECT 1;");

        assertEquals(2, statements.size());
        assertTrue(statements.stream().noneMatch("/"::equals));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsNestedProcedureAndPackageEndTogetherUntilSlash() {
        String script = "CREATE OR REPLACE PACKAGE BODY p AS "
                + "PROCEDURE q AS BEGIN NULL; END; END;\n/\nSELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE q AS BEGIN NULL; END; END"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsPackageInitializationBlockAndFinalEndTogetherUntilSlash() {
        String script = "CREATE OR REPLACE PACKAGE BODY p AS\n"
                + "PROCEDURE q AS BEGIN NULL; END;\n"
                + "BEGIN NULL; END p;\n/\nSELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE q AS BEGIN NULL; END;"));
        assertTrue(statements.get(0).contains("BEGIN NULL; END p;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void createTableDoesNotOpenABlock() {
        List<String> statements = split("CREATE TABLE b (id int);\nCREATE INDEX i ON b (id);");
        assertEquals(2, statements.size());
    }

    @Test
    void dropsCommentOnlyFragments() {
        List<String> statements = split("-- only a comment\n;\n/* another */\nSELECT 1;");
        assertEquals(1, statements.size());
        // The block comment rides along with the statement it precedes.
        assertEquals("/* another */\nSELECT 1", statements.get(0));
    }

    @Test
    void emptyAndNullScriptsProduceNothing() {
        assertEquals(0, split(null).size());
        assertEquals(0, split("").size());
        assertEquals(0, split("   \n -- x \n ").size());
    }

    @Test
    void preservesCopyPayloadAsOpaqueDataAndSeparatesClientDirectives() {
        String script = "-- pg_dump header\n"
                + "\\restrict dump-key\n"
                + "CREATE TABLE src.copy_sample (payload text);\n"
                + "COPY src.copy_sample (payload) FROM stdin;\n"
                + "value;with;semicolons\n"
                + "\\connect this-is-copy-data\n"
                + "\\.\n"
                + "SELECT 1;\n"
                + "\\unrestrict dump-key\n";

        List<SqlScriptSplitter.Statement> parsed = SqlScriptSplitter.parse(script);

        assertEquals(5, parsed.size());
        assertTrue(parsed.get(0).isDirective());
        assertEquals("\\restrict dump-key", parsed.get(0).directive());
        assertEquals("CREATE TABLE src.copy_sample (payload text)", parsed.get(1).sql());
        assertTrue(parsed.get(2).isCopy());
        assertEquals("COPY src.copy_sample (payload) FROM stdin", parsed.get(2).sql());
        assertEquals("value;with;semicolons\n\\connect this-is-copy-data\n", parsed.get(2).copyData());
        assertEquals("SELECT 1", parsed.get(3).sql());
        assertEquals("\\unrestrict dump-key", parsed.get(4).directive());
    }

    @Test
    void rejectsCopyInputWithoutTerminator() {
        assertThrows(IllegalArgumentException.class, () -> SqlScriptSplitter.parse(
                "COPY src.t FROM stdin;\nvalue\nSELECT 1;"));
    }

    @Test
    void missingEndIsToleratedAtScriptEnd() {
        // Unterminated block: everything stays one statement instead of crashing.
        List<String> statements = split("BEGIN SELECT 1;");
        assertEquals(1, statements.size());
    }
}
