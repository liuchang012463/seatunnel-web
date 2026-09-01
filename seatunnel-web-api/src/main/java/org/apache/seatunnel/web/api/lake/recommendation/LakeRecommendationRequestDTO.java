package org.apache.seatunnel.web.api.lake.recommendation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

/**
 * The four user decisions used by the v1.4 recommendation tree.
 *
 * <p>The source id, adapter and target scope identify the server-side
 * capability to inspect.  They are references only; none of them grants
 * access or changes a catalog.</p>
 */
@Data
public class LakeRecommendationRequestDTO {

    /** Whether the user wants data moved into Doris-managed storage. */
    @NotNull
    private Boolean moveData;

    /** Whether Doris physical table governance is required. */
    @NotNull
    private Boolean physicalGovernance;

    /** Whether the only requested use is a cross-catalog join. */
    @NotNull
    private Boolean joinOnly;

    /** Requested metadata scope for the logical catalog capability check. */
    @NotNull
    private LakeCatalogScope targetScope;

    /** Existing server-owned source data-source reference. */
    @NotNull
    @Positive
    private Long sourceDataSourceId;

    /** Fixed server-side JDBC adapter to inspect. */
    @NotNull
    private LakeJdbcAdapterType adapter;
}
