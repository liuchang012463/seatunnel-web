package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void damengTriggerWithRealBodyStillStaysOneStatement() {
        String script = "CREATE TRIGGER trg BEFORE INSERT ON x FOR EACH ROW "
                + "DECLARE v INT; BEGIN IF NEW.id > 0 THEN NEW.id := 1; END IF; END;";
        List<String> statements = split(script + "\nSELECT 1;");
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE TRIGGER"));
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
    void missingEndIsToleratedAtScriptEnd() {
        // Unterminated block: everything stays one statement instead of crashing.
        List<String> statements = split("BEGIN SELECT 1;");
        assertEquals(1, statements.size());
    }
}
