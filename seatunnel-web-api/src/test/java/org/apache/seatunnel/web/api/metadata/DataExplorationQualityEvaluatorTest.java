package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumnProfile;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableConstraint;
import org.apache.seatunnel.web.spi.enums.ExplorationQualityStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataExplorationQualityEvaluatorTest {

    @Test
    void evaluatesNotNullAndPrimaryKeyWithoutInventingMissingMetrics() {
        OpenMetadataColumnProfile profile = new OpenMetadataColumnProfile();
        profile.setNullCount(0L);
        profile.setValidCount(1L);
        profile.setDistinctCount(1L);

        ExplorationQualityResult result = DataExplorationQualityEvaluator.evaluate(
                "PRIMARY_KEY", profile, List.of());

        assertEquals(ExplorationQualityStatus.NORMAL, result.getQualityStatus());
        assertEquals("PRIMARY_KEY/NOT_NULL constraints satisfied", result.getQualityReason());
    }

    @Test
    void marksNotNullAbnormalWhenNullsExist() {
        OpenMetadataColumnProfile profile = new OpenMetadataColumnProfile();
        profile.setNullCount(2L);

        ExplorationQualityResult result = DataExplorationQualityEvaluator.evaluate(
                "NOT_NULL", profile, List.of());

        assertEquals(ExplorationQualityStatus.ABNORMAL, result.getQualityStatus());
    }

    @Test
    void evaluatesOnlySingleColumnUniqueConstraints() {
        OpenMetadataColumnProfile profile = new OpenMetadataColumnProfile();
        profile.setValidCount(10L);
        profile.setDistinctCount(10L);
        OpenMetadataTableConstraint constraint = new OpenMetadataTableConstraint();
        constraint.setConstraintType("UNIQUE");
        constraint.setColumns(List.of("id"));

        ExplorationQualityResult result = DataExplorationQualityEvaluator.evaluate(
                null, "id", profile, List.of(constraint));

        assertEquals(ExplorationQualityStatus.NORMAL, result.getQualityStatus());
    }

    @Test
    void leavesCompositeUniqueConstraintUnruled() {
        OpenMetadataColumnProfile profile = new OpenMetadataColumnProfile();
        profile.setValidCount(10L);
        profile.setDistinctCount(10L);
        OpenMetadataTableConstraint constraint = new OpenMetadataTableConstraint();
        constraint.setConstraintType("UNIQUE");
        constraint.setColumns(List.of("tenant_id", "id"));

        ExplorationQualityResult result = DataExplorationQualityEvaluator.evaluate(
                null, "id", profile, List.of(constraint));

        assertEquals(ExplorationQualityStatus.NO_RULE, result.getQualityStatus());
    }

    @Test
    void reportsNoProfileInsteadOfZeroForAConstrainedColumn() {
        ExplorationQualityResult result = DataExplorationQualityEvaluator.evaluate(
                "NOT_NULL", null, List.of());

        assertEquals(ExplorationQualityStatus.NO_PROFILE, result.getQualityStatus());
    }
}
