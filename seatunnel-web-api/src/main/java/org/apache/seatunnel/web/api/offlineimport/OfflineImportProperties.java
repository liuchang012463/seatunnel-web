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

    /**
     * Upper bound for one SQL script loaded into memory. The splitter keeps
     * the decoded script and its statements in the heap, so the default stays
     * well below a typical 1 GB API heap.
     */
    private long sqlMaxFileBytes = 67108864L;

    /** Per-statement JDBC timeout; 0 disables it. */
    private long sqlStatementTimeoutSeconds = 3600L;

    /** Upper bound for one DMP backup downloaded to the scratch directory. */
    private long dmpMaxFileBytes = 10737418240L;

    /** Hard wall-clock limit for one external tool process. */
    private long processTimeoutMinutes = 240L;

    /**
     * A RUNNING/QUEUED task whose heartbeat is older than this is considered
     * orphaned. Keeps a second web instance from reclaiming a live task.
     */
    private long staleActiveMinutes = 5L;

    /** Absolute path of the Dameng dimp executable. */
    private String damengDimpPath;

    /** Absolute path of the KingbaseES sys_restore executable. */
    private String kingbaseSysRestorePath;
}
