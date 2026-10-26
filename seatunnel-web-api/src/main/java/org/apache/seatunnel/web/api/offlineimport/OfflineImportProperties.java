package org.apache.seatunnel.web.api.offlineimport;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for DMP/SQL offline imports. DMP dumps are restored through the
 * vendor's native client tool, so the tool paths are deployment-level inputs
 * and stay empty (feature degrades with a clear error) until configured.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "seatunnel.web.offline-import")
public class OfflineImportProperties {

    /** Local scratch directory for DMP files downloaded from object storage. */
    private String workDir = "tmp/offline-import";

    /** Upper bound for one SQL script loaded into memory. */
    private long sqlMaxFileBytes = 268435456L;

    /** Per-statement wall-clock limit for JDBC SQL imports. */
    private int sqlStatementTimeoutSeconds = 300;

    /** Hard wall-clock limit for one external tool process. */
    private long processTimeoutMinutes = 240L;

    /** Absolute path of the Dameng dimp executable. */
    private String damengDimpPath;

    /** Absolute path of the KingbaseES sys_restore executable. */
    private String kingbaseSysRestorePath;
}
