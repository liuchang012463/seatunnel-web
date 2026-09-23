package org.apache.seatunnel.web.api.fileresource.storage;

import org.apache.seatunnel.web.core.fileresource.FileResourceReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Provider-neutral object storage boundary for reusable file resources.
 * Implementations own endpoint, credential, and provider-specific client
 * details; the resource entity only stores the resulting object metadata.
 */
public interface FileResourceStorageProvider {

    String providerType();

    String bucket();

    /** Converts a logical path below the configured root to a provider key. */
    String objectKey(String logicalPath);

    void ensureBucket();

    String upload(String objectKey, InputStream input, long size, String contentType);

    String initiateMultipartUpload(String objectKey, String contentType);

    String presignMultipartUploadPart(
            String objectKey, String uploadId, int partNumber, long expiresInMillis);

    String completeMultipartUpload(String objectKey, String uploadId, List<StorageUploadPart> parts);

    void abortMultipartUpload(String objectKey, String uploadId);

    void createDirectory(String objectKey);

    List<StorageEntry> list(String logicalPath);

    void delete(String objectKey);

    void deletePrefix(String objectKey);

    StorageObjectMetadata head(String objectKey);

    void download(String objectKey, OutputStream output) throws IOException;

    /** Builds the execution-node reference without exposing provider classes to task code. */
    FileResourceReference executionReference(String objectKey);
}
