package org.apache.seatunnel.web.api.lake.operation;

/** Token and version captured by an external operation execution. */
public record LakeOperationHandle(
        Long operationId,
        String resourceType,
        Long resourceId,
        Integer generation,
        String operationToken,
        Integer lockVersion) {

    public LakeOperationHandle {
        if (operationId == null || operationId <= 0) {
            throw new IllegalArgumentException("operationId must be positive");
        }
        if (operationToken == null || operationToken.isBlank()) {
            throw new IllegalArgumentException("operationToken must not be blank");
        }
    }
}
