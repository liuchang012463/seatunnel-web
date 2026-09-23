package org.apache.seatunnel.web.api.fileresource.storage;

/** Part number and object-storage ETag required to complete a multipart upload. */
public record StorageUploadPart(int partNumber, String etag) {
}
