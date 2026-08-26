package org.apache.seatunnel.web.api.metadata.client;

/** Minimal OpenMetadata 1.12.10 Database identity needed for profiler ownership checks. */
public record OpenMetadataDatabase(String id, String fullyQualifiedName, String serviceFullyQualifiedName) {
}
