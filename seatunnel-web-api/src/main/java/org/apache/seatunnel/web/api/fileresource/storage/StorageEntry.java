package org.apache.seatunnel.web.api.fileresource.storage;

/** One immediate child returned by an object-storage directory listing. */
public record StorageEntry(
        String name,
        String objectKey,
        String type,
        Long size,
        Long modifiedTime) {
}
