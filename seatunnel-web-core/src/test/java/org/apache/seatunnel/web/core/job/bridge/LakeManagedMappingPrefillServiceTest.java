package org.apache.seatunnel.web.core.job.bridge;

import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class LakeManagedMappingPrefillServiceTest {

    @Test
    void exactSingleManagedMappingPrefillsFieldMapperWithoutChangingCase() {
        LakeOdsTableMappingDao dao = Mockito.mock(LakeOdsTableMappingDao.class);
        LakeManagedMappingPrefillService service = new LakeManagedMappingPrefillService();
        ReflectionTestUtils.setField(service, "tableMappingDao", dao);

        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setFieldMappingsJson("[{\"sourceField\":\"ORDER_NO\","
                + "\"targetField\":\"order_no\",\"targetType\":\"BIGINT\"}]");
        when(dao.queryByBindingIdAndTargetTable(eq(7L), eq("ods_orders"))).thenReturn(mapping);

        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setOdsDatabaseBindingId(7L);
        command.setWorkflow(workflowWithEmptyFieldMapper());

        service.prefill(command);

        Map<String, Object> transformConfig = transformConfig(command);
        List<?> mappings = (List<?>) transformConfig.get("mappings");
        assertEquals(1, mappings.size());
        Map<?, ?> first = (Map<?, ?>) mappings.get(0);
        assertEquals("ORDER_NO", first.get("sourceField"));
        assertEquals("order_no", first.get("targetField"));
        assertEquals("BIGINT", first.get("targetType"));
    }

    @Test
    void nonManagedMappingDoesNotOverwriteAnExistingUserMapping() {
        LakeOdsTableMappingDao dao = Mockito.mock(LakeOdsTableMappingDao.class);
        LakeManagedMappingPrefillService service = new LakeManagedMappingPrefillService();
        ReflectionTestUtils.setField(service, "tableMappingDao", dao);

        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setManagementLevel(LakeManagementLevel.UNMANAGED);
        mapping.setFieldMappingsJson("[{\"sourceField\":\"SERVER_VALUE\"}]");
        when(dao.queryByBindingIdAndTargetTable(eq(7L), eq("ods_orders"))).thenReturn(mapping);

        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setOdsDatabaseBindingId(7L);
        Map<String, Object> workflow = workflowWithEmptyFieldMapper();
        Map<String, Object> config = transformConfig(command, workflow);
        List<Map<String, Object>> userMappings = new ArrayList<>();
        userMappings.add(new HashMap<>(Map.of(
                "sourceField", "USER_FIELD", "targetField", "user_field")));
        config.put("mappings", userMappings);
        command.setWorkflow(workflow);

        service.prefill(command);

        assertEquals(userMappings, config.get("mappings"));
    }

    private Map<String, Object> workflowWithEmptyFieldMapper() {
        Map<String, Object> workflow = new HashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();

        Map<String, Object> sourceData = new HashMap<>();
        sourceData.put("nodeType", "source");
        nodes.add(new HashMap<>(Map.of("data", sourceData)));

        Map<String, Object> transformData = new HashMap<>();
        transformData.put("nodeType", "transform");
        transformData.put("componentType", "FIELDMAPPER");
        Map<String, Object> transformConfig = new HashMap<>();
        transformConfig.put("mappings", new ArrayList<>());
        transformData.put("config", transformConfig);
        nodes.add(new HashMap<>(Map.of("data", transformData)));

        Map<String, Object> sinkData = new HashMap<>();
        sinkData.put("nodeType", "sink");
        sinkData.put("config", new HashMap<>(Map.of(
                "odsDatabaseBindingId", 7L,
                "targetTableName", "ods_orders")));
        nodes.add(new HashMap<>(Map.of("data", sinkData)));

        workflow.put("nodes", nodes);
        return workflow;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> transformConfig(BatchGuideSingleJobSaveCommand command) {
        return transformConfig(command, command.getWorkflow());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> transformConfig(
            BatchGuideSingleJobSaveCommand command, Map<String, Object> workflow) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) workflow.get("nodes");
        Map<String, Object> data = (Map<String, Object>) nodes.get(1).get("data");
        return (Map<String, Object>) data.get("config");
    }
}
