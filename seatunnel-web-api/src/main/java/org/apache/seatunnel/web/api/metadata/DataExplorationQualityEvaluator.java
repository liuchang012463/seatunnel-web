package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumnProfile;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableConstraint;
import org.apache.seatunnel.web.spi.enums.ExplorationQualityStatus;

import java.util.List;
import java.util.Locale;

/**
 * Thin quality layer for the Sprint 4 contract. It evaluates only explicit
 * NOT NULL/PK and single-column UNIQUE constraints. It never turns an absent
 * OpenMetadata metric into zero.
 */
public final class DataExplorationQualityEvaluator {

    private DataExplorationQualityEvaluator() {
    }

    public static ExplorationQualityResult evaluate(
            String columnConstraint,
            OpenMetadataColumnProfile profile,
            List<OpenMetadataTableConstraint> tableConstraints) {
        return evaluate(columnConstraint, null, profile, tableConstraints);
    }

    public static ExplorationQualityResult evaluate(
            String columnConstraint,
            String columnName,
            OpenMetadataColumnProfile profile,
            List<OpenMetadataTableConstraint> tableConstraints) {
        String normalized = upper(columnConstraint);
        boolean primaryKey = "PRIMARY_KEY".equals(normalized)
                || hasSingleConstraint(tableConstraints, "PRIMARY_KEY", columnName);
        boolean notNull = primaryKey || "NOT_NULL".equals(normalized);
        boolean unique = primaryKey
                || "UNIQUE".equals(normalized)
                || hasSingleConstraint(tableConstraints, "UNIQUE", columnName);

        if (!notNull && !unique) {
            return result(ExplorationQualityStatus.NO_RULE, "No NOT_NULL, PRIMARY_KEY or single-column UNIQUE constraint");
        }
        if (profile == null) {
            return result(ExplorationQualityStatus.NO_PROFILE, "Profile is not available");
        }

        if (notNull) {
            if (profile.getNullCount() == null) {
                return result(ExplorationQualityStatus.NO_PROFILE, "nullCount is not available in the Profile");
            }
            if (profile.getNullCount() > 0L) {
                return result(ExplorationQualityStatus.ABNORMAL, "NOT_NULL/PRIMARY_KEY constraint has null values");
            }
        }

        if (unique) {
            Long validCount = effectiveValidCount(profile);
            Long uniqueCount = profile.getUniqueCount() != null
                    ? profile.getUniqueCount() : profile.getDistinctCount();
            if (validCount == null || uniqueCount == null) {
                return result(ExplorationQualityStatus.NO_PROFILE,
                        "distinct/unique count or valid value count is not available in the Profile");
            }
            if (!validCount.equals(uniqueCount)) {
                return result(ExplorationQualityStatus.ABNORMAL,
                        "PRIMARY_KEY/UNIQUE constraint has duplicate valid values");
            }
            return result(
                    ExplorationQualityStatus.NORMAL,
                    primaryKey ? "PRIMARY_KEY/NOT_NULL constraints satisfied" : "UNIQUE constraint satisfied");
        }

        return result(ExplorationQualityStatus.NORMAL, "NOT_NULL constraint satisfied");
    }

    private static Long effectiveValidCount(OpenMetadataColumnProfile profile) {
        if (profile.getValidCount() != null) {
            return profile.getValidCount();
        }
        if (profile.getValuesCount() == null) {
            return null;
        }
        if (profile.getNullCount() == null) {
            return profile.getValuesCount();
        }
        return profile.getValuesCount() - profile.getNullCount();
    }

    private static boolean hasSingleConstraint(
            List<OpenMetadataTableConstraint> constraints,
            String type,
            String columnName) {
        if (columnName == null || constraints == null) {
            return false;
        }
        return constraints.stream().anyMatch(constraint -> {
            if (constraint == null || !type.equals(upper(constraint.getConstraintType()))
                    || constraint.getColumns() == null || constraint.getColumns().size() != 1) {
                return false;
            }
            return columnName.equalsIgnoreCase(constraint.getColumns().get(0));
        });
    }

    private static ExplorationQualityResult result(ExplorationQualityStatus status, String reason) {
        return new ExplorationQualityResult(status, reason);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
