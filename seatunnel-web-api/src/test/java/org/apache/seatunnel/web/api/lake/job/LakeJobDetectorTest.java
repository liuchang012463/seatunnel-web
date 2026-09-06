package org.apache.seatunnel.web.api.lake.job;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchScriptJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideSingleJobSaveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class LakeJobDetectorTest {

    private static final long BINDING_ID = 7L;
    private static final long LAKE_DATA_SOURCE_ID = 99L;

    private LakeOdsTableMappingDao tableMappingDao;
    private LakeJobDetector detector;

    @BeforeEach
    void setUp() {
        LakeOdsDatabaseBindingDao bindingDao = Mockito.mock(LakeOdsDatabaseBindingDao.class);
        tableMappingDao = Mockito.mock(LakeOdsTableMappingDao.class);
        LakeProperties properties = new LakeProperties();
        properties.setDataSourceId(LAKE_DATA_SOURCE_ID);

        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(readyBinding());
        when(tableMappingDao.queryByBindingIdAndTargetTable(eq(BINDING_ID), eq("ods_orders")))
                .thenReturn(managedMapping());
        detector = new LakeJobDetector(bindingDao, tableMappingDao, properties);
    }

    @Test
    void exactSingleIncrementalAndStreamingUseTableRelations() {
        BatchGuideSingleJobSaveCommand batch = new BatchGuideSingleJobSaveCommand();
        batch.setOdsDatabaseBindingId(BINDING_ID);
        batch.setWorkflow(singleWorkflow());
        LakeJobDescriptor batchDescriptor = detector.detect(batch);
        assertTableDescriptor(batchDescriptor, LakeJobRuntimeType.BATCH);

        BatchGuideSingleIncrementalJobSaveCommand incremental =
                new BatchGuideSingleIncrementalJobSaveCommand();
        incremental.setOdsDatabaseBindingId(BINDING_ID);
        incremental.setWorkflow(singleWorkflow());
        LakeJobDescriptor incrementalDescriptor = detector.detect(incremental);
        assertTableDescriptor(incrementalDescriptor, LakeJobRuntimeType.BATCH);

        StreamingGuideSingleJobSaveCommand streaming = new StreamingGuideSingleJobSaveCommand();
        streaming.setOdsDatabaseBindingId(BINDING_ID);
        streaming.setWorkflow(singleWorkflow());
        LakeJobDescriptor streamingDescriptor = detector.detect(streaming);
        assertTableDescriptor(streamingDescriptor, LakeJobRuntimeType.STREAMING);
    }

    @Test
    void exactSingleUnmanagedMappingUsesTableRelation() {
        LakeOdsTableMapping mapping = managedMapping();
        mapping.setManagementLevel(LakeManagementLevel.UNMANAGED);
        when(tableMappingDao.queryByBindingIdAndTargetTable(eq(BINDING_ID), eq("ods_orders")))
                .thenReturn(mapping);

        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setOdsDatabaseBindingId(BINDING_ID);
        command.setWorkflow(singleWorkflow());

        assertTableDescriptor(detector.detect(command), LakeJobRuntimeType.BATCH);
    }

    @Test
    void multiAndWholeAlwaysUseNamespaceWithoutTableMapping() {
        BatchGuideMultiJobSaveCommand exact = new BatchGuideMultiJobSaveCommand();
        exact.setOdsDatabaseBindingId(BINDING_ID);
        exact.setContent(multiContent("3"));
        LakeJobDescriptor exactDescriptor = detector.detect(exact);
        assertNamespaceDescriptor(exactDescriptor, "\"matchMode\":\"3\"");

        BatchGuideMultiJobSaveCommand whole = new BatchGuideMultiJobSaveCommand();
        whole.setOdsDatabaseBindingId(BINDING_ID);
        whole.setContent(multiContent("4"));
        LakeJobDescriptor wholeDescriptor = detector.detect(whole);
        assertNamespaceDescriptor(wholeDescriptor, "\"matchMode\":\"4\"");

        StreamingGuideMultiJobSaveCommand streaming = new StreamingGuideMultiJobSaveCommand();
        streaming.setOdsDatabaseBindingId(BINDING_ID);
        streaming.setContent(multiContent("4"));
        LakeJobDescriptor streamingDescriptor = detector.detect(streaming);
        assertEquals(LakeJobRuntimeType.STREAMING, streamingDescriptor.jobRuntimeType());
        assertEquals(LakeRelationScope.NAMESPACE, streamingDescriptor.relationScope());
    }

    @Test
    void ordinaryWrongDataSourceAndNonReadyBindingAreNotLakeJobs() {
        BatchScriptJobSaveCommand script = new BatchScriptJobSaveCommand();
        assertNull(detector.detect(script));

        BatchGuideSingleJobSaveCommand wrongDataSource = new BatchGuideSingleJobSaveCommand();
        wrongDataSource.setOdsDatabaseBindingId(BINDING_ID);
        Map<String, Object> workflow = singleWorkflow();
        sinkConfig(workflow).put("dataSourceId", "100");
        wrongDataSource.setWorkflow(workflow);
        assertNull(detector.detect(wrongDataSource));

        LakeOdsDatabaseBindingDao bindingDao = Mockito.mock(LakeOdsDatabaseBindingDao.class);
        when(bindingDao.queryActiveById(BINDING_ID)).thenReturn(binding(LakeResourceStatus.CREATING));
        detector = new LakeJobDetector(bindingDao, tableMappingDao, lakeProperties());

        BatchGuideSingleJobSaveCommand notReady = new BatchGuideSingleJobSaveCommand();
        notReady.setOdsDatabaseBindingId(BINDING_ID);
        notReady.setWorkflow(singleWorkflow());
        assertNull(detector.detect(notReady));
    }

    @Test
    void snapshotsContainBothEndpointsAndSchemaMode() {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setOdsDatabaseBindingId(BINDING_ID);
        Map<String, Object> workflow = singleWorkflow();
        sinkConfig(workflow).put("schemaSaveMode", "ERROR_WHEN_SCHEMA_NOT_EXIST");
        command.setWorkflow(workflow);

        LakeJobDescriptor descriptor = detector.detect(command);
        assertTrue(descriptor.sourceEndpointSnapshot().contains("dataSourceId"));
        assertTrue(descriptor.sinkEndpointSnapshot().contains("targetTableName"));
        assertEquals("ERROR_WHEN_SCHEMA_NOT_EXIST", descriptor.schemaSaveModeSnapshot());
    }

    private void assertTableDescriptor(LakeJobDescriptor descriptor, LakeJobRuntimeType runtimeType) {
        assertEquals(BINDING_ID, descriptor.odsDatabaseBindingId());
        assertEquals(LAKE_DATA_SOURCE_ID, descriptor.lakeDataSourceId());
        assertEquals(LakeRelationScope.TABLE, descriptor.relationScope());
        assertEquals(70L, descriptor.tableMappingId());
        assertEquals(runtimeType, descriptor.jobRuntimeType());
    }

    private void assertNamespaceDescriptor(LakeJobDescriptor descriptor, String matchMode) {
        assertEquals(LakeRelationScope.NAMESPACE, descriptor.relationScope());
        assertNull(descriptor.tableMappingId());
        assertTrue(descriptor.sourceEndpointSnapshot().contains(matchMode));
        assertTrue(descriptor.sinkEndpointSnapshot().contains(matchMode));
    }

    private LakeOdsDatabaseBinding readyBinding() {
        return binding(LakeResourceStatus.READY);
    }

    private LakeOdsDatabaseBinding binding(LakeResourceStatus status) {
        LakeOdsDatabaseBinding binding = new LakeOdsDatabaseBinding();
        binding.setId(BINDING_ID);
        binding.setLakeDataSourceId(LAKE_DATA_SOURCE_ID);
        binding.setDatabaseName("ods_demo");
        binding.setResourceStatus(status);
        binding.setDeleted(false);
        return binding;
    }

    private LakeOdsTableMapping managedMapping() {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(70L);
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setDeleted(false);
        return mapping;
    }

    private LakeProperties lakeProperties() {
        LakeProperties properties = new LakeProperties();
        properties.setDataSourceId(LAKE_DATA_SOURCE_ID);
        return properties;
    }

    private Map<String, Object> singleWorkflow() {
        Map<String, Object> sourceConfig = new HashMap<>(Map.of(
                "dataSourceId", "10",
                "dbType", "MYSQL",
                "pluginName", "MYSQL",
                "table", "orders"));
        Map<String, Object> sinkConfig = new HashMap<>(Map.of(
                "dataSourceId", String.valueOf(LAKE_DATA_SOURCE_ID),
                "dbType", "DORIS",
                "pluginName", "DORIS",
                "targetTableName", "ods_orders",
                "schemaSaveMode", "ERROR_WHEN_SCHEMA_NOT_EXIST"));
        return new HashMap<>(Map.of(
                "nodes", List.of(
                        node("source", sourceConfig),
                        node("sink", sinkConfig)),
                "edges", List.of(Map.of("source", "source", "target", "sink"))));
    }

    private Map<String, Object> node(String type, Map<String, Object> config) {
        return new HashMap<>(Map.of(
                "id", type,
                "data", new HashMap<>(Map.of(
                        "nodeType", type,
                        "config", config))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sinkConfig(Map<String, Object> workflow) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) workflow.get("nodes");
        Map<String, Object> sink = nodes.get(1);
        Map<String, Object> data = (Map<String, Object>) sink.get("data");
        return (Map<String, Object>) data.get("config");
    }

    private GuideMultiJobContent multiContent(String mode) {
        GuideMultiJobContent content = new GuideMultiJobContent();
        GuideMultiJobContent.WorkflowSourceConfig source =
                new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("10");
        source.setDbType("MYSQL");
        source.setPluginName("MYSQL");
        source.setConnectorType("Jdbc");
        content.setSource(source);

        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId(String.valueOf(LAKE_DATA_SOURCE_ID));
        target.setDbType("DORIS");
        target.setPluginName("DORIS");
        target.setConnectorType("Doris");
        target.setSchemaSaveMode("ERROR_WHEN_SCHEMA_NOT_EXIST");
        target.setOdsDatabaseBindingId(BINDING_ID);
        content.setTarget(target);

        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode(mode);
        tableMatch.setTables(List.of("orders"));
        content.setTableMatch(tableMatch);
        return content;
    }
}
