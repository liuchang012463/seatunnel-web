package org.apache.seatunnel.web.core.fileresource;

import java.util.Objects;

/**
 * Provider-neutral execution reference for a file resource.
 *
 * <p>The first provider implementation is S3-compatible storage (including
 * MinIO), but the task model intentionally carries provider-neutral fields so
 * additional object-storage adapters can be introduced later.</p>
 */
public final class FileResourceReference {

    private final String providerType;
    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String basePath;
    private final String objectKey;
    private final String credentialMode;
    private final String accessKey;
    private final String secretKey;
    private final boolean pathStyleAccess;

    public FileResourceReference(
            String providerType,
            String endpoint,
            String region,
            String bucket,
            String basePath,
            String objectKey,
            String credentialMode,
            String accessKey,
            String secretKey,
            boolean pathStyleAccess) {
        this.providerType = requireText(providerType, "providerType");
        this.endpoint = requireText(endpoint, "endpoint");
        this.region = requireText(region, "region");
        this.bucket = requireText(bucket, "bucket");
        this.basePath = normalizeBasePath(basePath);
        this.objectKey = requireText(objectKey, "objectKey");
        this.credentialMode = requireText(credentialMode, "credentialMode");
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRegion() {
        return region;
    }

    public String getBucket() {
        return bucket;
    }

    public String getBasePath() {
        return basePath;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getCredentialMode() {
        return credentialMode;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeBasePath(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "/";
        }
        String normalized = value.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FileResourceReference)) {
            return false;
        }
        FileResourceReference that = (FileResourceReference) other;
        return pathStyleAccess == that.pathStyleAccess
                && Objects.equals(providerType, that.providerType)
                && Objects.equals(endpoint, that.endpoint)
                && Objects.equals(region, that.region)
                && Objects.equals(bucket, that.bucket)
                && Objects.equals(basePath, that.basePath)
                && Objects.equals(objectKey, that.objectKey)
                && Objects.equals(credentialMode, that.credentialMode)
                && Objects.equals(accessKey, that.accessKey)
                && Objects.equals(secretKey, that.secretKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                providerType,
                endpoint,
                region,
                bucket,
                basePath,
                objectKey,
                credentialMode,
                accessKey,
                secretKey,
                pathStyleAccess);
    }
}
