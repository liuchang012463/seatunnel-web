package org.apache.seatunnel.web.api.lake.operation;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeOperationType;

/** Immutable-at-the-boundary request data used to create a durable operation intent. */
@Data
public class LakeOperationIntent {

    private String resourceType;

    private Long resourceId;

    /** Expected current generation. Null means read it from the resource gateway. */
    private Integer generation;

    /** Expected current lock version. Null means read it from the resource gateway. */
    private Integer lockVersion;

    /** Expected current token, normally null when a resource is not leased. */
    private String operationToken;

    private LakeOperationType operationType;

    private String requestHash;

    private Integer operatorId;

    /** Rebuild reuses the same row and advances generation exactly once. */
    private boolean rebuild;

    public LakeOperationIntent() {
    }

    public LakeOperationIntent(
            String resourceType,
            Long resourceId,
            LakeOperationType operationType,
            String requestHash,
            Integer operatorId) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.operationType = operationType;
        this.requestHash = requestHash;
        this.operatorId = operatorId;
    }
}
