package org.apache.seatunnel.web.api.lake.operation;

/** Secret-free lifecycle binding facts published with a MANAGED table create. */
public record LakeManagedTableOperationPublication(
        Long lifecycleBindingId,
        Integer lifecycleLockVersion,
        Integer retentionCount) {
}
