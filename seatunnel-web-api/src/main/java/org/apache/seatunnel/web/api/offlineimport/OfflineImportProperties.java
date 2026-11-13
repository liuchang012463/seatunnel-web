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

    private static final long DATAPACK_ABSOLUTE_MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final long DATAPACK_CSV_ABSOLUTE_MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final int DATAPACK_ABSOLUTE_MAX_RECORDS = 50_000;
    private static final long DATAPACK_ABSOLUTE_MAX_CONFIG_BYTES = 1024L * 1024;

    /** Local scratch directory for DMP files downloaded from object storage. */
    private String workDir = "tmp/offline-import";

    /**
     * Upper bound for one SQL script loaded into memory. The splitter keeps
     * the decoded script and its statements in the heap, so the default stays
     * well below a typical 1 GB API heap.
     */
    private long sqlMaxFileBytes = 67108864L;

    /** Upper bound for one DMP backup downloaded to the scratch directory. */
    private long dmpMaxFileBytes = 10737418240L;

    /**
     * Default encrypted package size. The importer holds the encrypted bytes, decrypted JSON and
     * parsed object tree at once; a single package is therefore capped at 1/256 of the JVM heap,
     * with an absolute 32 MiB ceiling. Values configured above that runtime budget are clamped.
     */
    private long datapackMaxFileBytes = 8L * 1024 * 1024;

    /** Encrypted TOTAL ZIP size; one CSV import is capped at 1/64 of heap with a 64 MiB ceiling. */
    private long datapackCsvMaxFileBytes = DATAPACK_CSV_ABSOLUTE_MAX_FILE_BYTES;

    /** Must match the exporting tool because DRPK1 does not store this value in its header. */
    private int datapackPbkdf2Iterations = 120000;

    /** Parsed JSON objects add substantial per-record overhead; this configurable value is hard-capped. */
    private int datapackMaxRecords = 25_000;

    /** Persisted field mappings use TEXT/CLOB; keep their UTF-8 JSON below one MiB on both databases. */
    private long datapackTaskConfigMaxBytes = DATAPACK_ABSOLUTE_MAX_CONFIG_BYTES;

    /** Per-statement wall-clock limit for JDBC SQL imports. */
    private int sqlStatementTimeoutSeconds = 300;

    /** Hard wall-clock limit for one external tool process. */
    private long processTimeoutMinutes = 240L;

    /**
     * A RUNNING/QUEUED task whose heartbeat is older than this is considered
     * orphaned. Keeps a second web instance from reclaiming a live task.
     */
    private long staleActiveMinutes = 5L;

    /** Absolute path of the Dameng dimp executable; child library paths use its bin/ and sibling lib/. */
    private String damengDimpPath;

    /** Absolute path of the KingbaseES sys_restore executable; child library paths use its bin/ and sibling lib/. */
    private String kingbaseSysRestorePath;

    /** Runtime-aware hard bound for the in-memory package inspection/import path. */
    public long getDatapackMaxFileBytes() {
        long heapBudget = Math.max(1L, Runtime.getRuntime().maxMemory() / 256L);
        return Math.min(datapackMaxFileBytes, Math.min(DATAPACK_ABSOLUTE_MAX_FILE_BYTES, heapBudget));
    }

    /** Runtime-aware bound for encrypted CSV ZIP inspection and extraction, separate from JSON datapacks. */
    public long getDatapackCsvMaxFileBytes() {
        long heapBudget = Math.max(1L, Runtime.getRuntime().maxMemory() / 64L);
        return Math.min(datapackCsvMaxFileBytes, Math.min(DATAPACK_CSV_ABSOLUTE_MAX_FILE_BYTES, heapBudget));
    }

    public int getDatapackMaxRecords() {
        return Math.min(datapackMaxRecords, DATAPACK_ABSOLUTE_MAX_RECORDS);
    }

    public long getDatapackTaskConfigMaxBytes() {
        return Math.min(datapackTaskConfigMaxBytes, DATAPACK_ABSOLUTE_MAX_CONFIG_BYTES);
    }
}
