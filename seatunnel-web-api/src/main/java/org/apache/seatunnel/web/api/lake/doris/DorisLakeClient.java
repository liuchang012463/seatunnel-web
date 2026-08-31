package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.contract.TargetContract;

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

    List<String> listCatalogs();

    boolean catalogExists(String catalogName);

    void createCatalog(String catalogName, Map<String, String> properties);

    void dropCatalog(String catalogName);

    /** Executes a caller-generated Doris statement outside the local DB transaction. */
    void execute(String sql);

    @Override
    default void close() {
        // The data source is owned by LakeDataSourceResolver.
    }
}
