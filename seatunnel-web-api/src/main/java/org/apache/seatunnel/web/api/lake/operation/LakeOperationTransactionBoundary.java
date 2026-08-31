package org.apache.seatunnel.web.api.lake.operation;

import java.util.function.Supplier;

/**
 * Explicit local transaction boundary for lake operation journal/state work.
 *
 * <p>The external Doris phase never runs through this boundary.  The Spring
 * implementation uses {@code REQUIRES_NEW}, so a caller's transaction cannot
 * accidentally keep an intent/finalize transaction open across a network
 * call.</p>
 */
@FunctionalInterface
public interface LakeOperationTransactionBoundary {

    <T> T requiresNew(Supplier<T> action);
}
