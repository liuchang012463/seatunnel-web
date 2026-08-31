package org.apache.seatunnel.web.api.lake.operation;

import java.util.Objects;
import java.util.function.Supplier;

/** Synchronous boundary used by pure unit tests and embedding applications. */
final class DirectLakeOperationTransactionBoundary implements LakeOperationTransactionBoundary {

    @Override
    public <T> T requiresNew(Supplier<T> action) {
        return Objects.requireNonNull(action, "action").get();
    }
}
