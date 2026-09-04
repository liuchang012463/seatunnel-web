package org.apache.seatunnel.web.core.fileupload;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connection settings for the platform-owned MinIO bucket used by browser
 * uploads.  Values are intentionally optional at startup; the service only
 * requires them when a WEB_UPLOAD source is used.
 */
@Component
@ConfigurationProperties(prefix = "seatunnel.web.file-upload.minio")
public class BuiltInMinioProperties {

    private String endpoint;

    private String runtimeEndpoint;

    private String bucket = "seatunnel-web-upload";

    private String accessKey;

    private String secretKey;

    private String runtimeAccessKey;

    private String runtimeSecretKey;

    private String rootPrefix = "seatunnel-web-upload";

    private int sessionTtlHours = 24;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRuntimeEndpoint() {
        return StringUtils.defaultIfBlank(runtimeEndpoint, endpoint);
    }

    public void setRuntimeEndpoint(String runtimeEndpoint) {
        this.runtimeEndpoint = runtimeEndpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getRuntimeAccessKey() {
        return StringUtils.defaultIfBlank(runtimeAccessKey, accessKey);
    }

    public void setRuntimeAccessKey(String runtimeAccessKey) {
        this.runtimeAccessKey = runtimeAccessKey;
    }

    public String getRuntimeSecretKey() {
        return StringUtils.defaultIfBlank(runtimeSecretKey, secretKey);
    }

    public void setRuntimeSecretKey(String runtimeSecretKey) {
        this.runtimeSecretKey = runtimeSecretKey;
    }

    public String getRootPrefix() {
        String normalized = normalizeSegment(rootPrefix, "");
        // The bucket already scopes object names. Treating its name as another
        // root segment would expose the same namespace in both bucket and path.
        if (StringUtils.equalsIgnoreCase(normalized, StringUtils.trimToEmpty(bucket))) {
            return "";
        }
        return normalized;
    }

    public void setRootPrefix(String rootPrefix) {
        this.rootPrefix = rootPrefix;
    }

    public int getSessionTtlHours() {
        return sessionTtlHours > 0 ? sessionTtlHours : 24;
    }

    public void setSessionTtlHours(int sessionTtlHours) {
        this.sessionTtlHours = sessionTtlHours;
    }

    public String objectKeyPrefix(Long jobDefinitionId, String sessionId) {
        if (jobDefinitionId == null || jobDefinitionId <= 0) {
            throw new IllegalArgumentException("jobDefinitionId is required for WEB_UPLOAD");
        }
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("uploadSessionId is required for WEB_UPLOAD");
        }
        String sessionPath = jobDefinitionId + "/" + sessionId.trim();
        String prefix = getRootPrefix();
        return StringUtils.isBlank(prefix) ? sessionPath : prefix + "/" + sessionPath;
    }

    public String objectPath(Long jobDefinitionId, String sessionId) {
        return "/" + objectKeyPrefix(jobDefinitionId, sessionId);
    }

    private String normalizeSegment(String value, String fallback) {
        String normalized = StringUtils.defaultIfBlank(value, fallback).trim();
        if (normalized.startsWith("/") || normalized.endsWith("/")
                || normalized.contains("\\") || normalized.contains("..")) {
            throw new IllegalArgumentException("MinIO root prefix is invalid");
        }
        return normalized;
    }
}
