package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapability;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapabilityReason;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpec;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpecCanonicalizer;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpecValidator;
import org.apache.seatunnel.web.api.lake.catalog.LakeExternalCatalogCapabilityResolver;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogOperationResult;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogValidationResult;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCredentialRevisionService;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcCatalogDdlBuilder;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcDriverRegistry;
import org.apache.seatunnel.web.api.lake.catalog.LakeLogicalCapabilityVO;
import org.apache.seatunnel.web.api.lake.catalog.LakeSourceNetworkProbeCache;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.operation.LakeExternalOperationException;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationException;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationExecution;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationHandle;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationIntent;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationCoordinator;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.LakeLogicalCatalogService;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-only logical catalog facade.
 *
 * <p>The capability endpoint performs a bounded Doris ping and can consume a
 * short-lived source reachability observation produced by the explicit
 * FE/BE probe endpoint.  It never probes the source from the Web process.</p>
 */
@Service
public class LakeLogicalCatalogServiceImpl implements LakeLogicalCatalogService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT);

    private final DataSourceDao dataSourceDao;
    private final LakeProperties lakeProperties;
    private final LakeExternalCatalogCapabilityResolver capabilityResolver;
    private final LakeDorisClientProvider dorisClientProvider;
    private final LakeExternalCatalogBindingPersistenceService persistenceService;
    private final LakeJdbcDriverRegistry driverRegistry;
    private final LakeCatalogCredentialRevisionService credentialService;
    private final LakeResourceOperationCoordinator coordinator;
    private final CurrentUserProvider currentUserProvider;
    private final LakeSourceNetworkProbeCache sourceProbeCache;
    private final LakeWarehouseService warehouseService;

    @Autowired
    public LakeLogicalCatalogServiceImpl(
            DataSourceDao dataSourceDao,
            LakeProperties lakeProperties,
            LakeExternalCatalogCapabilityResolver capabilityResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeExternalCatalogBindingPersistenceService persistenceService,
            LakeJdbcDriverRegistry driverRegistry,
            LakeCatalogCredentialRevisionService credentialService,
            LakeResourceOperationCoordinator coordinator,
            CurrentUserProvider currentUserProvider,
            LakeSourceNetworkProbeCache sourceProbeCache,
            LakeWarehouseService warehouseService) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.lakeProperties = Objects.requireNonNull(lakeProperties, "lakeProperties");
        this.capabilityResolver = Objects.requireNonNull(capabilityResolver, "capabilityResolver");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
        this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
        this.sourceProbeCache = Objects.requireNonNull(sourceProbeCache, "sourceProbeCache");
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService");
    }

    /** Compatibility constructor for embedders that supply the legacy source id. */
    public LakeLogicalCatalogServiceImpl(
            DataSourceDao dataSourceDao,
            LakeProperties lakeProperties,
            LakeExternalCatalogCapabilityResolver capabilityResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeExternalCatalogBindingPersistenceService persistenceService,
            LakeJdbcDriverRegistry driverRegistry,
            LakeCatalogCredentialRevisionService credentialService,
            LakeResourceOperationCoordinator coordinator,
            CurrentUserProvider currentUserProvider,
            LakeSourceNetworkProbeCache sourceProbeCache) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.lakeProperties = Objects.requireNonNull(lakeProperties, "lakeProperties");
        this.capabilityResolver = Objects.requireNonNull(capabilityResolver, "capabilityResolver");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
        this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.coordinator = coordinator;
        this.currentUserProvider = currentUserProvider;
        this.sourceProbeCache = Objects.requireNonNull(sourceProbeCache, "sourceProbeCache");
        this.warehouseService = null;
    }

    /** Constructor retained for embedders that do not need a custom probe cache. */
    public LakeLogicalCatalogServiceImpl(
            DataSourceDao dataSourceDao,
            LakeProperties lakeProperties,
            LakeExternalCatalogCapabilityResolver capabilityResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeExternalCatalogBindingPersistenceService persistenceService,
            LakeJdbcDriverRegistry driverRegistry,
            LakeCatalogCredentialRevisionService credentialService,
            LakeResourceOperationCoordinator coordinator,
            CurrentUserProvider currentUserProvider) {
        this(dataSourceDao, lakeProperties, capabilityResolver, dorisClientProvider,
                persistenceService, driverRegistry, credentialService, coordinator,
                currentUserProvider, new LakeSourceNetworkProbeCache());
    }

    /** Constructor retained for focused read-only tests and embedders. */
    public LakeLogicalCatalogServiceImpl(
            DataSourceDao dataSourceDao,
            LakeProperties lakeProperties,
            LakeExternalCatalogCapabilityResolver capabilityResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeExternalCatalogBindingPersistenceService persistenceService) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.lakeProperties = Objects.requireNonNull(lakeProperties, "lakeProperties");
        this.capabilityResolver = Objects.requireNonNull(capabilityResolver, "capabilityResolver");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
        this.driverRegistry = new LakeJdbcDriverRegistry();
        this.credentialService = new LakeCatalogCredentialRevisionService(
                lakeProperties, ignored -> null);
        this.coordinator = null;
        this.currentUserProvider = null;
        this.sourceProbeCache = new LakeSourceNetworkProbeCache();
        this.warehouseService = null;
    }

    @Override
    public LakeLogicalCapabilityVO capability(Long sourceDataSourceId) {
        LakeJdbcAdapterType adapter = adapterFor(sourceDataSourceId);
        return capability(sourceDataSourceId, adapter, LakeCatalogScope.ALL);
    }

    @Override
    public LakeLogicalCapabilityVO capability(
            Long sourceDataSourceId, LakeJdbcAdapterType adapter, LakeCatalogScope scope) {
        LakeCatalogScope requestedScope = scope == null ? LakeCatalogScope.ALL : scope;
        boolean lakeDorisReachable = probeLakeDoris();
        LakeSourceNetworkProbeCache.ProbeResult probe = sourceProbeCache.get(
                probeKey(sourceDataSourceId, adapter), lakeProperties.getSourceProbeCacheTtl())
                .orElse(null);
        return capabilityWithProbe(sourceDataSourceId, adapter, requestedScope,
                lakeDorisReachable, probe);
    }

    @Override
    public LakeLogicalCapabilityVO probe(
            Long sourceDataSourceId, LakeJdbcAdapterType adapter, LakeCatalogScope scope) {
        LakeCatalogScope requestedScope = scope == null ? LakeCatalogScope.ALL : scope;
        LakeJdbcAdapterType requestedAdapter = adapter == null
                ? adapterFor(sourceDataSourceId) : adapter;
        boolean lakeDorisReachable = probeLakeDoris();
        if (!lakeDorisReachable || requestedAdapter == null
                || sourceDataSourceId == null || sourceDataSourceId <= 0) {
            return capabilityWithProbe(sourceDataSourceId, requestedAdapter, requestedScope,
                    lakeDorisReachable, null);
        }

        // Resolve static checks first.  A probe cannot repair a missing driver,
        // adapter or source configuration, and avoiding a temporary catalog in
        // those cases prevents needless side effects.
        LakeCatalogCapability staticCapability;
        try {
            staticCapability = capabilityResolver.resolve(
                    sourceDataSourceId, requestedAdapter, requestedScope,
                    lakeDorisReachable, true);
        } catch (RuntimeException exception) {
            return capabilityWithProbe(sourceDataSourceId, requestedAdapter, requestedScope,
                    lakeDorisReachable, null);
        }
        if (staticCapability == null || hasBlockingCapabilityReason(staticCapability.reasonCodes())) {
            return capabilityWithProbe(sourceDataSourceId, requestedAdapter, requestedScope,
                    lakeDorisReachable, null);
        }

        ProbeAttempt attempt = probeSourceFromDoris(sourceDataSourceId, requestedAdapter);
        if (!attempt.completed()) {
            // Missing credentials, an incomplete source row or an unverified
            // driver means that no source-side observation was made.  Keep
            // the capability UNKNOWN instead of misreporting a setup problem
            // as a network outage.
            return capabilityWithProbe(sourceDataSourceId, requestedAdapter, requestedScope,
                    lakeDorisReachable, null);
        }
        boolean reachable = attempt.reachable();
        String key = probeKey(sourceDataSourceId, requestedAdapter);
        sourceProbeCache.put(key, reachable);
        return capabilityWithProbe(sourceDataSourceId, requestedAdapter, requestedScope,
                lakeDorisReachable,
                new LakeSourceNetworkProbeCache.ProbeResult(reachable, System.currentTimeMillis()));
    }

    @Override
    public PaginationResult<LakeExternalCatalogVO> page(LakeExternalCatalogPageDTO request) {
        return persistenceService.page(request);
    }

    @Override
    public LakeExternalCatalogVO detail(Long bindingId) {
        return persistenceService.detail(bindingId);
    }

    /**
     * Creates a catalog through the durable three-phase operation boundary.
     * The request is persisted first without credentials; credentials and the
     * complete desired spec exist only inside the external-operation callback.
     */
    @Override
    public LakeExternalCatalogVO create(LakeExternalCatalogCreateDTO request) {
        CreateInput input = validateCreateInput(request);
        requireExecutionBoundaries();
        staticPreflight(input.sourceDataSourceId(), input.adapter(), input.scope());

        LakeExternalCatalogCreateDTO pendingRequest = pendingRequest(input);
        LakeExternalCatalogVO pending = persistenceService.createPending(
                pendingRequest, requireCurrentUserId());
        if (pending == null || pending.getId() == null || pending.getId() <= 0) {
            throw catalogInvalid("catalog binding could not be reserved");
        }

        LakeOperationHandle handle;
        try {
            handle = coordinator.begin(new LakeOperationIntent(
                    LakeResourceTypes.EXTERNAL_CATALOG_BINDING,
                    pending.getId(),
                    LakeOperationType.CREATE_CATALOG,
                    requestHash(input),
                    requireCurrentUserId()));
        } catch (LakeOperationException exception) {
            throw catalogConflict("catalog binding is currently being changed");
        }

        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            LakeOperationExecution<LakeCatalogOperationResult> execution = coordinator.execute(
                    handle, () -> executeCreate(input, errorCode));
            if (!coordinator.finalizeSuccess(
                    handle, "Catalog created and validated", execution.externalResult())) {
                throw catalogStale("catalog create result is stale");
            }
            return persistenceService.detail(pending.getId());
        } catch (LakeOperationException exception) {
            throw classifiedOperationFailure(errorCode.get(),
                    "catalog create is unavailable");
        }
    }

    /**
     * Updates the desired mount configuration through the same external
     * operation boundary as create.  Catalog names are intentionally stable:
     * Doris has no atomic rename for a JDBC catalog, so callers must delete
     * and recreate when the target name itself needs to change.
     */
    @Override
    public LakeExternalCatalogVO update(Long bindingId, LakeExternalCatalogUpdateDTO request) {
        requireExecutionBoundaries();
        LakeExternalCatalogVO current = persistenceService.detail(bindingId);
        if (current == null || Boolean.TRUE.equals(current.getDeleted())) {
            throw catalogNotFound();
        }
        CreateInput input = validateUpdateInput(bindingId, current, request);
        staticPreflight(input.sourceDataSourceId(), input.adapter(), input.scope());
        // Persist only the server-normalized shape.  A browser request must
        // never be able to smuggle a JDBC URL, driver facts, credentials or
        // an arbitrary desiredSpecJson into the local binding row.
        LakeExternalCatalogUpdateDTO pendingRequest = pendingUpdateRequest(input,
                request.getExpectedLockVersion());
        LakeExternalCatalogVO pending = persistenceService.updatePending(
                bindingId, pendingRequest, requireCurrentUserId());
        if (pending == null || pending.getId() == null || pending.getId() <= 0) {
            throw catalogConflict("catalog binding could not be reserved");
        }

        LakeOperationHandle handle;
        try {
            handle = coordinator.begin(new LakeOperationIntent(
                    LakeResourceTypes.EXTERNAL_CATALOG_BINDING,
                    bindingId,
                    LakeOperationType.UPDATE_CATALOG,
                    requestHash(input),
                    requireCurrentUserId()));
        } catch (LakeOperationException exception) {
            throw catalogConflict("catalog binding is currently being changed");
        }

        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            LakeOperationExecution<LakeCatalogOperationResult> execution = coordinator.execute(
                    handle, () -> executeUpdate(input, errorCode));
            if (!coordinator.finalizeSuccess(
                    handle, "Catalog updated and validated", execution.externalResult())) {
                throw catalogStale("catalog update result is stale");
            }
            return persistenceService.detail(bindingId);
        } catch (LakeOperationException exception) {
            throw classifiedOperationFailure(errorCode.get(),
                    "catalog update is unavailable");
        }
    }

    /** Reads the existing Doris catalog and commits a safe actual snapshot. */
    @Override
    public LakeExternalCatalogVO validate(Long bindingId) {
        return observe(bindingId, LakeOperationType.VALIDATE, false,
                "Catalog validation completed");
    }

    /** Refreshes Doris connector metadata, then records a safe observation. */
    @Override
    public LakeExternalCatalogVO refresh(Long bindingId) {
        return observe(bindingId, LakeOperationType.REFRESH_CATALOG, true,
                "Catalog refresh completed");
    }

    /** Explicit drift observation; GET/detail never performs this work. */
    @Override
    public LakeExternalCatalogVO reconcile(Long bindingId) {
        return observe(bindingId, LakeOperationType.RECONCILE, false,
                "Catalog reconcile completed");
    }

    /** Drops only the Doris logical mount and leaves the source data untouched. */
    @Override
    public LakeExternalCatalogVO delete(Long bindingId) {
        requireExecutionBoundaries();
        LakeExternalCatalogVO binding = persistenceService.detail(bindingId);
        if (binding == null || Boolean.TRUE.equals(binding.getDeleted())) {
            throw catalogNotFound();
        }
        Long lakeDataSourceId = binding.getLakeDataSourceId();
        if (lakeDataSourceId == null || lakeDataSourceId <= 0
                || StringUtils.isBlank(binding.getTargetCatalogName())) {
            throw catalogInvalid("catalog binding");
        }
        String catalogName;
        try {
            catalogName = DorisIdentifier.normalize(binding.getTargetCatalogName());
        } catch (RuntimeException exception) {
            throw catalogInvalid("targetCatalogName");
        }
        LakeOperationHandle handle;
        try {
            handle = coordinator.begin(new LakeOperationIntent(
                    LakeResourceTypes.EXTERNAL_CATALOG_BINDING,
                    bindingId,
                    LakeOperationType.DROP_CATALOG,
                    LakeCatalogDesiredSpecCanonicalizer.sha256(
                            "DROP_CATALOG\u0000" + bindingId + "\u0000" + catalogName),
                    requireCurrentUserId()));
        } catch (LakeOperationException exception) {
            throw catalogConflict("catalog binding is currently being changed");
        }
        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            LakeOperationExecution<LakeCatalogOperationResult> execution = coordinator.execute(
                    handle, () -> executeDrop(lakeDataSourceId, catalogName, errorCode));
            if (!coordinator.finalizeSuccess(
                    handle, "Catalog mount deleted; source data was not changed",
                    execution.externalResult())) {
                throw catalogStale("catalog delete result is stale");
            }
            return persistenceService.detail(bindingId);
        } catch (LakeOperationException exception) {
            throw classifiedOperationFailure(errorCode.get(),
                    "catalog delete is unavailable");
        }
    }

    private LakeExternalCatalogVO observe(
            Long bindingId,
            LakeOperationType operationType,
            boolean refresh,
            String summary) {
        requireExecutionBoundaries();
        LakeExternalCatalogVO binding = persistenceService.detail(bindingId);
        if (binding == null || Boolean.TRUE.equals(binding.getDeleted())) {
            throw catalogNotFound();
        }
        LakeCatalogDesiredSpec desired = persistenceService.desiredSpec(bindingId);
        final LakeCatalogDesiredSpec normalized;
        try {
            normalized = LakeCatalogDesiredSpecValidator.validateAndNormalize(desired);
        } catch (RuntimeException exception) {
            throw catalogInvalid("persisted desired spec");
        }
        Long lakeDataSourceId = binding.getLakeDataSourceId();
        if (lakeDataSourceId == null || lakeDataSourceId <= 0) {
            throw catalogInvalid("lakeDataSourceId");
        }
        LakeOperationHandle handle;
        try {
            handle = coordinator.begin(new LakeOperationIntent(
                    LakeResourceTypes.EXTERNAL_CATALOG_BINDING,
                    bindingId,
                    operationType,
                    LakeCatalogDesiredSpecCanonicalizer.sha256(normalized),
                    requireCurrentUserId()));
        } catch (LakeOperationException exception) {
            throw catalogConflict("catalog binding is currently being changed");
        }
        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            LakeOperationExecution<LakeCatalogOperationResult> execution = coordinator.execute(
                    handle, () -> executeObservation(
                            lakeDataSourceId, normalized, refresh, errorCode));
            if (!coordinator.finalizeSuccess(handle, summary, execution.externalResult())) {
                throw catalogStale("catalog observation result is stale");
            }
            return persistenceService.detail(bindingId);
        } catch (LakeOperationException exception) {
            throw classifiedOperationFailure(errorCode.get(),
                    "catalog observation is unavailable");
        }
    }

    private LakeCatalogOperationResult executeCreate(
            CreateInput input, AtomicReference<String> errorCode) {
        try (DorisLakeClient client = dorisClientProvider.get(input.lakeDataSourceId())) {
            if (!client.ping()) {
                errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                        "lake Doris is unavailable");
            }
            DataSource source = requireSource(input.sourceDataSourceId());
            LakeCatalogCredentialRevisionService.ExecutionCredentials credentials =
                    credentialService.resolveForExecution(source, input.adapter());
            LakeJdbcDriverRegistry.DriverStatus driverStatus =
                    driverRegistry.status(input.adapter());
            LakeJdbcDriverRegistry.DriverRegistration registration = driverStatus.registration();
            if (!driverStatus.available() || registration == null) {
                errorCode.set(LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID);
                throw external(LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID,
                        "catalog driver is unavailable");
            }
            LakeCatalogDesiredSpec desired = desiredSpec(input, source, credentials, registration);
            client.createCatalog(desired, driverRegistry, credentials.ddlCredentials());
            if (!client.catalogExists(desired.catalogName())) {
                errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                        "catalog create could not be verified");
            }
            LakeCatalogValidationResult validation = client.validateCatalog(
                    desired.catalogName(), desired);
            if (validation == null || !validation.isMatch()) {
                errorCode.set(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED);
                throw external(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED,
                        "catalog validation did not match");
            }
            return publication(desired, validation, LakeResourceStatus.READY);
        } catch (LakeExternalOperationException exception) {
            throw exception;
        } catch (LakeServiceException exception) {
            String code = exception.getLakeErrorCode();
            errorCode.set(code);
            throw external(code, "catalog operation is unavailable");
        } catch (RuntimeException exception) {
            errorCode.set(classifyRuntime(exception));
            throw external(errorCode.get(), "catalog operation is unavailable");
        }
    }

    private LakeCatalogOperationResult executeUpdate(
            CreateInput input, AtomicReference<String> errorCode) {
        try (DorisLakeClient client = dorisClientProvider.get(input.lakeDataSourceId())) {
            if (!client.ping()) {
                errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                        "lake Doris is unavailable");
            }
            DataSource source = requireSource(input.sourceDataSourceId());
            LakeCatalogCredentialRevisionService.ExecutionCredentials credentials =
                    credentialService.resolveForExecution(source, input.adapter());
            LakeJdbcDriverRegistry.DriverStatus driverStatus =
                    driverRegistry.status(input.adapter());
            LakeJdbcDriverRegistry.DriverRegistration registration = driverStatus.registration();
            if (!driverStatus.available() || registration == null) {
                errorCode.set(LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID);
                throw external(LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID,
                        "catalog driver is unavailable");
            }
            LakeCatalogDesiredSpec desired = desiredSpec(input, source, credentials, registration);
            if (!client.catalogExists(desired.catalogName())) {
                errorCode.set(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED);
                throw external(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED,
                        "catalog does not exist");
            }
            client.alterCatalog(
                    desired.catalogName(), desired, driverRegistry, credentials.ddlCredentials());
            LakeCatalogValidationResult validation = client.validateCatalog(
                    desired.catalogName(), desired);
            if (validation == null || !validation.isMatch()) {
                errorCode.set(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED);
                throw external(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED,
                        "catalog validation did not match");
            }
            return publication(desired, validation, LakeResourceStatus.READY);
        } catch (LakeExternalOperationException exception) {
            throw exception;
        } catch (LakeServiceException exception) {
            String code = exception.getLakeErrorCode();
            errorCode.set(code);
            throw external(code, "catalog update is unavailable");
        } catch (RuntimeException exception) {
            errorCode.set(classifyRuntime(exception));
            throw external(errorCode.get(), "catalog update is unavailable");
        }
    }

    private LakeCatalogOperationResult executeValidation(
            Long lakeDataSourceId,
            LakeCatalogDesiredSpec desired,
            AtomicReference<String> errorCode) {
        return executeObservation(lakeDataSourceId, desired, false, errorCode);
    }

    private LakeCatalogOperationResult executeObservation(
            Long lakeDataSourceId,
            LakeCatalogDesiredSpec desired,
            boolean refresh,
            AtomicReference<String> errorCode) {
        try (DorisLakeClient client = dorisClientProvider.get(lakeDataSourceId)) {
            if (!client.ping()) {
                errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                        "lake Doris is unavailable");
            }
            if (refresh) {
                client.refreshCatalog(desired.catalogName());
            }
            LakeCatalogValidationResult validation = client.validateCatalog(
                    desired.catalogName(), desired);
            if (validation == null) {
                errorCode.set(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED);
                throw external(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED,
                        "catalog validation returned no result");
            }
            return new LakeCatalogOperationResult(
                    null,
                    null,
                    null,
                    null,
                    snapshotJson(validation),
                    validation.status().name(),
                    resourceStatus(validation));
        } catch (LakeExternalOperationException exception) {
            throw exception;
        } catch (LakeServiceException exception) {
            String code = exception.getLakeErrorCode();
            errorCode.set(code);
            throw external(code, "catalog validation is unavailable");
        } catch (RuntimeException exception) {
            errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
            throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "catalog validation is unavailable");
        }
    }

    private LakeCatalogOperationResult executeDrop(
            Long lakeDataSourceId,
            String catalogName,
            AtomicReference<String> errorCode) {
        try (DorisLakeClient client = dorisClientProvider.get(lakeDataSourceId)) {
            if (!client.ping()) {
                errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                        "lake Doris is unavailable");
            }
            client.dropCatalog(catalogName);
            if (client.catalogExists(catalogName)) {
                errorCode.set(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED);
                throw external(LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED,
                        "catalog delete could not be verified");
            }
            return LakeCatalogOperationResult.observation(
                    null, "DELETED", LakeResourceStatus.DELETED);
        } catch (LakeExternalOperationException exception) {
            throw exception;
        } catch (LakeServiceException exception) {
            String code = exception.getLakeErrorCode();
            errorCode.set(code);
            throw external(code, "catalog delete is unavailable");
        } catch (RuntimeException exception) {
            errorCode.set(classifyRuntime(exception));
            throw external(errorCode.get(), "catalog delete is unavailable");
        }
    }

    private CreateInput validateCreateInput(LakeExternalCatalogCreateDTO request) {
        if (request == null || request.getSourceDataSourceId() == null
                || request.getSourceDataSourceId() <= 0) {
            throw catalogInvalid("sourceDataSourceId");
        }
        Long lakeDataSourceId = canonicalLakeDataSourceId(request.getLakeDataSourceId());
        if (lakeDataSourceId == null || lakeDataSourceId <= 0) {
            throw catalogInvalid("lakeDataSourceId");
        }
        LakeJdbcAdapterType adapter = adapterFor(request.getAdapter());
        if (adapter == null) {
            throw catalogInvalid("adapter");
        }
        if (request.getScope() == null) {
            throw catalogInvalid("scope");
        }
        String catalogName;
        try {
            catalogName = DorisIdentifier.normalize(request.getTargetCatalogName());
        } catch (RuntimeException exception) {
            throw catalogInvalid("targetCatalogName");
        }
        // Exercise the same strict scope/list/option validation as the DDL
        // builder without reading credentials or driver configuration.
        LakeCatalogDesiredSpec shape = new LakeCatalogDesiredSpec(
                catalogName, request.getSourceDataSourceId(), "source",
                adapter, request.getScope(), "jdbc:placeholder", "driver.jar",
                "x.Driver", SHA256_PLACEHOLDER, "registry", "credential",
                request.getDatabaseInclude(), request.getTableInclude(), request.getOptions());
        try {
            shape = LakeCatalogDesiredSpecValidator.validateAndNormalize(shape);
        } catch (RuntimeException exception) {
            throw catalogInvalid("scope or catalog filters");
        }
        return new CreateInput(
                lakeDataSourceId,
                request.getSourceDataSourceId(),
                catalogName,
                adapter,
                request.getScope(),
                shape.databaseInclude(),
                shape.tableInclude(),
                shape.options());
    }

    private CreateInput validateUpdateInput(
            Long bindingId,
            LakeExternalCatalogVO current,
            LakeExternalCatalogUpdateDTO request) {
        if (bindingId == null || bindingId <= 0 || request == null) {
            throw catalogInvalid("catalog update request");
        }
        Long sourceDataSourceId = current.getSourceDataSourceId();
        Long lakeDataSourceId = current.getLakeDataSourceId();
        if (sourceDataSourceId == null || sourceDataSourceId <= 0
                || lakeDataSourceId == null || lakeDataSourceId <= 0) {
            throw catalogInvalid("catalog binding");
        }
        String catalogName;
        try {
            catalogName = DorisIdentifier.normalize(request.getTargetCatalogName());
        } catch (RuntimeException exception) {
            throw catalogInvalid("targetCatalogName");
        }
        String currentCatalogName;
        try {
            currentCatalogName = DorisIdentifier.normalize(current.getTargetCatalogName());
        } catch (RuntimeException exception) {
            throw catalogInvalid("persisted targetCatalogName");
        }
        if (!currentCatalogName.equals(catalogName)) {
            throw catalogInvalid("targetCatalogName cannot be changed; delete and recreate");
        }
        LakeJdbcAdapterType adapter = adapterFor(request.getAdapter());
        if (adapter == null) {
            throw catalogInvalid("adapter");
        }
        if (request.getScope() == null) {
            throw catalogInvalid("scope");
        }
        List<String> databases = request.getDatabaseInclude() == null
                ? List.of() : request.getDatabaseInclude();
        List<String> tables = request.getTableInclude() == null
                ? List.of() : request.getTableInclude();
        Map<String, String> options = request.getOptions() == null
                ? Map.of() : request.getOptions();
        LakeCatalogDesiredSpec shape = new LakeCatalogDesiredSpec(
                catalogName, sourceDataSourceId, "source", adapter, request.getScope(),
                "jdbc:placeholder", "driver.jar", "x.Driver", SHA256_PLACEHOLDER,
                "registry", "credential", databases, tables, options);
        try {
            shape = LakeCatalogDesiredSpecValidator.validateAndNormalize(shape);
        } catch (RuntimeException exception) {
            throw catalogInvalid("scope or catalog filters");
        }
        return new CreateInput(
                lakeDataSourceId, sourceDataSourceId, catalogName, adapter,
                request.getScope(), shape.databaseInclude(), shape.tableInclude(), shape.options());
    }

    private LakeExternalCatalogCreateDTO pendingRequest(CreateInput input) {
        LakeExternalCatalogCreateDTO pending = new LakeExternalCatalogCreateDTO();
        pending.setLakeDataSourceId(input.lakeDataSourceId());
        pending.setSourceDataSourceId(input.sourceDataSourceId());
        pending.setTargetCatalogName(input.catalogName());
        pending.setAdapter(input.adapter().code());
        pending.setScope(input.scope());
        pending.setDatabaseInclude(input.databaseInclude());
        pending.setTableInclude(input.tableInclude());
        pending.setOptions(input.options());
        return pending;
    }

    private LakeExternalCatalogUpdateDTO pendingUpdateRequest(
            CreateInput input, Integer expectedLockVersion) {
        LakeExternalCatalogUpdateDTO pending = new LakeExternalCatalogUpdateDTO();
        pending.setTargetCatalogName(input.catalogName());
        pending.setAdapter(input.adapter().code());
        pending.setScope(input.scope());
        pending.setDatabaseInclude(input.databaseInclude());
        pending.setTableInclude(input.tableInclude());
        pending.setOptions(input.options());
        pending.setExpectedLockVersion(expectedLockVersion);
        return pending;
    }

    private void staticPreflight(Long sourceId, LakeJdbcAdapterType adapter,
                                 LakeCatalogScope scope) {
        LakeCatalogCapability capability;
        try {
            // Network is deliberately not a static gate.  The external
            // callback performs the real ping/create/validate proof.
            capability = capabilityResolver.resolve(sourceId, adapter, scope, true, true);
        } catch (RuntimeException exception) {
            throw catalogInvalid("catalog capability");
        }
        if (capability == null) {
            throw catalogInvalid("catalog capability");
        }
        List<String> reasons = capability.reasonCodes() == null
                ? List.of() : capability.reasonCodes();
        boolean blocking = !capability.enabled()
                && reasons.stream().noneMatch(LakeLogicalCatalogServiceImpl::isNetworkWarning)
                || reasons.stream().anyMatch(reason -> !isNetworkWarning(reason));
        if (blocking) {
            throw catalogInvalid("catalog capability");
        }
    }

    private LakeCatalogDesiredSpec desiredSpec(
            CreateInput input,
            DataSource source,
            LakeCatalogCredentialRevisionService.ExecutionCredentials credentials,
            LakeJdbcDriverRegistry.DriverRegistration registration) {
        LakeCatalogDesiredSpec desired = new LakeCatalogDesiredSpec(
                input.catalogName(),
                source.getId(),
                sourceRevision(source),
                input.adapter(),
                input.scope(),
                credentials.jdbcUrl(),
                java.util.Objects.requireNonNullElse(registration.url(), registration.driverLocation()),
                registration.driverClass(),
                registration.checksum(),
                registration.registryRevision(),
                null,
                input.databaseInclude(),
                input.tableInclude(),
                input.options());
        try {
            return LakeCatalogDesiredSpecValidator.validateAndNormalize(desired, driverRegistry);
        } catch (RuntimeException exception) {
            throw external(LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID,
                    "catalog desired state is invalid");
        }
    }

    private LakeCatalogOperationResult publication(
            LakeCatalogDesiredSpec desired,
            LakeCatalogValidationResult validation,
            LakeResourceStatus resourceStatus) {
        String canonicalJson = LakeCatalogDesiredSpecCanonicalizer.canonicalJson(desired);
        return new LakeCatalogOperationResult(
                canonicalJson,
                LakeCatalogDesiredSpecCanonicalizer.sha256(canonicalJson),
                null,
                desired.driverChecksum(),
                snapshotJson(validation),
                validation.status().name(),
                resourceStatus);
    }

    private static String snapshotJson(LakeCatalogValidationResult validation) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", validation.status().name());
        snapshot.put("code", validation.code());
        snapshot.put("actualProperties", validation.actualProperties());
        snapshot.put("mismatches", validation.mismatches());
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static LakeResourceStatus resourceStatus(LakeCatalogValidationResult result) {
        return switch (result.status()) {
            case MATCH, MISMATCH -> LakeResourceStatus.READY;
            case MISSING -> LakeResourceStatus.MISSING;
            case UNKNOWN -> LakeResourceStatus.UNKNOWN;
        };
    }

    private DataSource requireSource(Long sourceId) {
        try {
            DataSource source = dataSourceDao.queryById(sourceId);
            if (source == null) {
                throw catalogInvalid("sourceDataSourceId");
            }
            return source;
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw catalogInvalid("sourceDataSourceId");
        }
    }

    private Integer requireCurrentUserId() {
        try {
            Integer userId = currentUserProvider.getCurrentUserId();
            if (userId == null || userId <= 0) {
                throw catalogInvalid("current user");
            }
            return userId;
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw catalogInvalid("current user");
        }
    }

    private void requireExecutionBoundaries() {
        if (coordinator == null || currentUserProvider == null) {
            throw catalogInvalid("catalog execution boundary");
        }
    }

    private static String sourceRevision(DataSource source) {
        if (source.getUpdateTime() == null) {
            return "datasource-" + source.getId();
        }
        return "datasource-" + source.getId() + "-" + source.getUpdateTime().getTime();
    }

    private static String requestHash(CreateInput input) {
        return LakeCatalogDesiredSpecCanonicalizer.sha256(
                "EXTERNAL_CATALOG\u0000" + input.sourceDataSourceId()
                        + "\u0000" + input.catalogName() + "\u0000"
                        + input.adapter().code() + "\u0000" + input.scope().name());
    }

    private static boolean isNetworkWarning(String reason) {
        return LakeCatalogCapabilityReason.SOURCE_NETWORK_UNKNOWN.equalsIgnoreCase(reason)
                || LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE.equalsIgnoreCase(reason)
                || LakeCatalogCapabilityReason.LAKE_DORIS_UNREACHABLE.equalsIgnoreCase(reason);
    }

    /** Converts resolver facts plus an optional source observation into the safe API VO. */
    private LakeLogicalCapabilityVO capabilityWithProbe(
            Long sourceDataSourceId,
            LakeJdbcAdapterType adapter,
            LakeCatalogScope scope,
            boolean lakeDorisReachable,
            LakeSourceNetworkProbeCache.ProbeResult probe) {
        boolean sourceNetworkReachable = probe != null && probe.reachable();
        LakeCatalogCapability resolved;
        try {
            // The resolver accepts a boolean rather than a tri-state.  An
            // unknown probe is represented as false and the unreachable
            // warning is replaced below, so the response never claims a
            // source failure that was not actually observed.
            resolved = capabilityResolver.resolve(
                    sourceDataSourceId, adapter, scope, lakeDorisReachable,
                    sourceNetworkReachable);
        } catch (RuntimeException exception) {
            resolved = null;
        }
        List<String> reasons = resolved == null || resolved.reasonCodes() == null
                ? List.of(LakeCatalogCapabilityReason.ADAPTER_MISSING)
                : resolved.reasonCodes();
        if (probe == null) {
            reasons = withoutIgnoreCase(reasons,
                    LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE);
            reasons = append(reasons, LakeCatalogCapabilityReason.SOURCE_NETWORK_UNKNOWN);
        }
        boolean supported = probe != null && resolved != null && resolved.enabled();
        return new LakeLogicalCapabilityVO(
                sourceDataSourceId,
                adapter,
                scope,
                supported,
                probe != null,
                sourceNetworkReachable,
                lakeDorisReachable,
                reasons);
    }

    private boolean hasBlockingCapabilityReason(List<String> reasons) {
        return reasons != null && reasons.stream().anyMatch(reason -> !isNetworkWarning(reason));
    }

    /** Builds a revision-aware process-local cache key without retaining credentials. */
    private String probeKey(Long sourceDataSourceId, LakeJdbcAdapterType adapter) {
        String revision = "unknown";
        if (sourceDataSourceId != null && sourceDataSourceId > 0) {
            try {
                DataSource source = dataSourceDao.queryById(sourceDataSourceId);
                if (source != null) {
                    revision = sourceRevision(source);
                }
            } catch (RuntimeException ignored) {
                // A DAO failure must not make a read-only capability endpoint
                // fail; the unknown revision simply avoids a cache hit.
            }
        }
        return sourceDataSourceId + ":"
                + (adapter == null ? "unknown" : adapter.code()) + ":" + revision;
    }

    /** Executes one temporary catalog observation from the Doris side only. */
    private ProbeAttempt probeSourceFromDoris(
            Long sourceDataSourceId, LakeJdbcAdapterType adapter) {
        final DataSource source;
        final LakeJdbcDriverRegistry.DriverRegistration registration;
        final LakeCatalogCredentialRevisionService.ExecutionCredentials credentials;
        try {
            source = requireSource(sourceDataSourceId);
            LakeJdbcDriverRegistry.DriverStatus driverStatus = driverRegistry.status(adapter);
            registration = driverStatus.registration();
            if (!driverStatus.available() || registration == null) {
                return ProbeAttempt.notRun();
            }
            credentials = credentialService.resolveForExecution(source, adapter);
        } catch (RuntimeException exception) {
            return ProbeAttempt.notRun();
        }

        String catalogName = probeCatalogName(sourceDataSourceId);
        LakeCatalogDesiredSpec desired = new LakeCatalogDesiredSpec(
                catalogName,
                source.getId(),
                sourceRevision(source),
                adapter,
                LakeCatalogScope.ALL,
                credentials.jdbcUrl(),
                java.util.Objects.requireNonNullElse(registration.url(), registration.driverLocation()),
                registration.driverClass(),
                registration.checksum(),
                registration.registryRevision(),
                null,
                List.of(),
                List.of(),
                Map.of());
        try (DorisLakeClient client = dorisClientProvider.get(configuredLakeDataSourceId())) {
            client.probeSource(desired, driverRegistry, credentials.ddlCredentials());
            return ProbeAttempt.completed(true);
        } catch (RuntimeException exception) {
            // The temporary catalog operation ran and failed.  This is a real
            // negative observation and may safely be cached for the short TTL.
            return ProbeAttempt.completed(false);
        }
    }

    private static String probeCatalogName(Long sourceDataSourceId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return "_lake_probe_" + sourceDataSourceId + "_" + suffix.substring(0, 20);
    }

    private static String classifyRuntime(RuntimeException exception) {
        if (exception instanceof IllegalArgumentException) {
            return LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID;
        }
        return LakeErrorCode.LAKE_DORIS_UNAVAILABLE;
    }

    private static LakeExternalOperationException external(String code, String message) {
        return new LakeExternalOperationException(code, message);
    }

    private static LakeServiceException classifiedOperationFailure(
            String errorCode, String fallback) {
        String code = errorCode == null ? LakeErrorCode.LAKE_DORIS_UNAVAILABLE : errorCode;
        if (LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID.equals(code)) {
            return catalogInvalid(fallback);
        }
        if (LakeErrorCode.LAKE_CATALOG_VALIDATION_FAILED.equals(code)) {
            return new LakeServiceException(code, fallback);
        }
        if (LakeErrorCode.LAKE_OPERATION_STALE.equals(code)) {
            return catalogStale(fallback);
        }
        return new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE, fallback);
    }

    private static LakeServiceException catalogInvalid(String field) {
        return new LakeServiceException(LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID,
                "Catalog request is invalid: " + field);
    }

    private static LakeServiceException catalogConflict(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_CATALOG_CONFLICT, message);
    }

    private static LakeServiceException catalogNotFound() {
        return new LakeServiceException(LakeErrorCode.LAKE_CATALOG_NOT_FOUND,
                "Catalog binding does not exist");
    }

    private static LakeServiceException catalogStale(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_OPERATION_STALE, message);
    }

    private static final String SHA256_PLACEHOLDER =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private record CreateInput(
            Long lakeDataSourceId,
            Long sourceDataSourceId,
            String catalogName,
            LakeJdbcAdapterType adapter,
            LakeCatalogScope scope,
            List<String> databaseInclude,
            List<String> tableInclude,
            Map<String, String> options) {
    }

    private LakeJdbcAdapterType adapterFor(Long sourceDataSourceId) {
        if (sourceDataSourceId == null || sourceDataSourceId <= 0) {
            return null;
        }
        try {
            DataSource source = dataSourceDao.queryById(sourceDataSourceId);
            return source == null || source.getDbType() == null
                    ? null : adapterFor(source.getDbType().name());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static LakeJdbcAdapterType adapterFor(String dbType) {
        if (StringUtils.isBlank(dbType)) {
            return null;
        }
        try {
            return LakeJdbcAdapterType.parse(dbType);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Long configuredLakeDataSourceId() {
        if (warehouseService != null) {
            try {
                org.apache.seatunnel.web.spi.bean.vo.LakeWarehouseConfigVO config =
                        warehouseService.getConfig();
                return config == null ? null : config.getSystemDataSourceId();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return lakeProperties.getDataSourceId();
    }

    private Long canonicalLakeDataSourceId(Long requestedId) {
        Long configured = configuredLakeDataSourceId();
        if (warehouseService == null) {
            return requestedId == null ? configured : requestedId;
        }
        try {
            return warehouseService.canonicalDataSourceId(requestedId);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean probeLakeDoris() {
        Long lakeDataSourceId = configuredLakeDataSourceId();
        if (lakeDataSourceId == null || lakeDataSourceId <= 0) {
            return false;
        }
        try (DorisLakeClient client = dorisClientProvider.get(lakeDataSourceId)) {
            return client.ping();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private record ProbeAttempt(boolean completed, boolean reachable) {

        private static ProbeAttempt notRun() {
            return new ProbeAttempt(false, false);
        }

        private static ProbeAttempt completed(boolean reachable) {
            return new ProbeAttempt(true, reachable);
        }
    }

    private static List<String> append(List<String> source, String value) {
        Set<String> values = new LinkedHashSet<>();
        if (source != null) {
            values.addAll(source);
        }
        values.add(value);
        return List.copyOf(values);
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        return values != null && values.stream().anyMatch(value -> expected.equalsIgnoreCase(value));
    }

    private static List<String> withoutIgnoreCase(List<String> values, String excluded) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(value -> !excluded.equalsIgnoreCase(value)).toList();
    }
}
