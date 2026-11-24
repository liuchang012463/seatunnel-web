package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO.FieldLink;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO.Task;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO.TaskKind;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openmetadata.schema.api.lineage.EsLineageData;
import org.openmetadata.schema.api.lineage.RelationshipRef;
import org.openmetadata.schema.type.ColumnLineage;
import org.openmetadata.schema.type.Edge;
import org.openmetadata.schema.type.EntityLineage;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.LineageDetails;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSourceLineageServiceTest {
    @Mock private DataSourceDao sources;
    @Mock private MetadataBindingDao bindings;
    @Mock private JobDefinitionDao batch;
    @Mock private StreamingJobDefinitionDao streaming;
    @Mock private OpenMetadataClient om;

    private DataSourceLineageService service;

    @BeforeEach
    void bindSources() {
        service = new DataSourceLineageService(sources, bindings, batch, streaming, om);
        var first = new DataSource();
        first.setId(1L);
        first.setName("订单库");
        first.setDbType(DbType.MYSQL);
        var second = new DataSource();
        second.setId(2L);
        second.setName("数仓");
        second.setDbType(DbType.POSTGRE_SQL);
        var unused = new DataSource();
        unused.setId(3L);
        unused.setDbType(DbType.DAMENG);
        var revoked = new DataSource();
        revoked.setId(4L);
        revoked.setDbType(DbType.MYSQL);
        revoked.setStatus(DataSourceLifecycleStatus.REVOKED);
        var nonDatabase = new DataSource();
        nonDatabase.setId(5L);
        nonDatabase.setDbType(DbType.KAFKA);
        when(sources.queryAll()).thenReturn(List.of(first, second, unused, revoked, nonDatabase));
        when(bindings.queryByDataSourceIds(Set.of(1L, 2L, 3L))).thenReturn(
                List.of(binding(1), binding(2), binding(3)));
    }

    @Test
    void retainsSameSourceEdgesAndDeletedTasksAndReturnsOnlyTheirAssets() {
        var from = table("st_ds_1.\"sales.db\".public.orders");
        var same = table("st_ds_1.\"sales.db\".public.cleaned");
        var to = table("st_ds_2.warehouse.public.orders");
        var historical = table("st_ds_2.warehouse.public.old_orders");
        var foreign = table("st_ds_4.warehouse.public.orders");
        var cross = edge(from, to, job("STREAMING", 12));
        when(om.getAssetLineage(List.of("st_ds_1", "st_ds_2", "st_ds_3"))).thenReturn(List.of(
                cross, cross, edge(from, same, "手工说明"), edge(from, historical, job("BATCH", 13)),
                edge(from, foreign, null)));
        var active = new StreamingJobDefinitionEntity();
        active.setId(12L);
        active.setJobName("现有实时任务");
        when(streaming.queryByIds(List.of(12L))).thenReturn(List.of(active));
        when(batch.queryByIds(List.of(13L))).thenReturn(List.of());

        var graph = service.lineage();

        assertEquals(Set.of("1", "2"), Set.copyOf(graph.sources().stream().map(DataSourceLineageVO.Source::id).toList()));
        assertEquals(4, graph.assets().size());
        assertEquals("sales.db", graph.assets().getFirst().database());
        assertEquals(3, graph.edges().size());
        assertEquals(new Task("12", TaskKind.STREAMING, "现有实时任务", false), graph.edges().getFirst().task());
        assertEquals(same.getId().toString(), graph.edges().get(1).targetId());
        assertNull(graph.edges().get(1).task());
        assertEquals(new Task("13", TaskKind.BATCH, "历史任务", true), graph.edges().getLast().task());
    }

    @Test
    void projectsNativeManyToOneFieldMappingsWithoutLeakingOtherSources() {
        var source = table("st_ds_1.db.public.orders");
        var target = table("st_ds_2.db.public.mart");
        var foreign = table("st_ds_99.db.public.orders");
        String fromA = column(source, "amount");
        String fromB = column(source, "currency");
        String to = column(target, "gross");
        Edge incoming = fieldEdge(source, target,
                new ColumnLineage().withFromColumns(List.of(fromA, fromB, column(foreign, "secret")))
                        .withToColumn(to));
        Edge foreignEdge = fieldEdge(foreign, target,
                new ColumnLineage().withFromColumns(List.of(column(foreign, "secret"))).withToColumn(to));
        when(om.getEntityLineage("table", target.getId())).thenReturn(new EntityLineage()
                .withEntity(reference(target)).withNodes(List.of(reference(source), reference(foreign)))
                .withUpstreamEdges(List.of(incoming, incoming, foreignEdge)));
        when(om.getEntityLineage("table", foreign.getId())).thenReturn(new EntityLineage().withEntity(reference(foreign)));

        assertEquals(List.of(
                new FieldLink(source.getId().toString(), target.getId().toString(), fromA, to),
                new FieldLink(source.getId().toString(), target.getId().toString(), fromB, to)),
                service.fieldLineage("table", target.getId()));
        assertThrows(ServiceException.class, () -> service.fieldLineage("table", foreign.getId()));
        verifyNoInteractions(batch, streaming);
    }

    private static MetadataSourceBinding binding(long id) {
        var binding = new MetadataSourceBinding();
        binding.setDataSourceId(id);
        binding.setOmServiceFqn("st_ds_" + id);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        return binding;
    }

    private static EsLineageData edge(RelationshipRef from, RelationshipRef to, String description) {
        return new EsLineageData().withFromEntity(from).withToEntity(to).withDescription(description);
    }

    private static RelationshipRef table(String fqn) {
        return new RelationshipRef().withId(UUID.randomUUID()).withType("table").withFullyQualifiedName(fqn);
    }

    private static EntityReference reference(RelationshipRef ref) {
        return new EntityReference().withId(ref.getId()).withType(ref.getType()).withFullyQualifiedName(ref.getFullyQualifiedName());
    }

    private static Edge fieldEdge(RelationshipRef from, RelationshipRef to, ColumnLineage mapping) {
        return new Edge().withFromEntity(from.getId()).withToEntity(to.getId())
                .withLineageDetails(new LineageDetails().withColumnsLineage(List.of(mapping)));
    }

    private static String column(RelationshipRef table, String name) {
        return table.getFullyQualifiedName() + "." + name;
    }

    private static String job(String kind, long id) {
        return """
                {"schemaVersion":1,"producer":"seatunnel-web",
                 "job":{"kind":"%s","definitionId":"%d","name":"历史任务"}}
                """.formatted(kind, id);
    }
}
