package org.apache.seatunnel.web.api.metadata.client;

/** Minimal OpenMetadata 2.0.4 Database identity needed for profiler ownership checks. */
public record OpenMetadataDatabase(String id, String fullyQualifiedName, String serviceFullyQualifiedName) {
}
