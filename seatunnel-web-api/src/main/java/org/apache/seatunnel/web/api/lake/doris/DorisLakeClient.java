package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpec;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogValidationResult;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcCatalogDdlBuilder;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcDriverRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal Doris control-plane client.  Implementations must keep credentials
 * and raw SQL out of exception messages and operation journals.
 */
public interface DorisLakeClient extends AutoCloseable {

    boolean ping();

    List<String> listDatabases();

    boolean databaseExists(String databaseName);

    void createDatabase(String databaseName);

    void dropDatabase(String databaseName);

    List<String> listTables(String databaseName);

    boolean tableExists(String databaseName, String tableName);

    void createTable(String databaseName, String tableName, TargetContract contract);

    void createTable(String databaseName, String tableName, TargetContract contract,
                     Map<String, String> tableProperties);

    void dropTable(String databaseName, String tableName);

    String showCreateTable(String databaseName, String tableName);

    TargetContract readContract(String databaseName, String tableName);

    List<DorisColumnMetadata> listColumns(String databaseName, String tableName);

    /** Reads a bounded column allowlist from one external catalog table. */
    default List<DorisColumnMetadata> listCatalogColumns(
            String catalogName, String databaseName, String tableName) {
        throw new UnsupportedOperationException("Catalog column metadata is unavailable");
    }

    /** Lists databases exposed by one external catalog for the structured picker. */
    default List<String> listCatalogDatabases(String catalogName) {
        throw new UnsupportedOperationException("Catalog database metadata is unavailable");
    }

    /** Lists tables exposed by one external catalog database for the structured picker. */
    default List<String> listCatalogTables(String catalogName, String databaseName) {
        throw new UnsupportedOperationException("Catalog table metadata is unavailable");
    }

    Map<String, String> readTableProperties(String databaseName, String tableName);

    /** Applies only the explicitly allowlisted mutable table properties. */
    void alterTableProperties(String databaseName, String tableName,
                              Map<String, String> tableProperties);

    /** Reads bounded partition metadata without returning raw SQL. */
    List<DorisPartitionMetadata> listPartitions(String databaseName, String tableName);

    List<String> listCatalogs();

    boolean catalogExists(String catalogName);

    void createCatalog(String catalogName, Map<String, String> properties);

    /** Creates a JDBC catalog from a validated non-secret desired spec. */
    default void createCatalog(
            LakeCatalogDesiredSpec desiredSpec,
            LakeJdbcDriverRegistry driverRegistry,
            LakeJdbcCatalogDdlBuilder.CatalogCredentials credentials) {
        throw new UnsupportedOperationException("Validated catalog create is unavailable");
    }

    /**
     * Proves that Doris FE/BE can reach the source represented by a temporary
     * catalog.  The caller must provide a unique, short-lived catalog name in
     * {@code desiredSpec}; the method always attempts to remove it again.
     *
     * <p>A successful CREATE alone is not enough because some JDBC connectors
     * defer opening the source connection.  Listing databases forces a
     * bounded connector metadata request from Doris.</p>
     */
    default void probeSource(
            LakeCatalogDesiredSpec desiredSpec,
            LakeJdbcDriverRegistry driverRegistry,
            LakeJdbcCatalogDdlBuilder.CatalogCredentials credentials) {
        Objects.requireNonNull(desiredSpec, "desired spec");
        String catalogName = desiredSpec.catalogName();
        // Cleanup is attempted after every CREATE attempt.  Doris may accept
        // the DDL and fail while waiting for connector metadata, so tracking
        // only a completed Java call could leave a temporary catalog behind.
        boolean createAttempted = false;
        RuntimeException failure = null;
        try {
            createAttempted = true;
            createCatalog(desiredSpec, driverRegistry, credentials);
            // The result may legitimately be empty for a source with no
            // visible schemas; reaching the statement without an exception is
            // the signal that FE/BE reached the source connector.
            listCatalogDatabases(catalogName);
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (createAttempted) {
                try {
                    dropCatalog(catalogName);
                } catch (RuntimeException cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    /** Reads only Web-owned, non-secret properties from SHOW CATALOG. */
    Map<String, String> readCatalogProperties(String catalogName);

    /** Applies only non-secret allowlisted properties through bounded DDL. */
    void alterCatalogProperties(String catalogName, Map<String, String> properties);

    /** Applies a complete desired spec with execution-only source credentials. */
    void alterCatalog(String catalogName, LakeCatalogDesiredSpec desiredSpec,
                      LakeJdbcDriverRegistry driverRegistry,
                      LakeJdbcCatalogDdlBuilder.CatalogCredentials credentials);

    /** Refreshes connector metadata using Doris' bounded catalog statement. */
    void refreshCatalog(String catalogName);

    /** Performs an existence/read-only desired-state comparison. */
    LakeCatalogValidationResult validateCatalog(String catalogName,
                                                LakeCatalogDesiredSpec desiredSpec);

    void dropCatalog(String catalogName);

    @Override
    default void close() {
        // The data source is owned by LakeDataSourceResolver.
    }
}
