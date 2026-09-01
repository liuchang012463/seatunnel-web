package org.apache.seatunnel.web.api.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.LakeDataSourceResolver;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpec;
import org.apache.seatunnel.web.api.lake.doris.DorisColumnMetadata;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.query.LakeQueryColumnAllowlist;
import org.apache.seatunnel.web.api.lake.query.LakeQueryColumnMetadata;
import org.apache.seatunnel.web.api.lake.query.LakeQueryColumnOptionVO;
import org.apache.seatunnel.web.api.lake.query.LakeQueryErrorCode;
import org.apache.seatunnel.web.api.lake.query.LakeQueryExecutionException;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryExecutor;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryPlanNormalizer;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryProperties;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryPlan;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryPreviewVO;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryResultVO;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQuerySqlGenerator;
import org.apache.seatunnel.web.api.lake.query.LakeQueryValidationException;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.LakeLogicalCatalogQueryService;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeExternalCatalogBinding;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.repository.LakeExternalCatalogBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeJoinQueryDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeQueryTableIdentityDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeSingleTableQueryDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves only server-side catalog metadata and executes through the
 * dedicated bounded read-only JDBC pool.  Every request is recorded in the
 * existing operation journal without persisting SQL, rows or credentials.
 */
@Service
public class LakeLogicalCatalogQueryServiceImpl implements LakeLogicalCatalogQueryService {

    private final LakeExternalCatalogBindingDao bindingDao;
    private final LakeExternalCatalogBindingPersistenceService persistenceService;
    private final LakeDorisClientProvider dorisClientProvider;
    private final LakeDataSourceResolver dataSourceResolver;
    private final LakeReadOnlyQueryProperties queryProperties;
    private final LakeResourceOperationDao operationDao;
    private final CurrentUserProvider currentUserProvider;

    @Autowired
    public LakeLogicalCatalogQueryServiceImpl(
            LakeExternalCatalogBindingDao bindingDao,
            LakeExternalCatalogBindingPersistenceService persistenceService,
            LakeDorisClientProvider dorisClientProvider,
            LakeDataSourceResolver dataSourceResolver,
            LakeReadOnlyQueryProperties queryProperties,
            LakeResourceOperationDao operationDao,
            CurrentUserProvider currentUserProvider) {
        this.bindingDao = Objects.requireNonNull(bindingDao, "bindingDao");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.dataSourceResolver = Objects.requireNonNull(dataSourceResolver, "dataSourceResolver");
        this.queryProperties = Objects.requireNonNull(queryProperties, "queryProperties");
        this.operationDao = Objects.requireNonNull(operationDao, "operationDao");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
    }

    @Override
    public LakeReadOnlyQueryResultVO single(
            Long catalogBindingId, LakeSingleTableQueryDTO request) {
        LakeExternalCatalogBinding binding = activeBinding(catalogBindingId);
        Integer operatorId = currentUserId();
        LakeResourceOperation audit = startAudit(binding.getId(), operatorId, "SINGLE");
        try {
            LakeQueryTableIdentityDTO table = request == null ? null : request.table();
            requireCatalog(table, binding.getTargetCatalogName());
            requireScope(desired(binding), table);
            try (DorisLakeClient metadataClient = dorisClientProvider.get(binding.getLakeDataSourceId())) {
                LakeQueryColumnAllowlist allowlist = allowlist(metadataClient, table);
                DataSource dataSource = dataSourceResolver.resolve(binding.getLakeDataSourceId());
                LakeReadOnlyQueryExecutor executor = new LakeReadOnlyQueryExecutor(
                        dataSource, queryProperties);
                LakeReadOnlyQueryResultVO result = executor.execute(
                        normalizer()
                                .normalize(request, allowlist));
                finishAudit(audit, "Structured single-table query completed", null);
                return result;
            }
        } catch (LakeQueryExecutionException exception) {
            failAudit(audit, exception.errorCode());
            throw stableQueryFailure(exception);
        } catch (RuntimeException exception) {
            failAudit(audit, queryErrorCode(exception));
            throw stableQueryFailure(exception);
        }
    }

    @Override
    public LakeReadOnlyQueryResultVO join(LakeJoinQueryDTO request) {
        LakeQueryTableIdentityDTO left = request == null ? null : request.leftTable();
        LakeQueryTableIdentityDTO right = request == null ? null : request.rightTable();
        LakeExternalCatalogBinding leftBinding = activeBindingByName(left);
        LakeExternalCatalogBinding rightBinding = activeBindingByName(right);
        Integer operatorId = currentUserId();
        LakeResourceOperation audit = startAudit(leftBinding.getId(), operatorId, "JOIN");
        try {
            if (Objects.equals(leftBinding.getLakeDataSourceId(), rightBinding.getLakeDataSourceId())
                    && leftBinding.getId().equals(rightBinding.getId())) {
                throw queryInvalid("join catalogs must be distinct");
            }
            requireScope(desired(leftBinding), left);
            requireScope(desired(rightBinding), right);
            try (DorisLakeClient metadataClient = dorisClientProvider.get(leftBinding.getLakeDataSourceId())) {
                LakeQueryColumnAllowlist leftAllowlist = allowlist(metadataClient, left);
                LakeQueryColumnAllowlist rightAllowlist = allowlist(metadataClient, right);
                if (!Objects.equals(leftBinding.getLakeDataSourceId(), rightBinding.getLakeDataSourceId())) {
                    throw queryInvalid("join catalogs must use one configured lake datasource");
                }
                DataSource dataSource = dataSourceResolver.resolve(leftBinding.getLakeDataSourceId());
                LakeReadOnlyQueryExecutor executor = new LakeReadOnlyQueryExecutor(
                        dataSource, queryProperties);
                LakeReadOnlyQueryResultVO result = executor.execute(
                        normalizer()
                                .normalize(request, leftAllowlist, rightAllowlist));
                finishAudit(audit, "Structured equality JOIN query completed", null);
                return result;
            }
        } catch (LakeQueryExecutionException exception) {
            failAudit(audit, exception.errorCode());
            throw stableQueryFailure(exception);
        } catch (RuntimeException exception) {
            failAudit(audit, queryErrorCode(exception));
            if (exception instanceof LakeServiceException serviceException) {
                throw serviceException;
            }
            throw stableQueryFailure(exception);
        }
    }

    @Override
    public LakeReadOnlyQueryPreviewVO previewSingle(
            Long catalogBindingId, LakeSingleTableQueryDTO request) {
        LakeExternalCatalogBinding binding = activeBinding(catalogBindingId);
        try {
            LakeQueryTableIdentityDTO table = request == null ? null : request.table();
            requireCatalog(table, binding.getTargetCatalogName());
            requireScope(desired(binding), table);
            try (DorisLakeClient metadataClient = dorisClientProvider.get(binding.getLakeDataSourceId())) {
                LakeReadOnlyQueryPlan plan = normalizer().normalize(
                        request, allowlist(metadataClient, table));
                return LakeReadOnlyQueryPreviewVO.from(
                        plan, new LakeReadOnlyQuerySqlGenerator(normalizer().maxRows()).generate(plan));
            }
        } catch (RuntimeException exception) {
            throw stableQueryFailure(exception);
        }
    }

    @Override
    public LakeReadOnlyQueryPreviewVO previewJoin(LakeJoinQueryDTO request) {
        LakeQueryTableIdentityDTO left = request == null ? null : request.leftTable();
        LakeQueryTableIdentityDTO right = request == null ? null : request.rightTable();
        LakeExternalCatalogBinding leftBinding = activeBindingByName(left);
        LakeExternalCatalogBinding rightBinding = activeBindingByName(right);
        try {
            if (Objects.equals(leftBinding.getLakeDataSourceId(), rightBinding.getLakeDataSourceId())
                    && leftBinding.getId().equals(rightBinding.getId())) {
                throw queryInvalid("join catalogs must be distinct");
            }
            requireScope(desired(leftBinding), left);
            requireScope(desired(rightBinding), right);
            if (!Objects.equals(leftBinding.getLakeDataSourceId(), rightBinding.getLakeDataSourceId())) {
                throw queryInvalid("join catalogs must use one configured lake datasource");
            }
            try (DorisLakeClient metadataClient = dorisClientProvider.get(leftBinding.getLakeDataSourceId())) {
                LakeReadOnlyQueryPlan plan = normalizer().normalize(
                        request, allowlist(metadataClient, left), allowlist(metadataClient, right));
                return LakeReadOnlyQueryPreviewVO.from(
                        plan, new LakeReadOnlyQuerySqlGenerator(normalizer().maxRows()).generate(plan));
            }
        } catch (RuntimeException exception) {
            throw stableQueryFailure(exception);
        }
    }

    @Override
    public List<String> databases(Long catalogBindingId) {
        LakeExternalCatalogBinding binding = activeBinding(catalogBindingId);
        try (DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId())) {
            List<String> databases = client.listCatalogDatabases(binding.getTargetCatalogName());
            LakeCatalogDesiredSpec desired = desired(binding);
            if (desired.scope() == org.apache.seatunnel.web.common.enums.LakeCatalogScope.ALL) {
                return List.copyOf(databases);
            }
            if (desired.scope() == org.apache.seatunnel.web.common.enums.LakeCatalogScope.DATABASE) {
                return databases.stream().filter(desired.databaseInclude()::contains).toList();
            }
            return databases.stream().filter(database -> desired.tableInclude().stream()
                    .anyMatch(include -> include.startsWith(database + "."))).toList();
        } catch (RuntimeException exception) {
            throw stableQueryFailure(exception);
        }
    }

    @Override
    public List<String> tables(Long catalogBindingId, String database) {
        LakeExternalCatalogBinding binding = activeBinding(catalogBindingId);
        String normalizedDatabase = identifier(database, "database");
        try {
            LakeCatalogDesiredSpec desired = desired(binding);
            if (desired.scope() == org.apache.seatunnel.web.common.enums.LakeCatalogScope.DATABASE
                    && !desired.databaseInclude().contains(normalizedDatabase)) {
                throw queryInvalid("database is outside the catalog scope");
            }
            try (DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId())) {
                List<String> tables = client.listCatalogTables(
                        binding.getTargetCatalogName(), normalizedDatabase);
                if (desired.scope() != org.apache.seatunnel.web.common.enums.LakeCatalogScope.TABLE) {
                    return List.copyOf(tables);
                }
                return tables.stream()
                        .filter(name -> desired.tableInclude().contains(normalizedDatabase + "." + name)
                                || desired.tableInclude().contains(name))
                        .toList();
            }
        } catch (RuntimeException exception) {
            throw stableQueryFailure(exception);
        }
    }

    @Override
    public List<LakeQueryColumnOptionVO> columns(
            Long catalogBindingId, String database, String table) {
        LakeExternalCatalogBinding binding = activeBinding(catalogBindingId);
        String normalizedDatabase = identifier(database, "database");
        String normalizedTable = identifier(table, "table");
        LakeQueryTableIdentityDTO identity = new LakeQueryTableIdentityDTO(
                binding.getTargetCatalogName(), normalizedDatabase, normalizedTable);
        try {
            requireScope(desired(binding), identity);
            try (DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId())) {
                List<DorisColumnMetadata> metadata = client.listCatalogColumns(
                        identity.catalog(), identity.database(), identity.table());
                return columnOptions(metadata);
            }
        } catch (RuntimeException exception) {
            throw stableQueryFailure(exception);
        }
    }

    private LakeExternalCatalogBinding activeBinding(Long id) {
        if (id == null || id <= 0) {
            throw queryInvalid("catalog binding is required");
        }
        LakeExternalCatalogBinding binding = bindingDao.queryActiveById(id);
        if (binding == null || Boolean.TRUE.equals(binding.getDeleted())
                || binding.getResourceStatus() != LakeResourceStatus.READY) {
            throw new LakeServiceException(LakeErrorCode.LAKE_CATALOG_NOT_FOUND,
                    "Catalog binding is not ready for read-only query");
        }
        return binding;
    }

    private LakeExternalCatalogBinding activeBindingByName(LakeQueryTableIdentityDTO table) {
        if (table == null || StringUtils.isBlank(table.catalog())) {
            throw queryInvalid("catalog is required");
        }
        String catalog;
        try {
            catalog = DorisIdentifier.normalize(table.catalog());
        } catch (RuntimeException exception) {
            throw queryInvalid("catalog identifier is invalid");
        }
        // A join is resolved against the configured lake datasource only; the
        // actual lake datasource is checked after both identities are loaded.
        List<LakeExternalCatalogBinding> candidates = bindingDao.queryActivePage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 1000),
                null, null, catalog, null, null, null).getRecords();
        LakeExternalCatalogBinding binding = candidates == null ? null : candidates.stream()
                .filter(value -> catalog.equals(value.getTargetCatalogName()))
                .findFirst().orElse(null);
        if (binding == null || Boolean.TRUE.equals(binding.getDeleted())
                || binding.getResourceStatus() != LakeResourceStatus.READY) {
            throw new LakeServiceException(LakeErrorCode.LAKE_CATALOG_NOT_FOUND,
                    "Catalog binding is not ready for read-only query");
        }
        return binding;
    }

    private LakeCatalogDesiredSpec desired(LakeExternalCatalogBinding binding) {
        try {
            return org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpecValidator
                    .validateAndNormalize(persistenceService.desiredSpec(binding.getId()));
        } catch (RuntimeException exception) {
            throw queryInvalid("catalog desired state is invalid");
        }
    }

    private static void requireCatalog(LakeQueryTableIdentityDTO table, String expected) {
        if (table == null || StringUtils.isBlank(table.catalog())) {
            throw queryInvalid("catalog is required");
        }
        String expectedCatalog;
        String requestedCatalog;
        try {
            expectedCatalog = DorisIdentifier.normalize(expected);
            requestedCatalog = DorisIdentifier.normalize(table.catalog());
        } catch (RuntimeException exception) {
            throw queryInvalid("catalog identifier is invalid");
        }
        if (!expectedCatalog.equals(requestedCatalog)) {
            throw queryInvalid("catalog binding does not match request");
        }
    }

    private static void requireScope(LakeCatalogDesiredSpec desired, LakeQueryTableIdentityDTO table) {
        if (table == null || StringUtils.isBlank(table.database()) || StringUtils.isBlank(table.table())) {
            throw queryInvalid("database and table are required");
        }
        switch (desired.scope()) {
            case ALL -> { }
            case DATABASE -> {
                if (!desired.databaseInclude().contains(table.database())) {
                    throw queryInvalid("database is outside the catalog scope");
                }
            }
            case TABLE -> {
                String qualified = table.database() + "." + table.table();
                if (!desired.tableInclude().contains(qualified)
                        && !desired.tableInclude().contains(table.table())) {
                    throw queryInvalid("table is outside the catalog scope");
                }
            }
        }
    }

    private static LakeQueryColumnAllowlist allowlist(
            DorisLakeClient client, LakeQueryTableIdentityDTO table) {
        List<DorisColumnMetadata> metadata;
        try {
            metadata = client.listCatalogColumns(table.catalog(), table.database(), table.table());
        } catch (RuntimeException exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Catalog metadata is unavailable");
        }
        if (metadata == null || metadata.isEmpty()) {
            throw queryInvalid("catalog table has no selectable metadata");
        }
        List<LakeQueryColumnMetadata> columns = new ArrayList<>();
        for (DorisColumnMetadata column : metadata) {
            String name = column == null ? null : column.name();
            if (StringUtils.isBlank(name)) {
                continue;
            }
            String type = column.type() == null ? "" : column.type().toLowerCase(Locale.ROOT);
            boolean sensitive = name.toLowerCase(Locale.ROOT)
                    .matches(".*(password|passwd|secret|token|private[_-]?key|access[_-]?key).*");
            boolean selectable = !type.contains("blob") && !type.contains("binary")
                    && !type.contains("geometry") && !type.contains("variant");
            columns.add(new LakeQueryColumnMetadata(name, selectable, sensitive));
        }
        return new LakeQueryColumnAllowlist(columns);
    }

    private static List<LakeQueryColumnOptionVO> columnOptions(List<DorisColumnMetadata> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            throw queryInvalid("catalog table has no selectable metadata");
        }
        List<LakeQueryColumnOptionVO> options = new ArrayList<>();
        for (DorisColumnMetadata column : metadata) {
            if (column == null || StringUtils.isBlank(column.name())) {
                continue;
            }
            String name = column.name().trim();
            String type = column.type() == null ? "" : column.type().trim();
            boolean sensitive = name.toLowerCase(Locale.ROOT)
                    .matches(".*(password|passwd|secret|token|private[_-]?key|access[_-]?key).*");
            boolean selectable = !type.toLowerCase(Locale.ROOT).matches(".*(blob|binary|geometry|variant).*")
                    && !sensitive;
            String reason = sensitive ? "敏感字段不可查询"
                    : selectable ? null : "当前类型不支持只读结果";
            options.add(new LakeQueryColumnOptionVO(
                    name, type, column.nullable(), selectable, reason));
        }
        return List.copyOf(options);
    }

    private static String identifier(String value, String label) {
        if (StringUtils.isBlank(value)) {
            throw queryInvalid(label + " is required");
        }
        try {
            return org.apache.seatunnel.web.api.lake.query.LakeQueryIdentifier.validate(value.trim());
        } catch (RuntimeException exception) {
            throw queryInvalid(label + " identifier is invalid");
        }
    }

    private LakeReadOnlyQueryPlanNormalizer normalizer() {
        long configured = queryProperties.getMaxRows();
        int maxRows = configured > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) configured;
        return new LakeReadOnlyQueryPlanNormalizer(maxRows);
    }

    private LakeResourceOperation startAudit(Long resourceId, Integer operatorId, String kind) {
        LakeResourceOperation operation = new LakeResourceOperation();
        operation.initInsert();
        operation.setResourceType("READONLY_QUERY");
        operation.setResourceId(resourceId);
        operation.setGeneration(1);
        operation.setOperationType(LakeOperationType.READONLY_QUERY);
        String token = UUID.randomUUID().toString();
        operation.setOperationToken(token);
        operation.setRequestHash(sha256("READONLY_QUERY\u0000" + kind + "\u0000" + resourceId));
        operation.setStatus(LakeOperationStatus.PENDING);
        operation.setStartedAt(new java.util.Date());
        operation.setOperatorId(operatorId);
        if (operationDao.insert(operation) <= 0) {
            throw queryInvalid("query audit could not be persisted");
        }
        return operation;
    }

    private void finishAudit(LakeResourceOperation operation, String summary, String code) {
        operationDao.updateStatusIfToken(
                operation.getId(), operation.getOperationToken(), LakeOperationStatus.SUCCEEDED,
                code, summary);
    }

    private void failAudit(LakeResourceOperation operation, String code) {
        if (operation != null) {
            operationDao.updateStatusIfToken(
                    operation.getId(), operation.getOperationToken(), LakeOperationStatus.FAILED,
                    code, "Structured read-only query failed");
        }
    }

    private Integer currentUserId() {
        Integer id = currentUserProvider.getCurrentUserId();
        if (id == null || id <= 0) {
            throw queryInvalid("authenticated user is required");
        }
        return id;
    }

    private static LakeServiceException stableQueryFailure(RuntimeException exception) {
        if (exception instanceof LakeServiceException serviceException) {
            return serviceException;
        }
        if (exception instanceof LakeQueryValidationException validationException) {
            return new LakeServiceException(
                    LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID, validationException.code().code());
        }
        if (exception instanceof LakeQueryExecutionException executionException) {
            return new LakeServiceException(executionException.errorCode(), executionException.errorCode());
        }
        return new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                "Structured read-only query is unavailable");
    }

    private static String queryErrorCode(RuntimeException exception) {
        if (exception instanceof LakeQueryExecutionException executionException) {
            return executionException.errorCode();
        }
        if (exception instanceof LakeQueryValidationException validationException) {
            return validationException.code().code();
        }
        if (exception instanceof LakeServiceException serviceException) {
            return serviceException.getLakeErrorCode();
        }
        return LakeQueryErrorCode.EXECUTION_FAILED;
    }

    private static LakeServiceException queryInvalid(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_CATALOG_REQUEST_INVALID,
                message == null ? "Structured query request is invalid" : message);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
