package org.apache.seatunnel.web.api.lake.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.spi.bean.dto.LakeJoinQueryDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeQueryColumnIdentityDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeQueryTableIdentityDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeSingleTableQueryDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LakeReadOnlyQuerySqlGeneratorTest {

    private static final LakeQueryTableIdentityDTO LEFT =
            new LakeQueryTableIdentityDTO("SalesCatalog", "SalesDB", "Orders");
    private static final LakeQueryTableIdentityDTO RIGHT =
            new LakeQueryTableIdentityDTO("AuditCatalog", "AuditDB", "Orders");

    @Test
    void rendersCasePreservingSingleProjectionAndCapsLimit() {
        LakeSingleTableQueryDTO request = new LakeSingleTableQueryDTO(LEFT,
                List.of(column(LEFT, "OrderId"), column(LEFT, "CustomerName")), 999, false);
        String sql = new LakeReadOnlyQuerySqlGenerator(10).generate(request,
                allow("OrderId", "CustomerName"));

        assertEquals("SELECT `t0`.`OrderId` AS `c0`, `t0`.`CustomerName` AS `c1`"
                        + " FROM `SalesCatalog`.`SalesDB`.`Orders` AS `t0` LIMIT 10", sql);
    }

    @Test
    void rendersEqualityJoinWithDifferentAliasesAndUnselectedAllowlistedKey() {
        LakeJoinQueryDTO request = new LakeJoinQueryDTO(LEFT, RIGHT,
                List.of(column(LEFT, "OrderId")), List.of(column(RIGHT, "AuditId")),
                column(LEFT, "JoinKey"), column(RIGHT, "JoinKey"), 2, true);
        LakeQueryColumnAllowlist left = allow("OrderId", "JoinKey");
        LakeQueryColumnAllowlist right = allow("AuditId", "JoinKey");

        assertEquals("EXPLAIN SELECT `l`.`OrderId` AS `left_c0`, `r`.`AuditId` AS `right_c0`"
                        + " FROM `SalesCatalog`.`SalesDB`.`Orders` AS `l`"
                        + " JOIN `AuditCatalog`.`AuditDB`.`Orders` AS `r`"
                        + " ON `l`.`JoinKey` = `r`.`JoinKey` LIMIT 2",
                new LakeReadOnlyQuerySqlGenerator().generate(request, left, right));
    }

    @Test
    void rejectsInjectionInEveryIdentifierAndPolicyViolations() {
        LakeReadOnlyQuerySqlGenerator generator = new LakeReadOnlyQuerySqlGenerator();
        LakeQueryTableIdentityDTO[] unsafeTables = {
            new LakeQueryTableIdentityDTO("a.b", "db", "t"),
            new LakeQueryTableIdentityDTO("catalog", "db;drop", "t"),
            new LakeQueryTableIdentityDTO("catalog", "db", "t--comment")
        };
        for (LakeQueryTableIdentityDTO unsafe : unsafeTables) {
            LakeSingleTableQueryDTO request = new LakeSingleTableQueryDTO(unsafe,
                    List.of(column(unsafe, "id")), 1, false);
            LakeQueryValidationException identifier = assertThrows(
                    LakeQueryValidationException.class,
                    () -> generator.generate(request, allow("id")));
            assertEquals(LakeQueryValidationCode.IDENTIFIER_INVALID, identifier.code());
        }
        LakeQueryTableIdentityDTO safeTable = LEFT;
        LakeSingleTableQueryDTO unsafeColumn = new LakeSingleTableQueryDTO(safeTable,
                List.of(column(safeTable, "id` OR `x")), 1, false);
        LakeQueryValidationException columnIdentifier = assertThrows(
                LakeQueryValidationException.class,
                () -> generator.generate(unsafeColumn, allow("id")));
        assertEquals(LakeQueryValidationCode.IDENTIFIER_INVALID, columnIdentifier.code());

        LakeSingleTableQueryDTO sensitive = new LakeSingleTableQueryDTO(LEFT,
                List.of(column(LEFT, "secret")), 1, false);
        LakeQueryValidationException rejected = assertThrows(LakeQueryValidationException.class,
                () -> generator.generate(sensitive,
                        new LakeQueryColumnAllowlist(List.of(
                                new LakeQueryColumnMetadata("secret", true, true)))));
        assertEquals(LakeQueryValidationCode.COLUMN_SENSITIVE, rejected.code());
    }

    @Test
    void rejectsUnknownUnsupportedDuplicateAndNonPositiveRequests() {
        LakeReadOnlyQuerySqlGenerator generator = new LakeReadOnlyQuerySqlGenerator(10);
        LakeSingleTableQueryDTO zero = new LakeSingleTableQueryDTO(LEFT,
                List.of(column(LEFT, "id")), 0, false);
        LakeQueryValidationException limit = assertThrows(LakeQueryValidationException.class,
                () -> generator.generate(zero, allow("id")));
        assertEquals(LakeQueryValidationCode.LIMIT_NOT_POSITIVE, limit.code());

        LakeSingleTableQueryDTO unknown = new LakeSingleTableQueryDTO(LEFT,
                List.of(column(LEFT, "missing")), 1, false);
        LakeQueryValidationException field = assertThrows(LakeQueryValidationException.class,
                () -> generator.generate(unknown, allow("id")));
        assertEquals(LakeQueryValidationCode.COLUMN_UNKNOWN, field.code());

        LakeSingleTableQueryDTO unsupported = new LakeSingleTableQueryDTO(LEFT,
                List.of(column(LEFT, "blob")), 1, false);
        LakeQueryValidationException type = assertThrows(LakeQueryValidationException.class,
                () -> generator.generate(unsupported,
                        new LakeQueryColumnAllowlist(List.of(
                                new LakeQueryColumnMetadata("blob", false, false)))));
        assertEquals(LakeQueryValidationCode.COLUMN_UNSUPPORTED, type.code());
    }

    @Test
    void dtoHasNoRawSqlSurface() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new LakeSingleTableQueryDTO(
                LEFT, List.of(column(LEFT, "id")), 1, false));
        assertFalse(Set.of("sql", "rawSql", "filter", "order", "expression")
                .stream().anyMatch(json::contains));
    }

    private static LakeQueryColumnIdentityDTO column(LakeQueryTableIdentityDTO table,
            String name) {
        return new LakeQueryColumnIdentityDTO(table, name);
    }

    private static LakeQueryColumnAllowlist allow(String... names) {
        return new LakeQueryColumnAllowlist(java.util.Arrays.stream(names)
                .map(name -> new LakeQueryColumnMetadata(name, true, false)).toList());
    }
}
