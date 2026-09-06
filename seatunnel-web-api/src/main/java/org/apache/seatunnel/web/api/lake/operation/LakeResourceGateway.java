package org.apache.seatunnel.web.api.lake.operation;

import org.apache.seatunnel.web.common.enums.LakeResourceStatus;

/**
 * Resource-row persistence boundary for the operation coordinator.
 * Implementations must use a single SQL compare-and-swap statement for each
 * mutating method and include both operation token and lock version.
 */
public interface LakeResourceGateway {

    LakeResourceState get(String resourceType, Long resourceId);

    boolean claim(
            LakeResourceState expected,
            String operationToken,
            Integer newGeneration,
            LakeResourceStatus pendingStatus);

    boolean finalizeSuccess(LakeOperationHandle handle, String summary);

    /**
     * Finalizes a resource and publishes an optional secret-free operation
     * result in the same local transaction. Existing resource gateways keep
     * their old behavior unless they understand the payload.
     */
    default boolean finalizeSuccess(
            LakeOperationHandle handle, String summary, Object publication) {
        return finalizeSuccess(handle, summary);
    }

    boolean finalizeFailure(LakeOperationHandle handle, String errorCode, String summary);

    /** Takes over a stale lease, retaining the row and advancing its lock version. */
    boolean takeOver(
            LakeOperationHandle staleHandle,
            String newOperationToken,
            Integer newGeneration,
            LakeResourceStatus pendingStatus);
}
