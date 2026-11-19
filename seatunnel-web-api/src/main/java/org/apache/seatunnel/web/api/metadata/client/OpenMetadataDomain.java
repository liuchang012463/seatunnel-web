package org.apache.seatunnel.web.api.metadata.client;

/** Minimal Domain projection returned by OpenMetadata 2.0.4. */
public record OpenMetadataDomain(String id, String name, String fullyQualifiedName, String displayName) {
}
