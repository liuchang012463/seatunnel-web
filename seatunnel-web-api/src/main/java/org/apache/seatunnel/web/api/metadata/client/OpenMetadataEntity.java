package org.apache.seatunnel.web.api.metadata.client;

/** The only OM entity fields retained by the local control plane. */
public record OpenMetadataEntity(String id, String fullyQualifiedName) {
}
