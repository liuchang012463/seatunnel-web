package org.apache.seatunnel.web.api.lake.query;

import org.apache.seatunnel.web.spi.bean.dto.LakeJoinQueryDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeQueryColumnIdentityDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeQueryTableIdentityDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeSingleTableQueryDTO;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Converts structured requests and allowlists into safe immutable plans. */
public final class LakeReadOnlyQueryPlanNormalizer {

    public static final int DEFAULT_MAX_ROWS = 1_000;

    private final int maxRows;

    public LakeReadOnlyQueryPlanNormalizer() {
        this(DEFAULT_MAX_ROWS);
    }

    public LakeReadOnlyQueryPlanNormalizer(int maxRows) {
        if (maxRows <= 0) {
            throw new LakeQueryValidationException(LakeQueryValidationCode.MAX_ROWS_INVALID);
        }
        this.maxRows = maxRows;
    }

    public int maxRows() {
        return maxRows;
    }

    public LakeReadOnlyQueryPlan normalize(
            LakeSingleTableQueryDTO request,
            LakeQueryColumnAllowlist allowlist) {
        if (request == null) {
            throw invalid(LakeQueryValidationCode.REQUEST_REQUIRED);
        }
        LakeQueryTableIdentity table = table(request.table());
        int limit = limit(request.limit());
        if (request.selectedColumns().isEmpty()) {
            throw invalid(LakeQueryValidationCode.COLUMNS_REQUIRED);
        }
        validateAllowlist(allowlist);
        Set<String> seen = new HashSet<>();
        List<LakeQueryOutputColumn> columns = new java.util.ArrayList<>();
        for (int index = 0; index < request.selectedColumns().size(); index++) {
            LakeQueryColumnIdentity column = column(request.selectedColumns().get(index));
            requireTable(column, table);
            requireSelectable(column, allowlist);
            if (!seen.add(column.column().toLowerCase(java.util.Locale.ROOT))) {
                throw invalid(LakeQueryValidationCode.DUPLICATE_COLUMN);
            }
            columns.add(new LakeQueryOutputColumn(column, "t0", "c" + index));
        }
        return LakeReadOnlyQueryPlan.single(table, columns, limit, capped(limit),
                Boolean.TRUE.equals(request.explain()));
    }

    public LakeReadOnlyQueryPlan normalize(
            LakeJoinQueryDTO request,
            LakeQueryColumnAllowlist leftAllowlist,
            LakeQueryColumnAllowlist rightAllowlist) {
        if (request == null) {
            throw invalid(LakeQueryValidationCode.REQUEST_REQUIRED);
        }
        LakeQueryTableIdentity leftTable = table(request.leftTable());
        LakeQueryTableIdentity rightTable = table(request.rightTable());
        if (leftTable.catalog().equals(rightTable.catalog())) {
            throw invalid(LakeQueryValidationCode.JOIN_CATALOGS_NOT_DISTINCT);
        }
        int limit = limit(request.limit());
        if (request.leftColumns().isEmpty() || request.rightColumns().isEmpty()) {
            throw invalid(LakeQueryValidationCode.COLUMNS_REQUIRED);
        }
        if (request.leftJoinColumn() == null || request.rightJoinColumn() == null) {
            throw invalid(LakeQueryValidationCode.JOIN_REQUIRED);
        }
        validateAllowlist(leftAllowlist);
        validateAllowlist(rightAllowlist);
        List<LakeQueryOutputColumn> columns = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < request.leftColumns().size(); index++) {
            LakeQueryColumnIdentity column = column(request.leftColumns().get(index));
            requireTable(column, leftTable);
            requireSelectable(column, leftAllowlist);
            if (!seen.add(column.column().toLowerCase(java.util.Locale.ROOT))) {
                throw invalid(LakeQueryValidationCode.DUPLICATE_COLUMN);
            }
            columns.add(new LakeQueryOutputColumn(column, "l", "left_c" + index));
        }
        for (int index = 0; index < request.rightColumns().size(); index++) {
            LakeQueryColumnIdentity column = column(request.rightColumns().get(index));
            requireTable(column, rightTable);
            requireSelectable(column, rightAllowlist);
            String key = "right:" + column.column().toLowerCase(java.util.Locale.ROOT);
            if (!seen.add(key)) {
                throw invalid(LakeQueryValidationCode.DUPLICATE_COLUMN);
            }
            columns.add(new LakeQueryOutputColumn(column, "r", "right_c" + index));
        }
        LakeQueryColumnIdentity leftJoin = column(request.leftJoinColumn());
        LakeQueryColumnIdentity rightJoin = column(request.rightJoinColumn());
        requireTable(leftJoin, leftTable);
        requireTable(rightJoin, rightTable);
        requireSelectable(leftJoin, leftAllowlist);
        requireSelectable(rightJoin, rightAllowlist);
        Set<String> aliases = new HashSet<>();
        for (LakeQueryOutputColumn output : columns) {
            if (!aliases.add(output.outputAlias())) {
                throw invalid(LakeQueryValidationCode.AMBIGUOUS_OUTPUT_ALIAS);
            }
        }
        return LakeReadOnlyQueryPlan.join(leftTable, rightTable, columns, leftJoin, rightJoin,
                limit, capped(limit), Boolean.TRUE.equals(request.explain()));
    }

    public LakeQueryValidationResult<LakeReadOnlyQueryPlan> tryNormalize(
            LakeSingleTableQueryDTO request,
            LakeQueryColumnAllowlist allowlist) {
        try {
            return LakeQueryValidationResult.valid(normalize(request, allowlist));
        } catch (LakeQueryValidationException exception) {
            return LakeQueryValidationResult.invalid(exception.code());
        }
    }

    private int capped(int requested) {
        return Math.min(requested, maxRows);
    }

    private int limit(Integer requested) {
        if (requested == null) {
            throw invalid(LakeQueryValidationCode.LIMIT_REQUIRED);
        }
        if (requested <= 0) {
            throw invalid(LakeQueryValidationCode.LIMIT_NOT_POSITIVE);
        }
        return requested;
    }

    private static LakeQueryTableIdentity table(LakeQueryTableIdentityDTO dto) {
        if (dto == null) {
            throw invalid(LakeQueryValidationCode.TABLE_REQUIRED);
        }
        return new LakeQueryTableIdentity(identifier(dto.catalog()), identifier(dto.database()),
                identifier(dto.table()));
    }

    private static LakeQueryColumnIdentity column(LakeQueryColumnIdentityDTO dto) {
        if (dto == null) {
            throw invalid(LakeQueryValidationCode.COLUMN_REQUIRED);
        }
        return new LakeQueryColumnIdentity(table(dto.table()), identifier(dto.column()));
    }

    private static String identifier(String value) {
        if (value == null || value.isEmpty()) {
            throw invalid(LakeQueryValidationCode.IDENTIFIER_REQUIRED);
        }
        try {
            return LakeQueryIdentifier.validate(value);
        } catch (RuntimeException exception) {
            throw invalid(LakeQueryValidationCode.IDENTIFIER_INVALID);
        }
    }

    private static void requireTable(LakeQueryColumnIdentity column,
            LakeQueryTableIdentity expected) {
        if (!expected.equals(column.table())) {
            throw invalid(LakeQueryValidationCode.COLUMN_TABLE_MISMATCH);
        }
    }

    private static void validateAllowlist(LakeQueryColumnAllowlist allowlist) {
        if (allowlist == null) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (LakeQueryColumnMetadata metadata : allowlist.columns()) {
            if (metadata == null || metadata.name() == null || metadata.name().isEmpty()) {
                throw invalid(LakeQueryValidationCode.IDENTIFIER_INVALID);
            }
            identifier(metadata.name());
            if (!names.add(metadata.name().toLowerCase(java.util.Locale.ROOT))) {
                throw invalid(LakeQueryValidationCode.DUPLICATE_ALLOWLIST_COLUMN);
            }
        }
    }

    private static void requireSelectable(LakeQueryColumnIdentity column,
            LakeQueryColumnAllowlist allowlist) {
        if (allowlist == null) {
            throw invalid(LakeQueryValidationCode.COLUMN_UNKNOWN);
        }
        LakeQueryColumnMetadata metadata = allowlist.find(column.column()).orElseThrow(
                () -> invalid(LakeQueryValidationCode.COLUMN_UNKNOWN));
        if (metadata.sensitive()) {
            throw invalid(LakeQueryValidationCode.COLUMN_SENSITIVE);
        }
        if (!metadata.selectable()) {
            throw invalid(LakeQueryValidationCode.COLUMN_UNSUPPORTED);
        }
    }

    private static LakeQueryValidationException invalid(LakeQueryValidationCode code) {
        return new LakeQueryValidationException(code);
    }
}
