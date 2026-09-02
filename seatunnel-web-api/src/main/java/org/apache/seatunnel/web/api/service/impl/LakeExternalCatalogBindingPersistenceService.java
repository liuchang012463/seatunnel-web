package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.CatalogPropertyRedactor;
import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpec;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeExternalCatalogBinding;
import org.apache.seatunnel.web.dao.repository.LakeExternalCatalogBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Local persistence/read-model service for logical Doris catalog bindings.
 * It deliberately has no Doris client dependency; all methods operate on the
 * binding row and are safe to call from a local GET/page request.
 */
@Service
public class LakeExternalCatalogBindingPersistenceService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int MAX_ERROR_LENGTH = 1_000;
    private static final String REDACTED_ERROR = "[REDACTED_ERROR]";

    private final LakeExternalCatalogBindingDao bindingDao;
    private final LakeProperties lakeProperties;
    private final LakeWarehouseService warehouseService;

    @Autowired
    public LakeExternalCatalogBindingPersistenceService(
            LakeExternalCatalogBindingDao bindingDao,
            LakeProperties lakeProperties,
            LakeWarehouseService warehouseService) {
        this.bindingDao = Objects.requireNonNull(bindingDao, "bindingDao");
        this.lakeProperties = Objects.requireNonNull(lakeProperties, "lakeProperties");
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService");
    }

    /** Compatibility constructor for repository-focused clients and tests. */
    public LakeExternalCatalogBindingPersistenceService(
            LakeExternalCatalogBindingDao bindingDao, LakeProperties lakeProperties) {
        this.bindingDao = Objects.requireNonNull(bindingDao, "bindingDao");
        this.lakeProperties = Objects.requireNonNull(lakeProperties, "lakeProperties");
        this.warehouseService = null;
    }

    /** Small constructor for repository-focused tests and embedders. */
    public LakeExternalCatalogBindingPersistenceService(
            LakeExternalCatalogBindingDao bindingDao) {
        this(bindingDao, new LakeProperties());
    }

    /**
     * TX create phase.  A deleted row for the same source is reopened as a new
     * generation; rows for another source remain a target reservation.
     */
    @Transactional
    public LakeExternalCatalogVO createPending(
            LakeExternalCatalogCreateDTO request, Integer operatorId) {
        LakeExternalCatalogBinding candidate = candidate(request, operatorId);
        LakeExternalCatalogBinding existingSource = bindingDao
                .queryBySourceDataSourceIdIncludingDeleted(candidate.getSourceDataSourceId());
        if (existingSource != null && !Boolean.TRUE.equals(existingSource.getDeleted())) {
            throw conflict("Source data source already has an active catalog binding");
        }

        LakeExternalCatalogBinding target = bindingDao
                .queryByLakeDataSourceIdAndCatalogNameIncludingDeleted(
                        candidate.getLakeDataSourceId(), candidate.getCatalogName());
        if (target != null && !Objects.equals(target.getSourceDataSourceId(),
                candidate.getSourceDataSourceId())) {
            throw conflict("Target catalog name is already reserved");
        }

        LakeExternalCatalogBinding persisted;
        if (existingSource != null) {
            if (target != null && !Objects.equals(target.getId(), existingSource.getId())) {
                throw conflict("Target catalog name is already reserved");
            }
            persisted = reopen(existingSource, candidate, operatorId);
        } else {
            candidate.initInsert();
            defaults(candidate, operatorId);
            try {
                if (bindingDao.insert(candidate) <= 0) {
                    throw conflict("Catalog binding could not be created");
                }
            } catch (DuplicateKeyException exception) {
                throw conflict("Catalog source or target is already bound");
            }
            persisted = candidate;
        }
        return toVO(persisted);
    }

    /**
     * TX update phase.  Desired state is changed by lock-version CAS while
     * operationToken remains null; the external coordinator may lease the row
     * after this method commits.
     */
    @Transactional
    public LakeExternalCatalogVO updatePending(
            Long id, LakeExternalCatalogUpdateDTO request, Integer operatorId) {
        if (id == null || id <= 0 || request == null
                || request.getExpectedLockVersion() == null) {
            throw invalid("Catalog update request is invalid");
        }
        LakeExternalCatalogBinding current = bindingDao.queryActiveById(id);
        if (current == null) {
            throw notFound();
        }
        if (current.getOperationToken() != null) {
            throw conflict("Catalog binding is currently being changed");
        }
        if (!Objects.equals(current.getLockVersion(), request.getExpectedLockVersion())) {
            throw casFailed();
        }

        LakeExternalCatalogBinding target = copyUpdate(current, request, operatorId);
        LakeExternalCatalogBinding reserved = bindingDao
                .queryByLakeDataSourceIdAndCatalogNameIncludingDeleted(
                        target.getLakeDataSourceId(), target.getCatalogName());
        if (reserved != null && !Objects.equals(reserved.getId(), current.getId())) {
            throw conflict("Target catalog name is already reserved");
        }
        if (!bindingDao.updateIfTokenAndVersion(
                target, null, request.getExpectedLockVersion())) {
            throw casFailed();
        }
        return toVO(afterSuccessfulCas(target, request.getExpectedLockVersion()));
    }

    /** Local GET: no Doris client or network lookup is performed. */
    public LakeExternalCatalogVO detail(Long id) {
        if (id == null || id <= 0) {
            throw notFound();
        }
        LakeExternalCatalogBinding binding = bindingDao.queryByIdIncludingDeleted(id);
        if (binding == null) {
            throw notFound();
        }
        return toVO(binding);
    }

    /** Reads the persisted, non-secret desired spec for an external operation. */
    public LakeCatalogDesiredSpec desiredSpec(Long id) {
        LakeExternalCatalogBinding binding = requireIncludingDeleted(id);
        if (Boolean.TRUE.equals(binding.getDeleted())
                || StringUtils.isBlank(binding.getDesiredSpecJson())) {
            throw invalid("desiredSpecJson");
        }
        try {
            return MAPPER.readValue(binding.getDesiredSpecJson(), LakeCatalogDesiredSpec.class);
        } catch (JsonProcessingException exception) {
            throw invalid("desiredSpecJson");
        }
    }

    /** Local page: filters and rows are sourced exclusively from MySQL. */
    public PaginationResult<LakeExternalCatalogVO> page(LakeExternalCatalogPageDTO request) {
        LakeExternalCatalogPageDTO safe = request == null
                ? new LakeExternalCatalogPageDTO() : request;
        int pageNo = safe.getPageNo() == null || safe.getPageNo() < 1
                ? 1 : safe.getPageNo();
        int pageSize = safe.getPageSize() == null || safe.getPageSize() < 1
                ? 10 : Math.min(1_000, safe.getPageSize());
        String adapter = normalizeAdapterFilter(safe.getAdapter());
        String resourceStatus = normalizeFilter(safe.getResourceStatus());
        String validationStatus = normalizeFilter(safe.getValidationStatus());
        IPage<LakeExternalCatalogBinding> page = bindingDao.queryActivePage(
                new Page<>(pageNo, pageSize), safe.getLakeDataSourceId(),
                safe.getSourceDataSourceId(), safe.getTargetCatalogName(), adapter,
                resourceStatus, validationStatus);
        List<LakeExternalCatalogVO> rows = page.getRecords() == null
                ? List.of() : page.getRecords().stream().map(this::toVO).toList();
        return PaginationResult.buildSuc(rows, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * Low-level TX update primitive for callers that already hold an entity
     * snapshot.  The row is claimed with the supplied token and version.
     */
    @Transactional
    public boolean updatePending(
            LakeExternalCatalogBinding entity, String operationToken, Integer expectedLockVersion) {
        if (entity == null || entity.getId() == null || expectedLockVersion == null) {
            return false;
        }
        String previousOperationToken = entity.getOperationToken();
        entity.setOperationToken(operationToken);
        entity.setResourceStatus(LakeResourceStatus.CREATING);
        entity.setDeleted(false);
        entity.initUpdate();
        // The DAO token argument is the token currently stored in the row.  The
        // supplied token is the new lease being claimed, so pass the snapshot's
        // previous token to the CAS predicate.
        boolean updated = bindingDao.updateIfTokenAndVersion(
                entity, previousOperationToken, expectedLockVersion);
        if (updated) {
            entity.setLockVersion(expectedLockVersion + 1);
        }
        return updated;
    }

    /** Finalizes a non-delete external result using token/version CAS. */
    @Transactional
    public LakeExternalCatalogVO finalizeSuccess(
            Long id,
            String operationToken,
            Integer expectedLockVersion,
            String actualSnapshotJson,
            String validationStatus) {
        return finalizeSuccess(id, operationToken, expectedLockVersion,
                actualSnapshotJson, validationStatus, false);
    }

    /** Finalizes a result and retains a tombstone when {@code deleted=true}. */
    @Transactional
    public LakeExternalCatalogVO finalizeSuccess(
            Long id,
            String operationToken,
            Integer expectedLockVersion,
            String actualSnapshotJson,
            String validationStatus,
            boolean deleted) {
        LakeExternalCatalogBinding binding = requireIncludingDeleted(id);
        if (!Objects.equals(binding.getLockVersion(), expectedLockVersion)) {
            throw casFailed();
        }
        binding.setOperationToken(null);
        binding.setResourceStatus(deleted ? LakeResourceStatus.DELETED : LakeResourceStatus.READY);
        binding.setDeleted(deleted);
        binding.setActualSnapshotJson(safeSnapshotJson(actualSnapshotJson));
        binding.setValidationStatus(normalizeValidationStatus(validationStatus));
        binding.setLastObservedAt(new Date());
        binding.setLastReconcileAt(new Date());
        binding.setErrorCode(null);
        binding.setErrorMessage(null);
        binding.initUpdate();
        if (!bindingDao.updateIfTokenAndVersionIncludingDeleted(
                binding, operationToken, expectedLockVersion)) {
            throw casFailed();
        }
        return toVO(afterSuccessfulCas(binding, expectedLockVersion));
    }

    /** Finalizes a failed external result without persisting raw exception text. */
    @Transactional
    public LakeExternalCatalogVO finalizeFailure(
            Long id,
            String operationToken,
            Integer expectedLockVersion,
            String errorCode,
            String errorMessage,
            String actualSnapshotJson,
            String validationStatus) {
        LakeExternalCatalogBinding binding = requireIncludingDeleted(id);
        if (!Objects.equals(binding.getLockVersion(), expectedLockVersion)) {
            throw casFailed();
        }
        String safeCode = safeErrorCode(errorCode);
        binding.setOperationToken(null);
        binding.setResourceStatus(failureStatus(safeCode));
        binding.setDeleted(Boolean.TRUE.equals(binding.getDeleted()));
        binding.setErrorCode(safeCode);
        binding.setErrorMessage(safeErrorMessage(errorMessage));
        binding.setActualSnapshotJson(safeSnapshotJson(actualSnapshotJson));
        binding.setValidationStatus(normalizeValidationStatus(validationStatus));
        binding.setLastObservedAt(new Date());
        binding.setLastReconcileAt(new Date());
        binding.initUpdate();
        if (!bindingDao.updateIfTokenAndVersionIncludingDeleted(
                binding, operationToken, expectedLockVersion)) {
            throw casFailed();
        }
        return toVO(afterSuccessfulCas(binding, expectedLockVersion));
    }

    /** Converts one row into the secret-safe local read model. */
    public LakeExternalCatalogVO toVO(LakeExternalCatalogBinding binding) {
        if (binding == null) {
            return null;
        }
        LakeExternalCatalogVO result = new LakeExternalCatalogVO();
        result.setId(binding.getId());
        result.setLakeDataSourceId(binding.getLakeDataSourceId());
        result.setSourceDataSourceId(binding.getSourceDataSourceId());
        result.setTargetCatalogName(binding.getTargetCatalogName());
        result.setAdapter(binding.getAdapter());
        result.setScope(binding.getScope());
        LakeCatalogDesiredSpec desired = readDesiredSpec(binding.getDesiredSpecJson());
        if (desired != null) {
            result.setDatabaseInclude(desired.databaseInclude());
            result.setTableInclude(desired.tableInclude());
        }
        result.setDesiredSpecHash(binding.getDesiredSpecHash());
        // credential_revision is a retained historical column.  New reads do
        // not expose or depend on the old feature-specific credential token.
        result.setDriverChecksum(binding.getDriverChecksum());
        result.setValidationStatus(binding.getValidationStatus());
        result.setResourceStatus(binding.getResourceStatus());
        result.setGeneration(binding.getGeneration());
        result.setLockVersion(binding.getLockVersion());
        result.setErrorCode(binding.getErrorCode());
        result.setErrorMessage(safeErrorMessage(binding.getErrorMessage()));
        result.setActualSnapshot(safeSnapshot(binding.getActualSnapshotJson()));
        result.setLastObservedAt(binding.getLastObservedAt());
        result.setLastReconcileAt(binding.getLastReconcileAt());
        result.setCreateUserId(binding.getCreateUserId());
        result.setUpdateUserId(binding.getUpdateUserId());
        result.setDeleted(binding.getDeleted());
        result.setCreateTime(binding.getCreateTime());
        result.setUpdateTime(binding.getUpdateTime());
        return result;
    }

    private static LakeCatalogDesiredSpec readDesiredSpec(String desiredSpecJson) {
        if (StringUtils.isBlank(desiredSpecJson)) {
            return null;
        }
        try {
            return MAPPER.readValue(desiredSpecJson, LakeCatalogDesiredSpec.class);
        } catch (JsonProcessingException exception) {
            // An invalid historical desired spec must not make a local GET fail.
            return null;
        }
    }

    private LakeExternalCatalogBinding candidate(
            LakeExternalCatalogCreateDTO request, Integer operatorId) {
        if (request == null || request.getSourceDataSourceId() == null
                || request.getSourceDataSourceId() <= 0) {
            throw invalid("sourceDataSourceId");
        }
        Long lakeDataSourceId = canonicalLakeDataSourceId(request.getLakeDataSourceId());
        if (lakeDataSourceId == null || lakeDataSourceId <= 0) {
            throw invalid("lakeDataSourceId");
        }
        String catalogName = normalizeCatalogName(request.getTargetCatalogName());
        LakeJdbcAdapterType adapter = normalizeAdapter(request.getAdapter());
        LakeCatalogScope scope = request.getScope();
        if (scope == null) {
            throw invalid("scope");
        }
        String desiredJson = desiredJson(request.getDesiredSpecJson(), catalogName,
                adapter, scope, request.getDatabaseInclude(), request.getTableInclude(),
                request.getOptions());
        LakeExternalCatalogBinding result = new LakeExternalCatalogBinding();
        result.setLakeDataSourceId(lakeDataSourceId);
        result.setSourceDataSourceId(request.getSourceDataSourceId());
        result.setTargetCatalogName(catalogName);
        result.setAdapter(adapter.code());
        result.setScope(scope);
        result.setDesiredSpecJson(desiredJson);
        result.setDesiredSpecHash(hash(request.getDesiredSpecHash(), desiredJson));
        // Historical credentialRevision input is intentionally ignored.  The
        // current source configuration is resolved at execution time.
        result.setDriverChecksum(checksum(request.getDriverChecksum()));
        result.setValidationStatus(null);
        result.setActualSnapshotJson(null);
        result.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
        result.setLockVersion(1);
        result.setGeneration(1);
        result.setOperationToken(null);
        result.setDeleted(false);
        result.setCreateUserId(operatorId);
        result.setUpdateUserId(operatorId);
        return result;
    }

    private Long canonicalLakeDataSourceId(Long requestedId) {
        if (warehouseService != null) {
            try {
                return warehouseService.canonicalDataSourceId(requestedId);
            } catch (RuntimeException exception) {
                throw invalid("lakeDataSourceId");
            }
        }
        return requestedId == null ? lakeProperties.getDataSourceId() : requestedId;
    }

    private LakeExternalCatalogBinding copyUpdate(
            LakeExternalCatalogBinding current,
            LakeExternalCatalogUpdateDTO request,
            Integer operatorId) {
        String catalogName = normalizeCatalogName(request.getTargetCatalogName());
        LakeJdbcAdapterType adapter = normalizeAdapter(request.getAdapter());
        LakeCatalogScope scope = request.getScope();
        if (scope == null) {
            throw invalid("scope");
        }
        String desiredJson = desiredJson(request.getDesiredSpecJson(), catalogName,
                adapter, scope, request.getDatabaseInclude(), request.getTableInclude(),
                request.getOptions());
        current.setTargetCatalogName(catalogName);
        current.setAdapter(adapter.code());
        current.setScope(scope);
        current.setDesiredSpecJson(desiredJson);
        current.setDesiredSpecHash(hash(request.getDesiredSpecHash(), desiredJson));
        // Do not read or write the retained historical credential revision.
        current.setDriverChecksum(checksum(request.getDriverChecksum()));
        current.setValidationStatus(null);
        current.setActualSnapshotJson(null);
        current.setResourceStatus(LakeResourceStatus.CREATING);
        current.setErrorCode(null);
        current.setErrorMessage(null);
        current.setLastObservedAt(null);
        current.setLastReconcileAt(null);
        current.setUpdateUserId(operatorId);
        current.setDeleted(false);
        return current;
    }

    private LakeExternalCatalogBinding reopen(
            LakeExternalCatalogBinding existing,
            LakeExternalCatalogBinding candidate,
            Integer operatorId) {
        Integer expectedVersion = existing.getLockVersion() == null
                ? 1 : existing.getLockVersion();
        candidate.setId(existing.getId());
        candidate.setLockVersion(expectedVersion);
        candidate.setGeneration((existing.getGeneration() == null
                ? 1 : existing.getGeneration()) + 1);
        candidate.setCreateTime(existing.getCreateTime());
        candidate.setCreateUserId(existing.getCreateUserId() == null
                ? operatorId : existing.getCreateUserId());
        candidate.setOperationToken(null);
        candidate.setDeleted(false);
        candidate.initUpdate();
        if (!bindingDao.updateIfTokenAndVersionIncludingDeleted(
                candidate, null, expectedVersion)) {
            throw casFailed();
        }
        return afterSuccessfulCas(candidate, expectedVersion);
    }

    private static void defaults(LakeExternalCatalogBinding entity, Integer operatorId) {
        if (entity.getLockVersion() == null) {
            entity.setLockVersion(1);
        }
        if (entity.getGeneration() == null) {
            entity.setGeneration(1);
        }
        if (entity.getResourceStatus() == null) {
            entity.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
        }
        if (entity.getDeleted() == null) {
            entity.setDeleted(false);
        }
        entity.setCreateUserId(operatorId);
        entity.setUpdateUserId(operatorId);
        entity.setOperationToken(null);
    }

    private static String desiredJson(
            String supplied,
            String catalogName,
            LakeJdbcAdapterType adapter,
            LakeCatalogScope scope,
            List<String> databases,
            List<String> tables,
            Map<String, String> options) {
        if (StringUtils.isNotBlank(supplied)) {
            return sanitizeDesiredJson(supplied);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("adapter", adapter.code());
        root.put("catalogName", catalogName);
        root.put("databaseInclude", databases == null ? List.of() : List.copyOf(databases));
        root.put("options", safeOptions(options));
        root.put("scope", scope.name());
        root.put("tableInclude", tables == null ? List.of() : List.copyOf(tables));
        root.put("type", "jdbc");
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw invalid("desiredSpecJson");
        }
    }

    private static Map<String, String> safeOptions(Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (entry.getKey() == null || CatalogPropertyRedactor.isSensitiveKey(entry.getKey())) {
                throw invalid("options");
            }
            safe.put(entry.getKey(), CatalogPropertyRedactor.redactText(entry.getValue()));
        }
        return safe;
    }

    private static String sanitizeDesiredJson(String supplied) {
        try {
            Object parsed = MAPPER.readValue(supplied, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw invalid("desiredSpecJson");
            }
            Object safe = sanitizeDesiredValue(null, map);
            return MAPPER.writeValueAsString(safe);
        } catch (JsonProcessingException exception) {
            throw invalid("desiredSpecJson");
        }
    }

    private static Object sanitizeDesiredValue(String key, Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                String normalized = compact(childKey);
                // These are non-secret identity facts, but their values still
                // pass through text redaction in case credentials were embedded.
                boolean preserveSafeIdentity = normalized.equals("jdbcurl")
                        || normalized.equals("driverurl")
                        || normalized.equals("credentialrevision")
                        || normalized.equals("driverchecksum");
                if (!preserveSafeIdentity && (CatalogPropertyRedactor.isSensitiveKey(childKey)
                        || normalized.equals("user") || normalized.equals("username"))) {
                    continue;
                }
                Object child = sanitizeDesiredValue(childKey, entry.getValue());
                if (child != null) {
                    safe.put(childKey, child);
                }
            }
            return safe;
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>(list.size());
            for (Object item : list) {
                safe.add(sanitizeDesiredValue(key, item));
            }
            return safe;
        }
        if (value instanceof String text) {
            return CatalogPropertyRedactor.redactText(text);
        }
        return value;
    }

    private static String safeSnapshotJson(String supplied) {
        if (StringUtils.isBlank(supplied)) {
            return null;
        }
        try {
            Object parsed = MAPPER.readValue(supplied, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                return null;
            }
            return MAPPER.writeValueAsString(sanitizeSnapshotValue(null, map));
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static Map<String, Object> safeSnapshot(String supplied) {
        if (StringUtils.isBlank(supplied)) {
            return Map.of();
        }
        try {
            Object parsed = MAPPER.readValue(supplied, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Object safe = sanitizeSnapshotValue(null, map);
            if (safe instanceof Map<?, ?> result) {
                Map<String, Object> copy = new LinkedHashMap<>();
                result.forEach((key, value) -> copy.put(String.valueOf(key), value));
                return copy;
            }
        } catch (JsonProcessingException ignored) {
            // A corrupt observation is represented as an empty local snapshot.
        }
        return Map.of();
    }

    private static Object sanitizeSnapshotValue(String key, Object value) {
        if (key != null && CatalogPropertyRedactor.isSensitiveKey(key)) {
            return CatalogPropertyRedactor.MASK;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                safe.put(String.valueOf(entry.getKey()),
                        sanitizeSnapshotValue(String.valueOf(entry.getKey()), entry.getValue()));
            }
            return safe;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> sanitizeSnapshotValue(key, item)).toList();
        }
        if (value instanceof String text) {
            String redacted = CatalogPropertyRedactor.redactText(text);
            String lower = redacted.toLowerCase(Locale.ROOT);
            return lower.contains("jdbc:") || lower.contains("eyj")
                    ? CatalogPropertyRedactor.MASK : redacted;
        }
        return value;
    }

    private static String hash(String requested, String desiredJson) {
        if (StringUtils.isBlank(requested)) {
            return org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpecCanonicalizer
                    .sha256(desiredJson);
        }
        if (!SHA256.matcher(requested.trim()).matches()) {
            throw invalid("desiredSpecHash");
        }
        return requested.trim().toLowerCase(Locale.ROOT);
    }

    private static String checksum(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        if (!SHA256.matcher(value.trim()).matches()) {
            throw invalid("driverChecksum");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeCatalogName(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("targetCatalogName");
        }
        try {
            return DorisIdentifier.normalize(value);
        } catch (RuntimeException exception) {
            throw invalid("targetCatalogName");
        }
    }

    private static LakeJdbcAdapterType normalizeAdapter(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("adapter");
        }
        try {
            return LakeJdbcAdapterType.parse(value);
        } catch (RuntimeException exception) {
            throw invalid("adapter");
        }
    }

    private static String normalizeAdapterFilter(String value) {
        return StringUtils.isBlank(value) ? null : normalizeAdapter(value).code();
    }

    private static String normalizeFilter(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = value.trim();
        if (!SAFE_CODE.matcher(normalized).matches()) {
            throw invalid("page filter");
        }
        return normalized;
    }

    private static String normalizeValidationStatus(String value) {
        return StringUtils.isBlank(value) ? null : normalizeFilter(value);
    }

    private static String safeErrorCode(String value) {
        if (StringUtils.isBlank(value) || !SAFE_CODE.matcher(value.trim()).matches()) {
            return LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID;
        }
        return value.trim();
    }

    private static String safeErrorMessage(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String redacted = CatalogPropertyRedactor.redactText(value.trim());
        String lower = redacted.toLowerCase(Locale.ROOT);
        if (lower.contains("jdbc:") || lower.contains("http://")
                || lower.contains("https://") || lower.contains("sqlexception")
                || lower.contains("password") || lower.contains("token=")) {
            return REDACTED_ERROR;
        }
        return redacted.length() <= MAX_ERROR_LENGTH
                ? redacted : redacted.substring(0, MAX_ERROR_LENGTH);
    }

    private static LakeResourceStatus failureStatus(String errorCode) {
        String normalized = errorCode.toUpperCase(Locale.ROOT);
        if (normalized.contains("MISSING")) {
            return LakeResourceStatus.MISSING;
        }
        if (normalized.contains("UNKNOWN") || normalized.contains("UNAVAILABLE")) {
            return LakeResourceStatus.UNKNOWN;
        }
        return LakeResourceStatus.ERROR;
    }

    /**
     * MyBatis CAS updates the SQL row's version but does not guarantee that a
     * custom mapper mutates the caller's entity.  Prefer a fresh local read and
     * retain the deterministic next version as a fallback for lightweight DAO
     * implementations and unit-test doubles.
     */
    private LakeExternalCatalogBinding afterSuccessfulCas(
            LakeExternalCatalogBinding entity, Integer expectedLockVersion) {
        int nextVersion = expectedLockVersion + 1;
        LakeExternalCatalogBinding fresh = bindingDao.queryByIdIncludingDeleted(entity.getId());
        if (fresh != null && fresh.getLockVersion() != null
                && fresh.getLockVersion() >= nextVersion) {
            return fresh;
        }
        entity.setLockVersion(nextVersion);
        return entity;
    }

    private LakeExternalCatalogBinding requireIncludingDeleted(Long id) {
        if (id == null || id <= 0) {
            throw notFound();
        }
        LakeExternalCatalogBinding binding = bindingDao.queryByIdIncludingDeleted(id);
        if (binding == null) {
            throw notFound();
        }
        return binding;
    }

    private static LakeServiceException invalid(String field) {
        return new LakeServiceException(LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID,
                "Catalog request is invalid: " + field);
    }

    private static LakeServiceException conflict(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_CATALOG_CONFLICT, message);
    }

    private static LakeServiceException notFound() {
        return new LakeServiceException(LakeErrorCode.LAKE_CATALOG_NOT_FOUND,
                "Catalog binding does not exist");
    }

    private static LakeServiceException casFailed() {
        return new LakeServiceException(LakeErrorCode.LAKE_CATALOG_CAS_FAILED,
                "Catalog binding changed concurrently");
    }

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[_\\-. ]", "");
    }
}
