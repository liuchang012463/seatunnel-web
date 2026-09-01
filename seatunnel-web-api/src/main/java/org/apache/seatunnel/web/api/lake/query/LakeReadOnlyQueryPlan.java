package org.apache.seatunnel.web.api.lake.query;

import java.util.List;
import java.util.Objects;

/** Immutable normalized plan accepted by the pure SQL generator. */
public final class LakeReadOnlyQueryPlan {

    public enum Kind {
        SINGLE_TABLE,
        EQUALITY_JOIN
    }

    private final Kind kind;
    private final LakeQueryTableIdentity leftTable;
    private final LakeQueryTableIdentity rightTable;
    private final List<LakeQueryOutputColumn> outputColumns;
    private final LakeQueryColumnIdentity leftJoinColumn;
    private final LakeQueryColumnIdentity rightJoinColumn;
    private final int requestedLimit;
    private final int effectiveLimit;
    private final boolean explain;
    private final String joinType;
    private final String leftAlias;
    private final String rightAlias;

    private LakeReadOnlyQueryPlan(
            Kind kind,
            LakeQueryTableIdentity leftTable,
            LakeQueryTableIdentity rightTable,
            List<LakeQueryOutputColumn> outputColumns,
            LakeQueryColumnIdentity leftJoinColumn,
            LakeQueryColumnIdentity rightJoinColumn,
            int requestedLimit,
            int effectiveLimit,
            boolean explain,
            String joinType,
            String leftAlias,
            String rightAlias) {
        this.kind = Objects.requireNonNull(kind);
        this.leftTable = Objects.requireNonNull(leftTable);
        this.rightTable = rightTable;
        this.outputColumns = List.copyOf(outputColumns);
        this.leftJoinColumn = leftJoinColumn;
        this.rightJoinColumn = rightJoinColumn;
        this.requestedLimit = requestedLimit;
        this.effectiveLimit = effectiveLimit;
        this.explain = explain;
        this.joinType = Objects.requireNonNull(joinType);
        this.leftAlias = Objects.requireNonNull(leftAlias);
        this.rightAlias = rightAlias;
    }

    public static LakeReadOnlyQueryPlan single(
            LakeQueryTableIdentity table,
            List<LakeQueryOutputColumn> columns,
            int requestedLimit,
            int effectiveLimit,
            boolean explain) {
        return new LakeReadOnlyQueryPlan(Kind.SINGLE_TABLE, table, null, columns,
                null, null, requestedLimit, effectiveLimit, explain, "INNER", "t0", null);
    }

    public static LakeReadOnlyQueryPlan join(
            LakeQueryTableIdentity leftTable,
            LakeQueryTableIdentity rightTable,
            List<LakeQueryOutputColumn> columns,
            LakeQueryColumnIdentity leftJoinColumn,
            LakeQueryColumnIdentity rightJoinColumn,
            int requestedLimit,
            int effectiveLimit,
            boolean explain) {
        return join(leftTable, rightTable, columns, leftJoinColumn, rightJoinColumn,
                requestedLimit, effectiveLimit, explain, "INNER");
    }

    public static LakeReadOnlyQueryPlan join(
            LakeQueryTableIdentity leftTable,
            LakeQueryTableIdentity rightTable,
            List<LakeQueryOutputColumn> columns,
            LakeQueryColumnIdentity leftJoinColumn,
            LakeQueryColumnIdentity rightJoinColumn,
            int requestedLimit,
            int effectiveLimit,
            boolean explain,
            String joinType) {
        return new LakeReadOnlyQueryPlan(Kind.EQUALITY_JOIN, leftTable, rightTable, columns,
                Objects.requireNonNull(leftJoinColumn), Objects.requireNonNull(rightJoinColumn),
                requestedLimit, effectiveLimit, explain, joinType, "l", "r");
    }

    public Kind kind() {
        return kind;
    }

    public boolean isSingleTable() {
        return kind == Kind.SINGLE_TABLE;
    }

    public boolean isJoin() {
        return kind == Kind.EQUALITY_JOIN;
    }

    public LakeQueryTableIdentity table() {
        return leftTable;
    }

    public LakeQueryTableIdentity leftTable() {
        return leftTable;
    }

    public LakeQueryTableIdentity rightTable() {
        return rightTable;
    }

    public List<LakeQueryOutputColumn> outputColumns() {
        return outputColumns;
    }

    public LakeQueryColumnIdentity leftJoinColumn() {
        return leftJoinColumn;
    }

    public LakeQueryColumnIdentity rightJoinColumn() {
        return rightJoinColumn;
    }

    public int requestedLimit() {
        return requestedLimit;
    }

    public int effectiveLimit() {
        return effectiveLimit;
    }

    public boolean explain() {
        return explain;
    }

    public String joinType() {
        return joinType;
    }

    public String leftAlias() {
        return leftAlias;
    }

    public String rightAlias() {
        return rightAlias;
    }
}
