package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPipelineConfig;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Upgrades existing OM agents through each installation's own Web bindings. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataExplorationPipelineUpgrade {

    private static final int BATCH_SIZE = 5;
    private static final Duration CLAIM_DURATION = Duration.ofMinutes(15);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(2);

    private final MetadataBindingDao bindingDao;
    private final OpenMetadataConfigResolver configResolver;
    private final OpenMetadataClient openMetadataClient;

    @Scheduled(fixedDelayString = "${metadata.reconcile.interval-ms:20000}")
    public void upgradePendingBindings() {
        if (!configResolver.isEnabled()) {
            return;
        }
        List<MetadataSourceBinding> bindings = bindingDao.queryExplorationPipelineUpgrades(
                MetadataExplorationPipelinePolicy.VERSION, new Date(), BATCH_SIZE);
        if (bindings.isEmpty()) {
            return;
        }
        try {
            openMetadataClient.assertFixedVersion();
        } catch (MetadataIntegrationException error) {
            log.warn("OpenMetadata exploration pipeline upgrade deferred: errorCode={}", error.getErrorCode());
            return;
        }
        bindings.forEach(this::upgradeBinding);
    }

    private void upgradeBinding(MetadataSourceBinding binding) {
        Date now = new Date();
        Date claimUntil = Date.from(now.toInstant().plus(CLAIM_DURATION));
        if (!bindingDao.claimExplorationPipelineUpgrade(binding.getId(), binding.getVersion(),
                MetadataExplorationPipelinePolicy.VERSION, now, claimUntil)) {
            return;
        }
        Integer appliedVersion = null;
        try {
            if (upgradePipelines(binding, claimUntil)) {
                appliedVersion = MetadataExplorationPipelinePolicy.VERSION;
            }
        } catch (Exception error) {
            Object code = error instanceof MetadataIntegrationException integrationError
                    ? integrationError.getErrorCode() : error.getClass().getSimpleName();
            log.warn("OpenMetadata exploration pipeline upgrade will retry: dataSourceId={}, error={}",
                    binding.getDataSourceId(), code);
        }
        Date retryAt = appliedVersion == null ? Date.from(Instant.now().plus(RETRY_DELAY)) : null;
        if (bindingDao.finishExplorationPipelineUpgrade(
                binding.getId(), binding.getConfigVersion(), claimUntil, retryAt, appliedVersion)
                && appliedVersion != null) {
            log.info("OpenMetadata exploration pipeline upgrade complete: dataSourceId={}, policyVersion={}",
                    binding.getDataSourceId(), appliedVersion);
        }
    }

    private boolean upgradePipelines(MetadataSourceBinding binding, Date claimUntil) {
        if (MetadataPipelineOperationService.isRunning(binding.getProfileStatus())
                || MetadataPipelineOperationService.isRunning(binding.getSampleStatus())) {
            return false;
        }
        String serviceFqn = binding.getOmServiceFqn();
        if (serviceFqn == null || serviceFqn.isBlank() || binding.getOmServiceId() == null) {
            throw failure("Ready OpenMetadata binding has no service identity");
        }
        long dataSourceId = binding.getDataSourceId();
        String profilerFqn = binding.getOmProfilerPipelineFqn();
        if (profilerFqn == null || profilerFqn.isBlank()) {
            profilerFqn = serviceFqn + "." + MetadataStableName.profilerPipelineName(dataSourceId);
        }

        List<PipelineUpgrade> upgrades = new ArrayList<>();
        findUpgrade(binding, profilerFqn, "profiler", MetadataExplorationPipelinePolicy.profilerSchedule(serviceFqn))
                .ifPresent(upgrades::add);
        String sampleSchedule = MetadataExplorationPipelinePolicy.sampleSchedule(serviceFqn);
        findUpgrade(binding, serviceFqn + "." + MetadataStableName.autoClassificationPipelineName(dataSourceId),
                "autoClassification", sampleSchedule).ifPresent(upgrades::add);
        findUpgrade(binding, serviceFqn + "." + MetadataStableName.legacyAutoClassificationPipelineName(dataSourceId),
                "autoClassification", sampleSchedule).ifPresent(upgrades::add);

        for (PipelineUpgrade upgrade : upgrades) {
            if (!ownsClaim(binding, claimUntil) || isRunning(upgrade.fqn())) {
                return false;
            }
            ArrayNode patch = upgrade.patch();
            if (!patch.isEmpty()) {
                openMetadataClient.patchIngestionPipeline(upgrade.pipeline().id(), patch);
            }
            if (upgrade.pipeline().deployed()) {
                if (!ownsClaim(binding, claimUntil)) {
                    return false;
                }
                // A prior attempt can save the entity and fail before deploy. Deploy the same
                // agent again until this binding's policy version has been recorded.
                openMetadataClient.deployIngestionPipeline(upgrade.pipeline().id());
            }
            OpenMetadataPipelineConfig actual = openMetadataClient.findIngestionPipelineConfig(upgrade.fqn())
                    .orElseThrow(() -> failure("OpenMetadata pipeline disappeared during the upgrade"));
            if (!upgrade.matches(actual)) {
                throw failure("OpenMetadata pipeline did not retain its upgraded configuration");
            }
            log.info("Upgraded OpenMetadata exploration pipeline: dataSourceId={}, pipelineFqn={}, schedule={}",
                    binding.getDataSourceId(), upgrade.fqn(), actual.schedule());
        }
        return true;
    }

    private Optional<PipelineUpgrade> findUpgrade(
            MetadataSourceBinding binding, String fqn, String expectedType, String targetSchedule) {
        return openMetadataClient.findIngestionPipelineConfig(fqn).flatMap(pipeline -> {
            if (!binding.getOmServiceId().equals(pipeline.serviceId()) || !expectedType.equals(pipeline.type())) {
                throw failure("OpenMetadata pipeline no longer belongs to its expected service or agent type");
            }
            boolean updateSchedule = MetadataExplorationPipelinePolicy.isLegacySchedule(expectedType, pipeline.schedule());
            boolean managedSchedule = updateSchedule || targetSchedule.equals(pipeline.schedule());
            boolean fullSample = "profiler".equals(expectedType) && usingFullSample(pipeline.sourceConfig());
            boolean managedSample = fullSample || ("profiler".equals(expectedType) && usingRowSample(pipeline.sourceConfig()));
            if (!managedSchedule && !managedSample) {
                return Optional.empty();
            }
            return Optional.of(new PipelineUpgrade(
                    fqn, pipeline, managedSchedule ? targetSchedule : null, fullSample, managedSample));
        });
    }

    private boolean ownsClaim(MetadataSourceBinding binding, Date claimUntil) {
        MetadataSourceBinding latest = bindingDao.queryById(binding.getId());
        return latest != null && latest.getDesiredState() == MetadataDesiredState.ACTIVE
                && latest.getSyncStatus() == MetadataSyncStatus.READY
                && Objects.equals(latest.getConfigVersion(), binding.getConfigVersion())
                && claimUntil.equals(latest.getExplorationPipelineRetryAt())
                && claimUntil.after(new Date());
    }

    private boolean isRunning(String fqn) {
        return openMetadataClient.listIngestionPipelineRuns(fqn, 1).stream()
                .map(run -> OpenMetadataRunStatusMapper.fromPipelineState(run.pipelineState()))
                .anyMatch(MetadataPipelineOperationService::isRunning);
    }

    private static boolean usingFullSample(JsonNode sourceConfig) {
        if (sourceConfig == null) {
            return false;
        }
        JsonNode sample = sourceConfig.at("/profileSampleConfig/config");
        return "PERCENTAGE".equals(sample.path("profileSampleType").asText())
                && sample.path("profileSample").asDouble() == 100.0;
    }

    private static boolean usingRowSample(JsonNode sourceConfig) {
        if (sourceConfig == null) {
            return false;
        }
        JsonNode sample = sourceConfig.at("/profileSampleConfig/config");
        return "ROWS".equals(sample.path("profileSampleType").asText())
                && sample.path("profileSample").asDouble() == MetadataExplorationPipelinePolicy.PROFILER_SAMPLE_ROWS
                && !sourceConfig.path("randomizedSample").asBoolean(true);
    }

    private static MetadataIntegrationException failure(String message) {
        return new MetadataIntegrationException(MetadataErrorCode.OM_SERVICE_SYNC_ERROR, message);
    }

    private record PipelineUpgrade(String fqn, OpenMetadataPipelineConfig pipeline, String schedule,
                                   boolean updateSample, boolean verifySample) {

        ArrayNode patch() {
            ArrayNode patch = JsonNodeFactory.instance.arrayNode();
            if (schedule != null && !schedule.equals(pipeline.schedule())) {
                patch.addObject().put("op", "replace").put("path", "/airflowConfig/scheduleInterval")
                        .put("value", schedule);
            }
            if (updateSample) {
                patch.addObject().put("op", "replace").put("path", "/sourceConfig/config/profileSampleConfig")
                        .set("value", MetadataExplorationPipelinePolicy.profilerRowSample());
                patch.addObject().put("op", "add").put("path", "/sourceConfig/config/randomizedSample")
                        .put("value", false);
            }
            return patch;
        }

        boolean matches(OpenMetadataPipelineConfig actual) {
            return pipeline.id().equals(actual.id()) && (!pipeline.deployed() || actual.deployed())
                    && (schedule == null || schedule.equals(actual.schedule()))
                    && (!verifySample || usingRowSample(actual.sourceConfig()));
        }
    }
}
