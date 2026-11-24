package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO.Asset;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO.FieldLink;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO.Source;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO.Task;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO.TaskKind;
import org.apache.seatunnel.web.spi.enums.Status;
import org.openmetadata.schema.api.lineage.EsLineageData;
import org.openmetadata.schema.api.lineage.RelationshipRef;
import org.openmetadata.schema.type.ColumnLineage;
import org.openmetadata.schema.type.Edge;
import org.openmetadata.schema.type.EntityLineage;
import org.openmetadata.schema.type.EntityReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.seatunnel.web.api.metadata.OpenMetadataFqn.unquote;

/** Keeps the graph scoped to registered assets while preserving the stored direction of every edge. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceLineageService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSourceDao dataSourceDao;
    private final MetadataBindingDao metadataBindingDao;
    private final JobDefinitionDao batchDefinitions;
    private final StreamingJobDefinitionDao streamingDefinitions;
    private final OpenMetadataClient openMetadataClient;

    public DataSourceLineageVO lineage() {
        Map<String, Source> scope = boundSources();
        if (scope.isEmpty()) {
            return new DataSourceLineageVO(List.of(), List.of(), List.of(), null);
        }
        long started = System.nanoTime();
        openMetadataClient.assertFixedVersion();
        List<EsLineageData> stored = openMetadataClient.getAssetLineage(List.copyOf(scope.keySet()));
        String readAt = Instant.now().toString();

        Map<String, Asset> assets = new LinkedHashMap<>();
        Map<AssetPair, JobReference> relationships = new LinkedHashMap<>();
        for (EsLineageData edge : stored) {
            Asset from = asset(edge.getFromEntity(), scope);
            Asset to = asset(edge.getToEntity(), scope);
            if (from == null || to == null) {
                continue;
            }
            assets.putIfAbsent(from.id(), from);
            assets.putIfAbsent(to.id(), to);
            relationships.putIfAbsent(new AssetPair(from.id(), to.id()), jobReference(edge.getDescription()));
        }
        Map<JobKey, String> currentJobs = currentJobs(relationships.values().stream()
                .filter(java.util.Objects::nonNull).map(JobReference::key).collect(Collectors.toSet()));
        List<DataSourceLineageVO.Link> links = relationships.entrySet().stream()
                .map(entry -> new DataSourceLineageVO.Link(entry.getKey().sourceId(), entry.getKey().targetId(),
                        toTask(entry.getValue(), currentJobs)))
                .toList();
        Set<String> usedSources = assets.values().stream().map(Asset::dataSourceId).collect(Collectors.toSet());
        List<Source> sources = scope.values().stream().filter(source -> usedSources.contains(source.id()))
                .distinct().sorted(Comparator.comparing(Source::name).thenComparing(Source::id)).toList();
        log.debug("Read asset lineage: sources={}, assets={}, edges={}, elapsedMs={}",
                sources.size(), assets.size(), links.size(), (System.nanoTime() - started) / 1_000_000);
        return new DataSourceLineageVO(sources, List.copyOf(assets.values()), links, readAt);
    }

    public List<FieldLink> fieldLineage(String entityType, UUID entityId) {
        Map<String, Source> scope = boundSources();
        if (!"table".equals(entityType) || scope.isEmpty()) {
            throw invalidAsset();
        }
        long started = System.nanoTime();
        openMetadataClient.assertFixedVersion();
        EntityLineage lineage = openMetadataClient.getEntityLineage(entityType, entityId);
        Asset root = lineage == null ? null : asset(lineage.getEntity(), scope);
        if (root == null || !entityId.toString().equals(root.id()) || !entityType.equals(root.type())) {
            throw invalidAsset();
        }

        // A shared OM credential can see more than this graph. Scope both field endpoints.
        Map<UUID, Asset> assets = new HashMap<>();
        for (EntityReference reference : safe(lineage.getNodes())) {
            Asset neighbor = asset(reference, scope);
            if (neighbor != null) {
                assets.put(reference.getId(), neighbor);
            }
        }
        assets.put(entityId, root);
        Set<FieldLink> fields = new LinkedHashSet<>();
        collectFields(lineage.getUpstreamEdges(), entityId, assets, fields);
        collectFields(lineage.getDownstreamEdges(), entityId, assets, fields);
        log.debug("Read field lineage: entityType={}, entityId={}, mappings={}, elapsedMs={}",
                entityType, entityId, fields.size(), (System.nanoTime() - started) / 1_000_000);
        return List.copyOf(fields);
    }

    private Map<String, Source> boundSources() {
        Map<Long, DataSource> sources = new HashMap<>();
        for (DataSource source : safe(dataSourceDao.queryAll())) {
            if (source != null && source.getId() != null && source.getStatus() != DataSourceLifecycleStatus.REVOKED
                    && DataExplorationService.supportsTableExploration(source.getDbType())) {
                sources.put(source.getId(), source);
            }
        }
        Map<String, Source> result = new LinkedHashMap<>();
        for (MetadataSourceBinding binding : safe(metadataBindingDao.queryByDataSourceIds(sources.keySet()))) {
            if (binding == null || binding.getDesiredState() != MetadataDesiredState.ACTIVE) {
                continue;
            }
            DataSource source = sources.get(binding.getDataSourceId());
            if (source == null || !StringUtils.hasText(binding.getOmServiceId())
                    && !StringUtils.hasText(binding.getOmServiceFqn())) {
                continue;
            }
            String serviceFqn = StringUtils.hasText(binding.getOmServiceFqn())
                    ? binding.getOmServiceFqn() : MetadataStableName.serviceFqn(source.getId());
            String id = source.getId().toString();
            result.putIfAbsent(serviceFqn, new Source(id,
                    StringUtils.hasText(source.getName()) ? source.getName() : id,
                    source.getDbType() == null ? null : source.getDbType().name()));
        }
        return result;
    }

    private static Asset asset(RelationshipRef reference, Map<String, Source> scope) {
        return reference == null ? null : asset(reference.getId(), reference.getType(),
                reference.getFullyQualifiedName(), scope);
    }

    private static Asset asset(EntityReference reference, Map<String, Source> scope) {
        return reference == null || Boolean.TRUE.equals(reference.getDeleted()) ? null
                : asset(reference.getId(), reference.getType(), reference.getFullyQualifiedName(), scope);
    }

    private static Asset asset(UUID id, String entityType, String fqn, Map<String, Source> scope) {
        if (id == null || !"table".equals(entityType) || fqn == null) {
            return null;
        }
        List<String> parts = OpenMetadataFqn.parts(fqn);
        if (parts.size() != 4) {
            return null;
        }
        Source source = scope.get(parts.getFirst());
        if (source == null) {
            return null;
        }
        return new Asset(id.toString(), source.id(), entityType,
                unquote(parts.get(1)), unquote(parts.get(2)),
                unquote(parts.getLast()), fqn);
    }

    private static JobReference jobReference(String description) {
        if (description == null || !description.stripLeading().startsWith("{")) {
            return null;
        }
        try {
            var payload = JSON.readTree(description);
            if (payload.path("schemaVersion").asInt() != 1 || !"seatunnel-web".equals(payload.path("producer").asText())) {
                return null;
            }
            // The producer persists the definition identity independently of the run and display name.
            var job = payload.path("job");
            TaskKind kind = TaskKind.valueOf(job.path("kind").asText());
            long id = Long.parseLong(job.path("definitionId").asText());
            return id > 0 ? new JobReference(new JobKey(kind, id), job.path("name").asText(null)) : null;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return null;
        }
    }

    private Map<JobKey, String> currentJobs(Set<JobKey> keys) {
        Map<JobKey, String> found = new HashMap<>();
        List<Long> batchIds = new ArrayList<>();
        List<Long> streamingIds = new ArrayList<>();
        for (JobKey key : keys) {
            (key.kind() == TaskKind.BATCH ? batchIds : streamingIds).add(key.id());
        }
        if (!batchIds.isEmpty()) {
            batchDefinitions.queryByIds(batchIds).forEach(job -> found.put(
                    new JobKey(TaskKind.BATCH, job.getId()), job.getJobName()));
        }
        if (!streamingIds.isEmpty()) {
            streamingDefinitions.queryByIds(streamingIds).forEach(job -> found.put(
                    new JobKey(TaskKind.STREAMING, job.getId()), job.getJobName()));
        }
        return found;
    }

    private static Task toTask(JobReference reference, Map<JobKey, String> jobs) {
        if (reference == null) {
            return null;
        }
        JobKey key = reference.key();
        String name = jobs.get(key);
        return new Task(Long.toString(key.id()), key.kind(),
                StringUtils.hasText(name) ? name : reference.name(), !jobs.containsKey(key));
    }

    private static void collectFields(List<Edge> edges, UUID entityId, Map<UUID, Asset> assets, Set<FieldLink> fields) {
        for (Edge edge : safe(edges)) {
            if (!entityId.equals(edge.getFromEntity()) && !entityId.equals(edge.getToEntity())) {
                continue;
            }
            Asset source = assets.get(edge.getFromEntity());
            Asset target = assets.get(edge.getToEntity());
            if (source == null || target == null || edge.getLineageDetails() == null) {
                continue;
            }
            for (ColumnLineage column : safe(edge.getLineageDetails().getColumnsLineage())) {
                String to = column.getToColumn();
                if (!ownedColumn(to, target)) {
                    continue;
                }
                for (String from : safe(column.getFromColumns())) {
                    if (ownedColumn(from, source)) {
                        fields.add(new FieldLink(source.id(), target.id(), from, to));
                    }
                }
            }
        }
    }

    private static boolean ownedColumn(String column, Asset asset) {
        return column != null && column.startsWith(asset.fullyQualifiedName() + ".");
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static ServiceException invalidAsset() {
        return new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "资产已不存在或不属于可查看的数据源");
    }

    private record AssetPair(String sourceId, String targetId) {}

    private record JobKey(TaskKind kind, long id) {}

    private record JobReference(JobKey key, String name) {}
}
