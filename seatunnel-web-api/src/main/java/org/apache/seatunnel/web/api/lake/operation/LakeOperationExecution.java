package org.apache.seatunnel.web.api.lake.operation;

/** Result of the external phase, before local finalize is attempted. */
public record LakeOperationExecution<T>(LakeOperationHandle handle, T externalResult) {
}
