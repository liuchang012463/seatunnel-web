package org.apache.seatunnel.web.core.fileupload;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Connection settings for the platform-owned MinIO bucket used by browser
 * uploads.  Values are intentionally optional at startup; the service only
 * requires them when a WEB_UPLOAD source is used.
 */
@Component
public class BuiltInMinioProperties {

    @Value("${SEATUNNEL_WEB_FILE_UPLOAD_MINIO_ENDPOINT:}")
    private String endpoint;

    @Value("${SEATUNNEL_WEB_FILE_UPLOAD_MINIO_RUNTIME_ENDPOINT:}")
    private String runtimeEndpoint;

    @Value("${SEATUNNEL_WEB_FILE_UPLOAD_MINIO_BUCKET:seatunnel-web-upload}")
    private String bucket;

    @Value("${SEATUNNEL_WEB_FILE_UPLOAD_MINIO_ACCESS_KEY:}")
    private String accessKey;

    @Value("${SEATUNNEL_WEB_FILE_UPLOAD_MINIO_SECRET_KEY:}")
    private String secretKey;

    @Value("${SEATUNNEL_WEB_FILE_UPLOAD_MINIO_RUNTIME_ACCESS_KEY:}")
    private String runtimeAccessKey;

    @Value("${SEATUNNEL_WEB_FILE_UPLOAD_MINIO_RUNTIME_SECRET_KEY:}")
    private String runtimeSecretKey;

    @Value("${SEATUNNEL_WEB_FILE_UPLOAD_MINIO_ROOT_PREFIX:seatunnel-web-upload}")
    private String rootPrefix;

    @Value("${SEATUNNEL_WEB_FILE_UPLOAD_MINIO_SESSION_TTL_HOURS:24}")
    private int sessionTtlHours;

    public String getEndpoint() {
        return endpoint;
    }

    public String getRuntimeEndpoint() {
        return StringUtils.defaultIfBlank(runtimeEndpoint, endpoint);
    }

    public String getBucket() {
        return bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getRuntimeAccessKey() {
        return StringUtils.defaultIfBlank(runtimeAccessKey, accessKey);
    }

    public String getRuntimeSecretKey() {
        return StringUtils.defaultIfBlank(runtimeSecretKey, secretKey);
    }

    public String getRootPrefix() {
        return normalizeSegment(rootPrefix, "seatunnel-web-upload");
    }

    public int getSessionTtlHours() {
        return sessionTtlHours > 0 ? sessionTtlHours : 24;
    }

    public String objectKeyPrefix(Long jobDefinitionId, String sessionId) {
        if (jobDefinitionId == null || jobDefinitionId <= 0) {
            throw new IllegalArgumentException("jobDefinitionId is required for WEB_UPLOAD");
        }
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("uploadSessionId is required for WEB_UPLOAD");
        }
        return getRootPrefix() + "/" + jobDefinitionId + "/" + sessionId.trim();
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
