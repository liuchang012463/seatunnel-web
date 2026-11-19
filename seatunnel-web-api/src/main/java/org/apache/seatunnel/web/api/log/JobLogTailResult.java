package org.apache.seatunnel.web.api.log;

/** A bounded page of a persisted per-instance log, addressed by UTF-8 byte offsets. */
public record JobLogTailResult(
        Long instanceId,
        String jobMode,
        JobLogAvailability availability,
        String content,
        long startCursor,
        long endCursor,
        boolean hasOlder,
        boolean hasNewer
) {
}
