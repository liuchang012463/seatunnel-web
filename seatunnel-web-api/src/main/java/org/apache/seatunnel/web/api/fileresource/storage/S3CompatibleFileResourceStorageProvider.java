package org.apache.seatunnel.web.api.fileresource.storage;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.s3.client.S3ObjectStorageClient;
import org.apache.seatunnel.plugin.datasource.s3.param.ObjectStorageCredentialMode;
import org.apache.seatunnel.plugin.datasource.s3.param.S3ConnectionParam;
import org.apache.seatunnel.web.api.fileresource.FileResourcePathUtils;
import org.apache.seatunnel.web.core.fileupload.BuiltInMinioProperties;
import org.apache.seatunnel.web.core.fileresource.FileResourceReference;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * S3-compatible provider used by the resource catalog.
 *
 * <p>MinIO is one compatible deployment, but it is deliberately not part of
 * this provider's public contract.  The existing AWS SDK based client is
 * reused for listing, upload, deletion, and streaming download.</p>
 */
@Component
public class S3CompatibleFileResourceStorageProvider implements FileResourceStorageProvider {

    public static final String PROVIDER_TYPE = "S3_COMPATIBLE";

    private static final String DEFAULT_ROOT_PREFIX = "seatunnel-web-resource";
    private static final int MAX_OBJECT_KEY_LENGTH = 1024;

    private final FileResourceStorageProperties properties;
    private final BuiltInMinioProperties legacyProperties;
    private final S3ObjectStorageClient objectStorageClient;

    public S3CompatibleFileResourceStorageProvider(
            FileResourceStorageProperties properties,
            BuiltInMinioProperties legacyProperties) {
        this(properties, legacyProperties, new S3ObjectStorageClient());
    }

    S3CompatibleFileResourceStorageProvider(
            FileResourceStorageProperties properties,
            BuiltInMinioProperties legacyProperties,
            S3ObjectStorageClient objectStorageClient) {
        this.properties = properties;
        this.legacyProperties = legacyProperties;
        this.objectStorageClient = objectStorageClient;
    }

    @Override
    public String providerType() {
        validateConfiguredProvider();
        return PROVIDER_TYPE;
    }

    @Override
    public String bucket() {
        validateConfiguredProvider();
        return required(
                StringUtils.defaultIfBlank(properties.getBucket(), legacyBucket()),
                "SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_BUCKET",
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_BUCKET");
    }

    @Override
    public String objectKey(String logicalPath) {
        String normalized = FileResourcePathUtils.normalizePath(logicalPath);
        if ("/".equals(normalized)) {
            throw new IllegalArgumentException("A file resource object path cannot be root");
        }
        String key = rootPrefix() + normalized;
        return validateObjectKey(key);
    }

    @Override
    public void ensureBucket() {
        objectStorageClient.ensureBucket(writeConnectionParam());
    }

    @Override
    public String upload(String objectKey, InputStream input, long size, String contentType) {
        return objectStorageClient.putObject(
                writeConnectionParam(), validateObjectKey(objectKey), input, size, contentType);
    }

    @Override
    public void createDirectory(String objectKey) {
        String key = validateObjectKey(objectKey) + "/";
        objectStorageClient.putObject(
                writeConnectionParam(),
                key,
                new ByteArrayInputStream(new byte[0]),
                0,
                "application/x-directory");
    }

    @Override
    public List<StorageEntry> list(String logicalPath) {
        String normalized = FileResourcePathUtils.normalizePath(logicalPath);
        String currentKey = "/".equals(normalized)
                ? rootPrefix()
                : objectKey(normalized);
        List<FileEntryVO> entries = objectStorageClient.listEntries(
                writeConnectionParam(), "/" + currentKey);
        return entries.stream()
                .map(entry -> new StorageEntry(
                        entry.getName(),
                        stripLeadingSlash(entry.getPath()),
                        entry.getType(),
                        entry.getSize(),
                        entry.getModifiedTime()))
                .toList();
    }

    @Override
    public void delete(String objectKey) {
        objectStorageClient.deleteObjects(
                writeConnectionParam(), List.of(validateObjectKey(objectKey)));
    }

    @Override
    public void deletePrefix(String objectKey) {
        objectStorageClient.deletePrefix(
                writeConnectionParam(), validateObjectKey(objectKey) + "/");
    }

    @Override
    public StorageObjectMetadata head(String objectKey) {
        var metadata = objectStorageClient.headObject(writeConnectionParam(), validateObjectKey(objectKey));
        return new StorageObjectMetadata(
                metadata.getContentLength(), metadata.getContentType(), metadata.getETag());
    }

    @Override
    public void download(String objectKey, OutputStream output) throws IOException {
        objectStorageClient.downloadObject(
                writeConnectionParam(), validateObjectKey(objectKey), output);
    }

    @Override
    public FileResourceReference executionReference(String objectKey) {
        String endpoint = StringUtils.defaultIfBlank(
                properties.getRuntimeEndpoint(),
                StringUtils.defaultIfBlank(
                        legacyRuntimeEndpoint(),
                        StringUtils.defaultIfBlank(properties.getEndpoint(), legacyEndpoint())));
        String region = StringUtils.defaultIfBlank(properties.getRegion(), "us-east-1");
        ObjectStorageCredentialMode credentialMode = credentialMode();
        String accessKey = credentialMode == ObjectStorageCredentialMode.STATIC
                ? required(
                        StringUtils.defaultIfBlank(
                                properties.getRuntimeAccessKey(),
                                StringUtils.defaultIfBlank(
                                        legacyRuntimeAccessKey(),
                                        StringUtils.defaultIfBlank(properties.getAccessKey(), legacyAccessKey()))),
                        "SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_RUNTIME_ACCESS_KEY",
                        "SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_ACCESS_KEY",
                        "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_RUNTIME_ACCESS_KEY",
                        "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_ACCESS_KEY")
                : null;
        String secretKey = credentialMode == ObjectStorageCredentialMode.STATIC
                ? required(
                        StringUtils.defaultIfBlank(
                                properties.getRuntimeSecretKey(),
                                StringUtils.defaultIfBlank(
                                        legacyRuntimeSecretKey(),
                                        StringUtils.defaultIfBlank(properties.getSecretKey(), legacySecretKey()))),
                        "SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_RUNTIME_SECRET_KEY",
                        "SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_SECRET_KEY",
                        "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_RUNTIME_SECRET_KEY",
                        "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_SECRET_KEY")
                : null;
        return new FileResourceReference(
                providerType(),
                required(endpoint, "SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_RUNTIME_ENDPOINT",
                        "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_RUNTIME_ENDPOINT"),
                region,
                bucket(),
                "/",
                validateObjectKey(objectKey),
                credentialMode.name(),
                accessKey,
                secretKey,
                properties.isPathStyleAccess());
    }

    private S3ConnectionParam writeConnectionParam() {
        S3ConnectionParam param = new S3ConnectionParam();
        param.setEndpoint(required(
                StringUtils.defaultIfBlank(properties.getEndpoint(), legacyEndpoint()),
                "SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_ENDPOINT",
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_ENDPOINT"));
        param.setRegion(StringUtils.defaultIfBlank(properties.getRegion(), "us-east-1"));
        param.setBucket(bucket());
        param.setBasePath("/");
        param.setCredentialMode(credentialMode());
        param.setPathStyleAccess(properties.isPathStyleAccess());
        param.setConnectTimeoutMs(properties.getConnectTimeoutMs());
        param.setRequestTimeoutMs(properties.getRequestTimeoutMs());
        if (param.getCredentialMode() == ObjectStorageCredentialMode.STATIC) {
            param.setAccessKey(required(
                    StringUtils.defaultIfBlank(properties.getAccessKey(), legacyAccessKey()),
                    "SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_ACCESS_KEY",
                    "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_ACCESS_KEY"));
            param.setSecretKey(required(
                    StringUtils.defaultIfBlank(properties.getSecretKey(), legacySecretKey()),
                    "SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_SECRET_KEY",
                    "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_SECRET_KEY"));
        }
        if (param.getConnectTimeoutMs() == null || param.getConnectTimeoutMs() <= 0) {
            param.setConnectTimeoutMs(10000);
        }
        if (param.getRequestTimeoutMs() == null || param.getRequestTimeoutMs() <= 0) {
            param.setRequestTimeoutMs(30000);
        }
        return param;
    }

    private ObjectStorageCredentialMode credentialMode() {
        String value = StringUtils.defaultIfBlank(properties.getCredentialMode(), "STATIC");
        try {
            return ObjectStorageCredentialMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported file resource storage credential mode: " + value, e);
        }
    }

    private void validateConfiguredProvider() {
        String configured = StringUtils.defaultIfBlank(properties.getProvider(), PROVIDER_TYPE)
                .trim().toUpperCase(Locale.ROOT);
        if (!PROVIDER_TYPE.equals(configured)
                && !"S3".equals(configured)
                && !"MINIO".equals(configured)) {
            throw new IllegalStateException(
                    "Unsupported file resource storage provider: " + configured);
        }
    }

    private String rootPrefix() {
        String configured = StringUtils.defaultIfBlank(properties.getRootPrefix(), DEFAULT_ROOT_PREFIX);
        String normalized = FileResourcePathUtils.normalizePath(configured);
        if ("/".equals(normalized)) {
            throw new IllegalStateException("File resource storage root prefix cannot be root");
        }
        return normalized.substring(1);
    }

    private String validateObjectKey(String objectKey) {
        // Object keys are derived from validated logical paths.  Preserve
        // filename whitespace here instead of trimming it away.
        String value = StringUtils.defaultString(objectKey);
        if (value.isEmpty() || value.startsWith("/") || value.indexOf('\\') >= 0
                || value.indexOf('\u0000') >= 0 || value.length() > MAX_OBJECT_KEY_LENGTH
                || value.isBlank()) {
            throw new IllegalArgumentException("Object key is invalid");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Object key is invalid");
            }
        }
        String root = rootPrefix();
        if (!value.startsWith(root + "/")) {
            throw new IllegalArgumentException("Object key is outside file resource root");
        }
        return value;
    }

    private String stripLeadingSlash(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path;
    }

    private String legacyEndpoint() {
        return legacyProperties == null ? null : legacyProperties.getEndpoint();
    }

    private String legacyRuntimeEndpoint() {
        return legacyProperties == null ? null : legacyProperties.getRuntimeEndpoint();
    }

    private String legacyBucket() {
        return legacyProperties == null ? null : legacyProperties.getBucket();
    }

    private String legacyAccessKey() {
        return legacyProperties == null ? null : legacyProperties.getAccessKey();
    }

    private String legacySecretKey() {
        return legacyProperties == null ? null : legacyProperties.getSecretKey();
    }

    private String legacyRuntimeAccessKey() {
        return legacyProperties == null ? null : legacyProperties.getRuntimeAccessKey();
    }

    private String legacyRuntimeSecretKey() {
        return legacyProperties == null ? null : legacyProperties.getRuntimeSecretKey();
    }

    private String required(String value, String... propertyNames) {
        if (StringUtils.isNotBlank(value)) {
            return value.trim();
        }
        throw new IllegalStateException("Missing file resource storage configuration: "
                + String.join(" or ", propertyNames));
    }
}
