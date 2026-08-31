package org.apache.seatunnel.web.api.lake.operation;

/** Work executed against Doris after the intent transaction has committed. */
@FunctionalInterface
public interface LakeExternalOperation<T> {

    T execute() throws Exception;
}
