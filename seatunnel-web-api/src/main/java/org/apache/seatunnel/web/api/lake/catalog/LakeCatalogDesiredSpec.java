package org.apache.seatunnel.web.api.lake.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

import java.util.List;
import java.util.Map;

/**
 * Non-secret desired state for one Doris JDBC external catalog.
 *
 * <p>Credentials are deliberately absent.  A service resolves them from the
 * server-side source DataSource only at external-operation time.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LakeCatalogDesiredSpec(
        String catalogName,
        Long sourceDataSourceId,
        String sourceDataSourceRevision,
        LakeJdbcAdapterType adapter,
        LakeCatalogScope scope,
        String jdbcUrl,
        String driverUrl,
        String driverClass,
        String driverChecksum,
        String driverRegistryRevision,
        String credentialRevision,
        List<String> databaseInclude,
        List<String> tableInclude,
        Map<String, String> options) {

    public LakeCatalogDesiredSpec {
        databaseInclude = immutableList(databaseInclude);
        tableInclude = immutableList(tableInclude);
        options = immutableMap(options);
    }

    public String sourceRevision() {
        return sourceDataSourceRevision;
    }

    public List<String> databases() {
        return databaseInclude;
    }

    public List<String> tables() {
        return tableInclude;
    }

    private static <T> List<T> immutableList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }
}
