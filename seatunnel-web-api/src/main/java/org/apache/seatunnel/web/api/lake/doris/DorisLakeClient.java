package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpec;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogValidationResult;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcCatalogDdlBuilder;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcDriverRegistry;

import java.util.List;
import java.util.Map;

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

    Map<String, String> readTableProperties(String databaseName, String tableName);

    /** Applies only the explicitly allowlisted mutable table properties. */
    void alterTableProperties(String databaseName, String tableName,
                              Map<String, String> tableProperties);

    /** Reads bounded partition metadata without returning raw SQL. */
    List<DorisPartitionMetadata> listPartitions(String databaseName, String tableName);

    List<String> listCatalogs();

    boolean catalogExists(String catalogName);

    void createCatalog(String catalogName, Map<String, String> properties);

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
