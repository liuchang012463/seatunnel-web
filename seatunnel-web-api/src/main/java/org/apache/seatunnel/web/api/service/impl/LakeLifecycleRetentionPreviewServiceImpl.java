package org.apache.seatunnel.web.api.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.contract.TargetContractCanonicalizer;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummary;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleBindingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleConfirmationTokenService;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleMappingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleRetentionPreviewVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleRetentionPreviewService;
import org.apache.seatunnel.web.api.service.LakeLifecycleValidationService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionPreviewDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleValidateDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-through retention impact preview.  It never mutates a binding or a
 * Doris table; a decrease receives a signed token tied to the complete
 * observed identity and impact set.
 */
@Service
public class LakeLifecycleRetentionPreviewServiceImpl
        implements LakeLifecycleRetentionPreviewService {

    public static final String BINDING_NOT_ACTIVE = "LAKE_LIFECYCLE_BINDING_NOT_ACTIVE";
    public static final String BINDING_OPERATION_IN_PROGRESS =
            "LAKE_LIFECYCLE_BINDING_OPERATION_IN_PROGRESS";
    public static final String IMPACT_OBSERVATION_UNKNOWN =
            "LAKE_LIFECYCLE_RETENTION_IMPACT_UNKNOWN";

    private final LakeLifecycleValidationService validationService;
    private final LakeTableLifecycleBindingDao lifecycleBindingDao;
    private final CurrentUserProvider currentUserProvider;
    private final LakeLifecycleConfirmationTokenService tokenService;

    @Autowired
    public LakeLifecycleRetentionPreviewServiceImpl(
            LakeLifecycleValidationService validationService,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            CurrentUserProvider currentUserProvider,
            LakeLifecycleConfirmationTokenService tokenService) {
        this.validationService = Objects.requireNonNull(validationService, "validationService");
        this.lifecycleBindingDao = Objects.requireNonNull(lifecycleBindingDao, "lifecycleBindingDao");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
    }

    /** Visible for unit tests that provide an already materialized binding snapshot. */
    public LakeLifecycleRetentionPreviewServiceImpl(
            LakeLifecycleValidationService validationService,
            CurrentUserProvider currentUserProvider,
            LakeLifecycleConfirmationTokenService tokenService) {
        this.validationService = Objects.requireNonNull(validationService, "validationService");
        this.lifecycleBindingDao = null;
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
    }

    @Override
    public LakeLifecycleRetentionPreviewVO preview(
            Long mappingId, LakeLifecycleRetentionPreviewDTO request) {
        validateRequest(mappingId, request);
        Integer userId = requireCurrentUserId();

        LakeLifecycleValidateDTO validationRequest = new LakeLifecycleValidateDTO();
        validationRequest.setMappingId(mappingId);
        validationRequest.setPolicyId(request.getPolicyId());
        var validated = validationService.validate(validationRequest);
        LakeLifecycleRetentionPreviewVO result = toPreview(validated, mappingId, request.getPolicyId());
        if (!validated.isValid()) {
            return result;
        }

        LakeLifecycleBindingSnapshotVO snapshot = validated.getExistingBinding();
        LakeTableLifecycleBinding currentBinding = readBinding(mappingId);
        if (currentBinding != null) {
            snapshot = toSnapshot(currentBinding);
            result.setExistingBinding(snapshot);
        }
        if (snapshot == null) {
            // A new binding has no prior desired retention and therefore no
            // decrease impact to confirm.  The validation result is still
            // useful to the caller before the first apply.
            return result;
        }
        if (!bindingCanBeUpdated(snapshot, currentBinding)) {
            return invalidResult(result, currentBinding != null
                    && StringUtils.isNotBlank(currentBinding.getOperationToken())
                    ? BINDING_OPERATION_IN_PROGRESS : BINDING_NOT_ACTIVE);
        }

        Integer currentDesired = snapshot.getRetentionCount();
        Integer requestedRetention = validated.getDesiredRetentionCount();
        result.setCurrentDesiredRetentionCount(currentDesired);
        result.setCurrentActualRetentionCount(snapshot.getActualRetentionCount());
        if (currentDesired == null || currentDesired <= 0 || requestedRetention == null
                || requestedRetention >= currentDesired) {
            return result;
        }

        DorisPartitionSummary summary = validated.getPartitionSummary();
        result.setHistoricalPartitionCount(summary == null ? null : summary.historical());
        if (!hasExactHistoricalObservation(summary)) {
            return invalidResult(result, IMPACT_OBSERVATION_UNKNOWN);
        }

        List<String> impacted = impactedHistoricalPartitionNames(summary, requestedRetention);
        result.setImpactedHistoricalPartitionNames(impacted);
        result.setImpactedHistoricalPartitionCount(impacted.size());
        result.setRequiresConfirmation(true);

        LakeLifecycleMappingSnapshotVO mapping = validated.getMappingSnapshot();
        if (!hasTokenIdentity(mapping, snapshot)) {
            return invalidResult(result, IMPACT_OBSERVATION_UNKNOWN);
        }
        String impactHash = observedImpactHash(mapping, snapshot, validated, requestedRetention, impacted);
        result.setConfirmationToken(tokenService.issue(
                userId,
                mapping.getId(), mapping.getGeneration(), mapping.getLockVersion(),
                snapshot.getId(), snapshot.getGeneration(), snapshot.getLockVersion(),
                currentDesired, validated.getPolicyId(),
                validated.getPolicySnapshot() == null ? null
                        : validated.getPolicySnapshot().getVersion(),
                requestedRetention, impactHash));
        return result;
    }

    private LakeTableLifecycleBinding readBinding(Long mappingId) {
        if (lifecycleBindingDao == null) {
            return null;
        }
        try {
            return lifecycleBindingDao.queryByTableMappingId(mappingId);
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle binding cannot be read");
        }
    }

    private static LakeLifecycleRetentionPreviewVO toPreview(
            org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO validated,
            Long mappingId,
            Long policyId) {
        LakeLifecycleRetentionPreviewVO result = new LakeLifecycleRetentionPreviewVO();
        result.setValid(validated.isValid());
        result.setCode(validated.getCode());
        result.setReasons(validated.getReasons() == null
                ? List.of() : List.copyOf(validated.getReasons()));
        result.setMappingId(mappingId);
        result.setPolicyId(policyId);
        result.setMappingSnapshot(validated.getMappingSnapshot());
        result.setRequestedPolicySnapshot(validated.getPolicySnapshot());
        result.setExistingBinding(validated.getExistingBinding());
        result.setRequestedRetentionCount(validated.getDesiredRetentionCount());
        result.setCurrentActualRetentionCount(validated.getExistingBinding() == null
                ? null : validated.getExistingBinding().getActualRetentionCount());
        result.setPartitionSummary(validated.getPartitionSummary());
        result.setObservedAt(validated.getObservedAt());
        result.setHistoricalPartitionCount(validated.getPartitionSummary() == null
                ? null : validated.getPartitionSummary().historical());
        result.setImpactedHistoricalPartitionCount(0);
        return result;
    }

    private static LakeLifecycleBindingSnapshotVO toSnapshot(LakeTableLifecycleBinding binding) {
        LakeLifecycleBindingSnapshotVO result = new LakeLifecycleBindingSnapshotVO();
        result.setId(binding.getId());
        result.setTableMappingId(binding.getTableMappingId());
        result.setPolicyId(binding.getPolicyId());
        result.setPolicyVersion(binding.getPolicyVersion());
        result.setPartitionColumn(binding.getPartitionColumn());
        result.setGranularity(binding.getGranularity());
        result.setRetentionCount(binding.getRetentionCount());
        result.setActualRetentionCount(binding.getActualRetentionCount());
        result.setActualPartitionSummaryJson(binding.getActualPartitionSummaryJson());
        result.setLastObservedAt(binding.getLastObservedAt());
        result.setPolicySnapshotJson(binding.getPolicySnapshotJson());
        result.setStatus(binding.getStatus());
        result.setGeneration(binding.getGeneration());
        result.setLockVersion(binding.getLockVersion());
        result.setErrorCode(binding.getErrorCode());
        result.setCreateTime(binding.getCreateTime());
        result.setUpdateTime(binding.getUpdateTime());
        return result;
    }

    private static boolean bindingCanBeUpdated(
            LakeLifecycleBindingSnapshotVO snapshot, LakeTableLifecycleBinding current) {
        if (snapshot.getStatus() != LakeLifecycleBindingStatus.ACTIVE
                && snapshot.getStatus() != LakeLifecycleBindingStatus.ERROR) {
            return false;
        }
        return current == null || StringUtils.isBlank(current.getOperationToken());
    }

    static boolean hasExactHistoricalObservation(DorisPartitionSummary summary) {
        if (summary == null || summary.unknown() != 0
                || summary.historical() != summary.historicalNames().size()) {
            return false;
        }
        Set<String> unique = new HashSet<>(summary.historicalNames());
        return unique.size() == summary.historicalNames().size();
    }

    private static boolean hasTokenIdentity(
            LakeLifecycleMappingSnapshotVO mapping,
            LakeLifecycleBindingSnapshotVO binding) {
        return mapping != null && positive(mapping.getId()) && positive(mapping.getGeneration())
                && positive(mapping.getLockVersion()) && positive(binding.getId())
                && positive(binding.getGeneration()) && positive(binding.getLockVersion())
                && positive(binding.getRetentionCount()) && positive(binding.getPolicyId())
                && positive(binding.getPolicyVersion());
    }

    /**
     * Returns the exact oldest historical partitions that would be affected by
     * retaining {@code requestedRetention} partitions.  The summarizer orders
     * historical partitions by parsed upper bound and then name, so this does
     * not infer age from a partition name.
     */
    static List<String> impactedHistoricalPartitionNames(
            DorisPartitionSummary summary, Integer requestedRetention) {
        if (summary == null || requestedRetention == null || requestedRetention <= 0) {
            return List.of();
        }
        List<String> historical = summary.historicalNames();
        int impactedCount = Math.max(0, historical.size() - requestedRetention);
        return List.copyOf(historical.subList(0, impactedCount));
    }

    /**
     * Computes the signed observation identity used by preview and retention
     * update.  Every scalar and list item is length-prefixed; using collection
     * {@code toString()} here would make punctuation/newlines ambiguous and
     * would allow two different observations to share a token payload.
     *
     * <p>Package visibility is intentional: the update coordinator must call
     * this same helper after its fresh read-through validation before consuming
     * a confirmation token.</p>
     */
    static String observedImpactHash(
            LakeLifecycleMappingSnapshotVO mapping,
            LakeLifecycleBindingSnapshotVO binding,
            org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO validated,
            Integer requestedRetention,
            List<String> impacted) {
        DorisPartitionSummary summary = validated.getPartitionSummary();
        StringBuilder canonical = new StringBuilder(512);
        appendValue(canonical, mapping == null ? null : mapping.getId());
        appendValue(canonical, mapping == null ? null : mapping.getGeneration());
        appendValue(canonical, mapping == null ? null : mapping.getLockVersion());
        appendValue(canonical, binding == null ? null : binding.getId());
        appendValue(canonical, binding == null ? null : binding.getGeneration());
        appendValue(canonical, binding == null ? null : binding.getLockVersion());
        appendValue(canonical, binding == null ? null : binding.getRetentionCount());
        appendValue(canonical, validated == null ? null : validated.getPolicyId());
        appendValue(canonical, validated == null || validated.getPolicySnapshot() == null
                ? null : validated.getPolicySnapshot().getVersion());
        appendValue(canonical, validated == null || validated.getPolicySnapshot() == null
                ? null : validated.getPolicySnapshot().getGranularity());
        appendValue(canonical, requestedRetention);
        appendValue(canonical, summary == null || summary.observedAt() == null
                ? null : summary.observedAt().toString());
        appendValue(canonical, summary == null ? null : summary.total());
        appendValue(canonical, summary == null ? null : summary.historical());
        appendValue(canonical, summary == null ? null : summary.current());
        appendValue(canonical, summary == null ? null : summary.future());
        appendValue(canonical, summary == null ? null : summary.unknown());
        appendValues(canonical, summary == null ? null : summary.partitionNames());
        appendValues(canonical, summary == null ? null : summary.historicalNames());
        appendValues(canonical, summary == null ? null : summary.currentNames());
        appendValues(canonical, summary == null ? null : summary.futureNames());
        appendValues(canonical, summary == null ? null : summary.unknownNames());
        appendValues(canonical, impacted);
        return TargetContractCanonicalizer.sha256(canonical.toString());
    }

    private static void appendValue(StringBuilder output, Object value) {
        if (value == null) {
            output.append("-1:");
            return;
        }
        String text = String.valueOf(value);
        output.append(text.length()).append(':').append(text);
    }

    private static void appendValues(StringBuilder output, List<String> values) {
        if (values == null) {
            appendValue(output, null);
            return;
        }
        appendValue(output, values.size());
        for (String value : values) {
            appendValue(output, value);
        }
    }

    private static LakeLifecycleRetentionPreviewVO invalidResult(
            LakeLifecycleRetentionPreviewVO result, String code) {
        List<String> reasons = new ArrayList<>(result.getReasons() == null
                ? List.of() : result.getReasons());
        if (!reasons.contains(code)) {
            reasons.add(code);
        }
        result.setValid(false);
        result.setCode(code);
        result.setReasons(List.copyOf(reasons));
        result.setRequiresConfirmation(false);
        result.setConfirmationToken(null);
        return result;
    }

    private static void validateRequest(
            Long mappingId, LakeLifecycleRetentionPreviewDTO request) {
        if (!positive(mappingId) || request == null || !positive(request.getPolicyId())) {
            throw invalid("mappingId and policyId are required");
        }
    }

    private Integer requireCurrentUserId() {
        try {
            Integer userId = currentUserProvider.getCurrentUserId();
            if (!positive(userId)) {
                throw invalid("Authenticated user is required");
            }
            return userId;
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("Authenticated user is required");
        }
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static LakeServiceException invalid(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID, message);
    }

    private static LakeServiceException conflict(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT, message);
    }
}
