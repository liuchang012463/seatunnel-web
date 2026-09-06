package org.apache.seatunnel.web.api.lake.query;

import java.util.List;

/**
 * Safe preview of a server-generated structured query.
 *
 * <p>The SQL is composed exclusively from validated catalog, database, table
 * and column identifiers.  It is intentionally not persisted in the
 * operation journal and is never accepted back as an execution request.</p>
 */
public record LakeReadOnlyQueryPreviewVO(
        String sql,
        List<String> outputColumns,
        int effectiveLimit,
        boolean explain,
        String joinType) {

    public LakeReadOnlyQueryPreviewVO {
        outputColumns = outputColumns == null ? List.of() : List.copyOf(outputColumns);
    }

    public static LakeReadOnlyQueryPreviewVO from(LakeReadOnlyQueryPlan plan, String sql) {
        return new LakeReadOnlyQueryPreviewVO(
                sql,
                plan.outputColumns().stream().map(LakeQueryOutputColumn::outputAlias).toList(),
                plan.effectiveLimit(),
                plan.explain(),
                plan.isJoin() ? plan.joinType() : null);
    }
}
