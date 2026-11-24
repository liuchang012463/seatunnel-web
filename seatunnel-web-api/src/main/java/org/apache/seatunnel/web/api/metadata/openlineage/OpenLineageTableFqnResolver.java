package org.apache.seatunnel.web.api.metadata.openlineage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.DataExplorationService;
import org.apache.seatunnel.web.api.metadata.OpenMetadataFqn;
import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageSnapshot.Dataset;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationSchemaVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.apache.seatunnel.web.api.metadata.OpenMetadataFqn.unquote;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lineage-bridge.kafka", name = "enabled", havingValue = "true")
class OpenLineageTableFqnResolver {

    private final DataExplorationService exploration;

    Map<Dataset, TableIdentity> resolve(OpenLineageSnapshot snapshot) {
        Map<Long, List<Database>> databases = new HashMap<>();
        Map<Database, Optional<List<Schema>>> schemas = new HashMap<>();
        Map<Dataset, TableIdentity> identities = new HashMap<>();
        Stream.concat(snapshot.inputs().stream(), snapshot.outputs().stream())
                .distinct()
                .filter(this::isTableDataset)
                .forEach(dataset -> {
                    List<String> path = OpenMetadataFqn.parts(dataset.name());
                    if (path.size() < 2 || path.size() > 4) {
                        return;
                    }
                    List<Database> catalog = databases.computeIfAbsent(dataset.datasourceId(), this::readDatabases);
                    TableIdentity identity = resolveTable(path, catalog, schemas);
                    if (identity != null) {
                        identities.put(dataset, identity);
                    } else {
                        log.debug("OpenLineage retained local table path, instanceId={}, datasourceId={}, path={}",
                                snapshot.instanceId(), dataset.datasourceId(), dataset.name());
                    }
                });
        return identities;
    }

    private boolean isTableDataset(Dataset dataset) {
        return dataset != null && dataset.datasourceId() != null && dataset.datasourceId() > 0
                && dataset.name() != null
                && DataExplorationService.supportsTableExploration(DbType.of(dataset.dbTypeName()));
    }

    private TableIdentity resolveTable(
            List<String> path, List<Database> catalog, Map<Database, Optional<List<Schema>>> schemas) {
        if (path.size() == 4) {
            String databaseFqn = String.join(".", path.subList(0, 2));
            return catalog.stream().filter(database -> database.fqn().equals(databaseFqn))
                    .findFirst().map(database -> new TableIdentity(database.serviceName(), String.join(".", path)))
                    .orElse(null);
        }
        String schemaName = unquote(path.get(path.size() - 2));
        String table = path.getLast();
        if (path.size() == 3) {
            String databaseName = unquote(path.getFirst());
            List<Database> exact = catalog.stream().filter(database -> databaseName.equals(database.name())).toList();
            if (exact.size() == 1) {
                Database database = exact.getFirst();
                return new TableIdentity(database.serviceName(), database.fqn() + "." + path.get(1) + "." + table);
            }
            // A custom connector can group a physical database under OM's single "default" database.
            if (!exact.isEmpty() || catalog.size() != 1 || !"default".equals(catalog.getFirst().name())) {
                return null;
            }
        }
        TableIdentity match = null;
        for (Database database : catalog) {
            Optional<List<Schema>> catalogSchemas = schemas.computeIfAbsent(database, this::readSchemas);
            if (catalogSchemas.isEmpty()) {
                // An unreadable database could contain another schema with this name.
                return null;
            }
            for (Schema schema : catalogSchemas.get()) {
                if (!schemaName.equals(schema.name())) {
                    continue;
                }
                if (match != null) {
                    return null;
                }
                // The table itself might not have been scanned yet. Assert can retry this exact FQN.
                match = new TableIdentity(database.serviceName(), schema.fqn() + "." + table);
            }
        }
        return match;
    }

    private List<Database> readDatabases(Long datasourceId) {
        try {
            return exploration.listDatabases(datasourceId).stream()
                    .map(DataExplorationDatabaseVO::getFullyQualifiedName)
                    .filter(fqn -> fqn != null && !fqn.isBlank())
                    .map(OpenMetadataFqn::parts)
                    .filter(parts -> parts.size() == 2)
                    .map(parts -> new Database(datasourceId, parts.getFirst(), unquote(parts.getLast()),
                            String.join(".", parts)))
                    .distinct().toList();
        } catch (RuntimeException error) {
            log.debug("Cannot read OpenMetadata databases for lineage, datasourceId={}, error={}",
                    datasourceId, error.getClass().getSimpleName());
            return List.of();
        }
    }

    private Optional<List<Schema>> readSchemas(Database database) {
        try {
            return Optional.of(exploration.listSchemas(database.datasourceId(), database.fqn()).stream()
                    .map(DataExplorationSchemaVO::getFullyQualifiedName)
                    .filter(fqn -> fqn != null && fqn.startsWith(database.fqn() + "."))
                    .map(OpenMetadataFqn::parts)
                    .filter(parts -> parts.size() == 3)
                    .map(parts -> new Schema(unquote(parts.getLast()), String.join(".", parts)))
                    .distinct().toList());
        } catch (RuntimeException error) {
            log.debug("Cannot read OpenMetadata schemas for lineage, datasourceId={}, database={}, error={}",
                    database.datasourceId(), database.fqn(), error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private record Database(Long datasourceId, String serviceName, String name, String fqn) {}

    private record Schema(String name, String fqn) {}

    record TableIdentity(String serviceName, String fqn) {}
}
