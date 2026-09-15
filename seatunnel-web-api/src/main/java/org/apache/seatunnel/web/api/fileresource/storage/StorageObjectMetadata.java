package org.apache.seatunnel.web.api.fileresource.storage;

/** Provider-neutral metadata read from the backing object. */
public record StorageObjectMetadata(Long size, String contentType, String etag) {
}
