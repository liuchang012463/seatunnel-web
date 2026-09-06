package org.apache.seatunnel.web.api.lake.query;

import org.apache.seatunnel.web.spi.bean.dto.LakeJoinQueryDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeSingleTableQueryDTO;

/** Pure SQL renderer for a normalized, structured read-only query plan. */
public final class LakeReadOnlyQuerySqlGenerator {

    private final LakeReadOnlyQueryPlanNormalizer normalizer;

    public LakeReadOnlyQuerySqlGenerator() {
        this(LakeReadOnlyQueryPlanNormalizer.DEFAULT_MAX_ROWS);
    }

    public LakeReadOnlyQuerySqlGenerator(int maxRows) {
        this.normalizer = new LakeReadOnlyQueryPlanNormalizer(maxRows);
    }

    public LakeReadOnlyQueryPlan normalize(LakeSingleTableQueryDTO request,
            LakeQueryColumnAllowlist allowlist) {
        return normalizer.normalize(request, allowlist);
    }

    public LakeReadOnlyQueryPlan normalize(LakeJoinQueryDTO request,
            LakeQueryColumnAllowlist leftAllowlist,
            LakeQueryColumnAllowlist rightAllowlist) {
        return normalizer.normalize(request, leftAllowlist, rightAllowlist);
    }

    public String generate(LakeSingleTableQueryDTO request,
            LakeQueryColumnAllowlist allowlist) {
        return generate(normalize(request, allowlist));
    }

    public String generate(LakeJoinQueryDTO request,
            LakeQueryColumnAllowlist leftAllowlist,
            LakeQueryColumnAllowlist rightAllowlist) {
        return generate(normalize(request, leftAllowlist, rightAllowlist));
    }

    public String generate(LakeReadOnlyQueryPlan plan) {
        if (plan == null || plan.outputColumns().isEmpty()) {
            throw new LakeQueryValidationException(LakeQueryValidationCode.COLUMNS_REQUIRED);
        }
        StringBuilder sql = new StringBuilder();
        if (plan.explain()) {
            sql.append("EXPLAIN ");
        }
        sql.append("SELECT ");
        for (int index = 0; index < plan.outputColumns().size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            LakeQueryOutputColumn output = plan.outputColumns().get(index);
            sql.append(LakeQueryIdentifier.quote(output.tableAlias())).append('.')
                    .append(LakeQueryIdentifier.quote(output.source().column()))
                    .append(" AS ").append(LakeQueryIdentifier.quote(output.outputAlias()));
        }
        sql.append(" FROM ").append(qualified(plan.leftTable()))
                .append(" AS ").append(LakeQueryIdentifier.quote(plan.leftAlias()));
        if (plan.isJoin()) {
            if ("INNER".equals(plan.joinType())) {
                sql.append(" JOIN ");
            } else {
                sql.append(' ').append(plan.joinType()).append(" JOIN ");
            }
            sql.append(qualified(plan.rightTable()))
                    .append(" AS ").append(LakeQueryIdentifier.quote(plan.rightAlias()))
                    .append(" ON ")
                    .append(LakeQueryIdentifier.quote(plan.leftAlias())).append('.')
                    .append(LakeQueryIdentifier.quote(plan.leftJoinColumn().column()))
                    .append(" = ")
                    .append(LakeQueryIdentifier.quote(plan.rightAlias())).append('.')
                    .append(LakeQueryIdentifier.quote(plan.rightJoinColumn().column()));
        }
        sql.append(" LIMIT ").append(plan.effectiveLimit());
        return sql.toString();
    }

    public String generateSql(LakeReadOnlyQueryPlan plan) {
        return generate(plan);
    }

    private static String qualified(LakeQueryTableIdentity table) {
        return LakeQueryIdentifier.quote(table.catalog()) + "."
                + LakeQueryIdentifier.quote(table.database()) + "."
                + LakeQueryIdentifier.quote(table.table());
    }
}
