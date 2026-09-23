package org.apache.seatunnel.web.api.fileresource.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Platform-owned object-storage settings for reusable file resources.
 *
 * <p>The endpoint and credentials intentionally have independent runtime
 * variants: the API can use an internal endpoint while the SeaTunnel engine
 * receives a reachable endpoint in {@code FileResourceReference}.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "seatunnel.web.file-resource.storage")
public class FileResourceStorageProperties {

    private String provider = "S3_COMPATIBLE";

    private String endpoint;

    private String runtimeEndpoint;

    private String uploadEndpoint;

    private String region = "us-east-1";

    private String bucket;

    private String accessKey;

    private String secretKey;

    private String runtimeAccessKey;

    private String runtimeSecretKey;

    /** New resources are kept in their own namespace, separate from WEB_UPLOAD sessions. */
    private String rootPrefix = "seatunnel-web-resource";

    private String credentialMode = "STATIC";

    private boolean pathStyleAccess = true;

    private Integer connectTimeoutMs = 10000;

    private Integer requestTimeoutMs = 30000;
}
