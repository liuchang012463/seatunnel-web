package org.apache.seatunnel.web.api.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional server-side connection to the open_metadata_extension service.
 * The browser never receives this URL and the extension is disabled unless
 * both the flag and the URL are configured explicitly.
 */
@Data
@ConfigurationProperties(prefix = "metadata.extension")
public class MetadataExtensionProperties {

    private boolean enabled = false;

    /** Base URL of the extension's /omext route, for example http://om-ext:8000/omext. */
    private String baseUrl;

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 15000;
}
