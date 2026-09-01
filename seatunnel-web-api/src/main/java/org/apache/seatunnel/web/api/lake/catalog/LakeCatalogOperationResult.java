package org.apache.seatunnel.web.api.lake.catalog;

import org.apache.seatunnel.web.common.enums.LakeResourceStatus;

/**
 * Secret-free publication produced by one external catalog operation.
 *
 * <p>The payload is handed from the external callback to the coordinator's
 * local finalize transaction. It deliberately contains no JDBC credentials
 * or executable SQL.</p>
 */
public record LakeCatalogOperationResult(
        String desiredSpecJson,
        String desiredSpecHash,
        String credentialRevision,
        String driverChecksum,
        String actualSnapshotJson,
        String validationStatus,
        LakeResourceStatus resourceStatus) {

    /** Publication for an operation that only observed the existing binding. */
    public static LakeCatalogOperationResult observation(
            String actualSnapshotJson,
            String validationStatus,
            LakeResourceStatus resourceStatus) {
        return new LakeCatalogOperationResult(
                null, null, null, null, actualSnapshotJson, validationStatus, resourceStatus);
    }
}
