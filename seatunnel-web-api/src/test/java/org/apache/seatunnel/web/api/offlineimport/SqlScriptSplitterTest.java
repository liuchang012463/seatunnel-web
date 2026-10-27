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

    @Test
    void keepsEveryDeclarationSemicolonInAMultiDeclarationProcedure() {
        String script = "CREATE OR REPLACE PROCEDURE p AS\n"
                + "  v1 INT;\n"
                + "  v2 VARCHAR(20);\n"
                + "BEGIN\n"
                + "  v1 := 1;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("v1 INT;"));
        assertTrue(statements.get(0).contains("v2 VARCHAR(20);"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void endCaseDoesNotSwallowFollowingStatements() {
        String script = "BEGIN\n"
                + "  CASE WHEN 1 = 1 THEN NULL; END CASE;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("END CASE"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsDamengPackageBodyAsOneStatement() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p1 IS BEGIN NULL; END;\n"
                + "  PROCEDURE p2 IS BEGIN NULL; END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE p1"));
        assertTrue(statements.get(0).contains("PROCEDURE p2"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsPackageSpecAsOneStatement() {
        String script = "CREATE OR REPLACE PACKAGE pkg AS\n"
                + "  PROCEDURE p1;\n"
                + "  FUNCTION f1 RETURN INT;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE p1;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void transactionBeginWithIsolationLevelIsNotABlock() {
        List<String> statements = split(
                "BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;\n"
                        + "INSERT INTO a VALUES (1);\n"
                        + "COMMIT;");
        assertEquals(3, statements.size());
        assertEquals("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE", statements.get(0));
        assertEquals("COMMIT", statements.get(2));
    }

    @Test
    void transactionBeginFollowedByCommentIsStillTransactionControl() {
        List<String> statements = split("BEGIN /* comment */;\nINSERT INTO a VALUES (1);\nCOMMIT;");
        assertEquals(3, statements.size());
        assertEquals("BEGIN /* comment */", statements.get(0));
    }

    @Test
    void endFollowedByCommentThenIfDoesNotCloseTheBlock() {
        String script = "BEGIN\n"
                + "  IF 1 = 1 THEN NULL; END /* note */ IF;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void bodylessFunctionDoesNotSwallowTheFollowingTransactionBegin() {
        String script = "CREATE FUNCTION f() RETURNS int AS 'select 1' LANGUAGE sql;\n"
                + "BEGIN;\n"
                + "INSERT INTO a VALUES (1);\n"
                + "COMMIT;";
        List<String> statements = split(script);

        assertEquals(4, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE FUNCTION"));
        assertEquals("BEGIN", statements.get(1));
        assertEquals("COMMIT", statements.get(3));
    }

    @Test
    void keepsSemicolonInsideEString() {
        List<String> statements = split("INSERT INTO a VALUES (E'it\\'s; ok');\nSELECT 1;");
        assertEquals(2, statements.size());
        assertEquals("INSERT INTO a VALUES (E'it\\'s; ok')", statements.get(0));
    }

    @Test
    void keepsSemicolonInsideAlternativeQuotedString() {
        List<String> statements = split("INSERT INTO a VALUES (q'[it's; ok]');\nSELECT 1;");
        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsCopyFromStdinDataBlockTogether() {
        String script = "COPY public.t (id, name) FROM stdin;\n"
                + "1\thello;world\n"
                + "2\tsecond\n"
                + "\\.\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("COPY public.t"));
        assertTrue(statements.get(0).contains("hello;world"));
        assertTrue(statements.get(0).endsWith("\\."));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void skipsStandaloneSlashTerminators() {
        String script = "CREATE OR REPLACE PROCEDURE p AS BEGIN NULL; END;\n/\nSELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE OR REPLACE PROCEDURE"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsNestedProcedureInsideAProcedure() {
        String script = "CREATE OR REPLACE PROCEDURE outer_p AS\n"
                + "  PROCEDURE inner_p AS BEGIN NULL; END;\n"
                + "BEGIN\n"
                + "  inner_p;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE inner_p"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsTerminatingSemicolonOnPlsqlUnits() {
        List<String> statements = split(
                "CREATE OR REPLACE PROCEDURE p AS BEGIN NULL; END;\n"
                        + "DECLARE v INT; BEGIN NULL; END;\n"
                        + "SELECT 1;");

        assertEquals(3, statements.size());
        assertTrue(statements.get(0).endsWith("END;"));
        assertTrue(statements.get(1).endsWith("END;"));
        assertEquals("SELECT 1", statements.get(2));
    }

    @Test
    void keepsPackageBodyWithNestedDeclarationsAsOneStatement() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p IS v INT; BEGIN NULL; END;\n"
                + "  FUNCTION f RETURN INT IS v INT; w INT; BEGIN RETURN 1; END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE p IS v INT;"));
        assertTrue(statements.get(0).contains("FUNCTION f RETURN INT"));
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsTypeBodyWithMemberDeclarationsAsOneStatement() {
        String script = "CREATE OR REPLACE TYPE BODY t AS\n"
                + "  MEMBER PROCEDURE p IS v INT; BEGIN NULL; END;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("MEMBER PROCEDURE p"));
        assertTrue(statements.get(0).endsWith("END;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsNestedProcedureWithDeclarationsInsideAProcedure() {
        String script = "CREATE OR REPLACE PROCEDURE outer_p AS\n"
                + "  PROCEDURE inner_p IS v INT; BEGIN NULL; END;\n"
                + "BEGIN\n"
                + "  inner_p;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE inner_p IS v INT;"));
        assertTrue(statements.get(0).endsWith("END;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void packageSpecForwardDeclarationsStayInsideThePackage() {
        String script = "CREATE OR REPLACE PACKAGE pkg AS\n"
                + "  PROCEDURE p1;\n"
                + "  FUNCTION f1 RETURN INT;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE p1;"));
        assertTrue(statements.get(0).endsWith("END pkg;"));
    }

    @Test
    void doesNotSwallowInsertContainingCopyWords() {
        List<String> statements = split(
                "INSERT INTO audit(msg) VALUES ('COPY t FROM stdin');\n"
                        + "INSERT INTO b VALUES (2);");
        assertEquals(2, statements.size());
        assertEquals("INSERT INTO b VALUES (2)", statements.get(1));
    }

    @Test
    void keepsDollarInsideAnIdentifier() {
        List<String> statements = split("CREATE TABLE t (a$b$ INT);\nSELECT 1;");
        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsSemicolonInsideNqQuote() {
        List<String> statements = split("INSERT INTO a VALUES (NQ'[it's; ok]');\nSELECT 1;");
        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void beginAsIdentifierInSelectDoesNotOpenABlock() {
        List<String> statements = split("SELECT begin FROM t;\nSELECT 1;");
        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsDivisionSlashOnItsOwnLine() {
        List<String> statements = split("SELECT 4\n/\n2;\nSELECT 1;");
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("/"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsPackageBodyWithLocalTypeDeclaration() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p IS\n"
                + "    v INT;\n"
                + "    TYPE t IS RECORD (a INT);\n"
                + "  BEGIN NULL; END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("TYPE t IS RECORD"));
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsPackageBodyWithLocalFunctionBody() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p IS\n"
                + "    FUNCTION local_f RETURN INT IS\n"
                + "    BEGIN RETURN 1; END;\n"
                + "  BEGIN NULL; END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("FUNCTION local_f"));
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void quotedIdentifierDeclarationDoesNotCloseTheOuterProcedure() {
        String script = "CREATE OR REPLACE PROCEDURE p AS\n"
                + "  \"v\" INT;\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("\"v\" INT;"));
        assertTrue(statements.get(0).endsWith("END;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void cursorAliasDoesNotCloseTheOuterProcedure() {
        String script = "CREATE OR REPLACE PROCEDURE outer_p AS\n"
                + "  CURSOR c IS SELECT col AS \"alias\" FROM t;\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("CURSOR c IS"));
        assertTrue(statements.get(0).endsWith("END;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void cursorAliasDoesNotCloseAnAnonymousDeclareBlock() {
        String script = "DECLARE\n"
                + "  CURSOR c IS SELECT col AS \"alias\" FROM t;\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("CURSOR c IS"));
        assertTrue(statements.get(0).endsWith("END;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void nestedBodyAliasDoesNotCloseTheOuterProcedure() {
        String script = "CREATE OR REPLACE PROCEDURE outer_p AS\n"
                + "  PROCEDURE inner_p IS\n"
                + "  BEGIN\n"
                + "    SELECT col AS \"alias\" FROM t;\n"
                + "  END;\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE inner_p"));
        assertTrue(statements.get(0).endsWith("END;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void nestedBodySubqueryAliasDoesNotCloseTheOuterProcedure() {
        String script = "CREATE OR REPLACE PROCEDURE outer_p AS\n"
                + "  PROCEDURE inner_p IS\n"
                + "  BEGIN\n"
                + "    SELECT * FROM (SELECT 1 AS x) AS \"sub\";\n"
                + "  END;\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).endsWith("END;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void forwardDeclarationWithCastDefaultDoesNotLeaveAFrame() {
        String script = "CREATE OR REPLACE PACKAGE pkg AS\n"
                + "  PROCEDURE p(x DATE := CAST(NULL AS DATE));\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void forwardDeclarationWithCaseDefaultDoesNotLeaveAFrame() {
        String script = "CREATE OR REPLACE PACKAGE pkg AS\n"
                + "  PROCEDURE p(x INT := CASE WHEN 1 IS NULL THEN 1 ELSE 2 END);\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void packageBodyForwardDeclarationWithCastDefaultThenBody() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p(x DATE := CAST(NULL AS DATE));\n"
                + "  PROCEDURE p IS BEGIN NULL; END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void handlesMutualRecursionForwardDeclarationsInPackageBody() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p;\n"
                + "  PROCEDURE q;\n"
                + "  PROCEDURE p IS BEGIN q; END;\n"
                + "  PROCEDURE q IS BEGIN p; END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE p IS"));
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void handlesSequentialForwardDeclarationAndBodyPairs() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p;\n"
                + "  PROCEDURE p IS BEGIN NULL; END;\n"
                + "  PROCEDURE q;\n"
                + "  PROCEDURE q IS BEGIN NULL; END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsPackageBodyWithDeclarationBeforeLocalFunctionBody() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p IS\n"
                + "    TYPE t IS RECORD (a INT);\n"
                + "    FUNCTION local_f RETURN INT IS\n"
                + "    BEGIN\n"
                + "      RETURN 1;\n"
                + "    END;\n"
                + "  BEGIN\n"
                + "    NULL;\n"
                + "  END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("TYPE t IS RECORD"));
        assertTrue(statements.get(0).contains("FUNCTION local_f"));
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsPackageBodyWithDeclarationBeforeLocalProcedureBody() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p IS\n"
                + "    v INT;\n"
                + "    PROCEDURE local_p IS BEGIN NULL; END;\n"
                + "  BEGIN NULL; END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE local_p"));
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsTypeBodyWithDeclarationBeforeLocalFunctionBody() {
        String script = "CREATE OR REPLACE TYPE BODY t AS\n"
                + "  MEMBER PROCEDURE p IS\n"
                + "    v INT;\n"
                + "    FUNCTION local_f RETURN INT IS BEGIN RETURN 1; END;\n"
                + "  BEGIN NULL; END;\n"
                + "END;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("FUNCTION local_f"));
        assertTrue(statements.get(0).endsWith("END;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void keepsPackageBodyWithLocalProcedureBody() {
        String script = "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p IS\n"
                + "    PROCEDURE local_p IS BEGIN NULL; END;\n"
                + "  BEGIN NULL; END;\n"
                + "END pkg;\n"
                + "SELECT 1;";
        List<String> statements = split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("PROCEDURE local_p"));
        assertTrue(statements.get(0).endsWith("END pkg;"));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void doesNotSwallowCopyToStatementWithFromStdinInSubquery() {
        List<String> statements = split(
                "COPY (SELECT * FROM stdin) TO stdout;\n"
                        + "COPY t TO stdout /* FROM STDIN */;\n"
                        + "SELECT 1;");
        assertEquals(3, statements.size());
        assertEquals("SELECT 1", statements.get(2));
    }
}
