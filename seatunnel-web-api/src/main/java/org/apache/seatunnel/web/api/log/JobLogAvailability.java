package org.apache.seatunnel.web.api.log;

/** Availability of the persisted per-instance log currently visible to the viewer. */
public enum JobLogAvailability {
    /** The job may still emit logs, or its Engine log is waiting for collection. */
    PENDING,
    /** At least one persisted log line is available. */
    RETAINED,
    /** No local log exists and the instance has no Engine log reference. */
    UNAVAILABLE
}
