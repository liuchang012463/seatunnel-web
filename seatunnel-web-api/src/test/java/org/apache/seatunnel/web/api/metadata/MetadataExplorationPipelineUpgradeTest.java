package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPipelineConfig;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPipelineRun;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataExplorationPipelineUpgradeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SERVICE_FQN = "st_ds_8472";
    private static final String SERVICE_ID = "inner-environment-service";
    private static final String PROFILER_FQN = SERVICE_FQN + ".st_ds_8472_profiler";
    private static final String SAMPLE_FQN = SERVICE_FQN + ".st_ds_8472_auto_classification";

    private final MetadataBindingDao bindingDao = mock(MetadataBindingDao.class);
    private final OpenMetadataConfigResolver config = mock(OpenMetadataConfigResolver.class);
    private final OpenMetadataClient om = mock(OpenMetadataClient.class);
    private final MetadataSourceBinding binding = new MetadataSourceBinding();
    private final MetadataExplorationPipelineUpgrade upgrade =
            new MetadataExplorationPipelineUpgrade(bindingDao, config, om);

    @BeforeEach
    void readyBindingInAnotherEnvironment() {
        binding.setId(112L);
        binding.setDataSourceId(8472L);
        binding.setVersion(7L);
        binding.setConfigVersion(2L);
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setOmServiceId(SERVICE_ID);
        binding.setOmServiceFqn(SERVICE_FQN);
        binding.setOmProfilerPipelineFqn(PROFILER_FQN);
        when(config.isEnabled()).thenReturn(true);
        when(bindingDao.queryExplorationPipelineUpgrades(eq(1), any(Date.class), eq(5)))
                .thenReturn(List.of(binding));
        when(bindingDao.claimExplorationPipelineUpgrade(eq(112L), eq(7L), eq(1), any(), any()))
                .thenAnswer(invocation -> {
                    binding.setExplorationPipelineRetryAt(invocation.getArgument(4));
                    return true;
                });
        when(bindingDao.queryById(112L)).thenReturn(binding);
    }

    @Test
    void patchesOnlySamplingAndScheduleOnExistingLocalPipelines() throws Exception {
        JsonNode oldSource = JSON.readTree("""
                {"profileSampleConfig":{"sampleConfigType":"STATIC","config":{
                  "profileSample":100.0,"profileSampleType":"PERCENTAGE"}},"randomizedSample":true,
                 "schemaFilterPattern":{"includes":["inner_sales"]}}
                """);
        String profileSchedule = MetadataExplorationPipelinePolicy.profilerSchedule(SERVICE_FQN);
        String sampleSchedule = MetadataExplorationPipelinePolicy.sampleSchedule(SERVICE_FQN);
        when(om.findIngestionPipelineConfig(PROFILER_FQN)).thenReturn(
                Optional.of(profile("0 1 * * *", oldSource)), Optional.of(profile(profileSchedule, rowSample())));
        when(om.findIngestionPipelineConfig(SAMPLE_FQN)).thenReturn(
                Optional.of(sample("0 0 * * *")), Optional.of(sample(sampleSchedule)));

        upgrade.upgradePendingBindings();

        ArgumentCaptor<JsonNode> patch = ArgumentCaptor.forClass(JsonNode.class);
        verify(om).patchIngestionPipeline(eq("existing-profile"), patch.capture());
        assertEquals(3, patch.getValue().size());
        assertEquals("/airflowConfig/scheduleInterval", patch.getValue().get(0).path("path").asText());
        assertEquals(profileSchedule, patch.getValue().get(0).path("value").asText());
        assertEquals("/sourceConfig/config/profileSampleConfig", patch.getValue().get(1).path("path").asText());
        assertEquals(50, patch.getValue().get(1).at("/value/config/profileSample").asInt());
        assertEquals("ROWS", patch.getValue().get(1).at("/value/config/profileSampleType").asText());
        assertEquals("/sourceConfig/config/randomizedSample", patch.getValue().get(2).path("path").asText());
        verify(om).patchIngestionPipeline(eq("existing-sample"), patch.capture());
        assertEquals(1, patch.getValue().size());
        assertEquals(sampleSchedule, patch.getValue().get(0).path("value").asText());
        verify(om).deployIngestionPipeline("existing-profile");
        verify(om).deployIngestionPipeline("existing-sample");
        verify(om, never()).triggerIngestionPipeline(any());
        verify(bindingDao).finishExplorationPipelineUpgrade(eq(112L), eq(2L), any(), isNull(), eq(1));
    }

    @Test
    void retriesTheDeployWhenTheConfigurationWasAlreadySaved() throws Exception {
        var ready = profile(MetadataExplorationPipelinePolicy.profilerSchedule(SERVICE_FQN), rowSample());
        when(om.findIngestionPipelineConfig(PROFILER_FQN)).thenReturn(Optional.of(ready));
        doThrow(new MetadataIntegrationException(MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR, "temporary"))
                .doNothing().when(om).deployIngestionPipeline("existing-profile");

        upgrade.upgradePendingBindings();
        verify(bindingDao).finishExplorationPipelineUpgrade(eq(112L), eq(2L), any(), any(Date.class), isNull());
        upgrade.upgradePendingBindings();
        verify(bindingDao).finishExplorationPipelineUpgrade(eq(112L), eq(2L), any(), isNull(), eq(1));
        verify(om, never()).patchIngestionPipeline(any(), any());
    }

    @Test
    void defersInFlightAgentsAndKeepsCustomSchedules() throws Exception {
        when(om.findIngestionPipelineConfig(PROFILER_FQN)).thenReturn(Optional.of(profile("0 1 * * *", rowSample())));
        when(om.findIngestionPipelineConfig(SAMPLE_FQN)).thenReturn(Optional.of(sample("13 3 * * *")));
        when(om.listIngestionPipelineRuns(PROFILER_FQN, 1)).thenReturn(List.of(
                new OpenMetadataPipelineRun("run", "running", null, null, null, null)));

        upgrade.upgradePendingBindings();

        verify(om, never()).patchIngestionPipeline(any(), any());
        verify(om, never()).deployIngestionPipeline(any());
        verify(bindingDao).finishExplorationPipelineUpgrade(eq(112L), eq(2L), any(), any(Date.class), isNull());
    }

    private static JsonNode rowSample() throws Exception {
        return JSON.readTree("""
                {"profileSampleConfig":{"sampleConfigType":"STATIC","config":{
                  "profileSample":50.0,"profileSampleType":"ROWS"}},"randomizedSample":false}
                """);
    }

    private static OpenMetadataPipelineConfig profile(String schedule, JsonNode config) {
        return new OpenMetadataPipelineConfig("existing-profile", SERVICE_ID, "profiler", schedule, config, true);
    }

    private static OpenMetadataPipelineConfig sample(String schedule) {
        return new OpenMetadataPipelineConfig("existing-sample", SERVICE_ID, "autoClassification", schedule, null, true);
    }
}
