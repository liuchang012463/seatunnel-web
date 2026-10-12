package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenMetadataJobLineageServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private OpenMetadataClient openMetadataClient;

    @Mock
    private MetadataBindingDao metadataBindingDao;

    @Mock
    private JobInstanceDao jobInstanceDao;

    @Mock
    private StreamingJobInstanceDao streamingJobInstanceDao;

    @InjectMocks
    private OpenMetadataJobLineageService service;

    @Test
    void writesMultipleSourceColumnsToOneTargetColumnAfterBatchSubmission() throws Exception {
        JobInstanceVO instance = instance();
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(10L)).thenReturn(binding("sample_service"));
        when(metadataBindingDao.queryByDataSourceId(20L)).thenReturn(binding("sample_service"));

        OpenMetadataTable source = table(
                "source-table-id", "sample_service.db.source_schema.orders",
                "first_name", "last_name");
        OpenMetadataTable target = table(
                "target-table-id", "sample_service.db.target_schema.orders_summary",
                "display_name");
        when(openMetadataClient.getTableByName("sample_service.db.source_schema.orders"))
                .thenReturn(source);
        when(openMetadataClient.getTableByName("sample_service.db.target_schema.orders_summary"))
                .thenReturn(target);

        service.recordBatchSubmission(instance, "engine-job-42");

        ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(openMetadataClient).addLineage(requestCaptor.capture());
        JsonNode edge = requestCaptor.getValue().path("edge");
        JsonNode columnLineage = edge.path("lineageDetails").path("columnsLineage").get(0);
        assertNotNull(columnLineage);
        assertEquals("sample_service.db.target_schema.orders_summary.display_name",
                columnLineage.path("toColumn").asText());
        assertEquals(List.of(
                        "sample_service.db.source_schema.orders.first_name",
                        "sample_service.db.source_schema.orders.last_name"),
                OBJECT_MAPPER.convertValue(columnLineage.path("fromColumns"),
                        OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));

        JsonNode description = OBJECT_MAPPER.readTree(
                edge.path("lineageDetails").path("description").asText());
        assertEquals("BATCH", description.path("job").path("kind").asText());
        assertEquals("200", description.path("job").path("definitionId").asText());
        assertEquals("Customer summary", description.path("job").path("name").asText());
        assertEquals("100", description.path("run").path("instanceId").asText());
        assertEquals("engine-job-42", description.path("run").path("engineJobId").asText());
    }

    @Test
    void skipsLineageWhenOpenMetadataHasNotRegisteredTheTables() {
        JobInstanceVO instance = instance();
        when(jobInstanceDao.selectDetailById(100L)).thenReturn(instance);
        when(metadataBindingDao.queryByDataSourceId(10L)).thenReturn(binding("sample_service"));
        when(metadataBindingDao.queryByDataSourceId(20L)).thenReturn(binding("sample_service"));
        when(openMetadataClient.getTableByName(anyString())).thenReturn(null);

        service.recordBatchSubmission(instance, "engine-job-42");

        verify(openMetadataClient, never()).addLineage(org.mockito.ArgumentMatchers.any());
    }

    private static JobInstanceVO instance() {
        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(100L);
        instance.setJobDefinitionId(200L);
        instance.setJobName("Customer summary");
        instance.setDefinitionMode("GUIDE_SINGLE");
        instance.setJobType("BATCH");
        instance.setRunMode("MANUAL");
        instance.setSourceDatasourceId(10L);
        instance.setSinkDatasourceId(20L);
        instance.setSourceTable("db.source_schema.orders");
        instance.setSinkTable("db.target_schema.orders_summary");
        instance.setRuntimeConfig("""
                transform = [{
                  FieldMapper {
                    plugin_input = source
                    plugin_output = sink
                    field_mapper {
                      first_name = display_name
                      last_name = display_name
                    }
                  }
                }]
                """);
        return instance;
    }

    private static MetadataSourceBinding binding(String serviceFqn) {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setOmServiceFqn(serviceFqn);
        return binding;
    }

    private static OpenMetadataTable table(String id, String fqn, String... columns) {
        OpenMetadataTable table = new OpenMetadataTable();
        table.setId(id);
        table.setFullyQualifiedName(fqn);
        table.setServiceFullyQualifiedName("sample_service");
        table.setColumns(java.util.Arrays.stream(columns).map(name -> {
            OpenMetadataColumn column = new OpenMetadataColumn();
            column.setName(name);
            return column;
        }).toList());
        return table;
    }
}
