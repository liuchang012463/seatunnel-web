package org.apache.seatunnel.web.api.lake.operation;

import org.apache.seatunnel.web.common.enums.LakeResourceStatus;

/** Snapshot used for token/version compare-and-swap operations. */
public record LakeResourceState(
        String resourceType,
        Long resourceId,
        Integer generation,
        Integer lockVersion,
        String operationToken,
        LakeResourceStatus status,
        boolean deleted) {

    public LakeResourceState {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType must not be blank");
        }
        if (resourceId == null || resourceId <= 0) {
            throw new IllegalArgumentException("resourceId must be positive");
        }
        if (generation == null || generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (lockVersion == null || lockVersion < 1) {
            throw new IllegalArgumentException("lockVersion must be positive");
        }
    }
}
