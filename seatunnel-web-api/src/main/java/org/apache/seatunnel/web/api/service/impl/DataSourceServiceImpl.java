package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.api.service.DataSourceService;
import org.apache.seatunnel.web.api.service.MetadataBindingCommandService;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.utils.ConvertUtil;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.DBOptionVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataSourceServiceImpl extends BaseServiceImpl implements DataSourceService {

    private static final String UNASSIGNED_LABEL = "待归属";

    private static final long MAX_JDBC_DRIVER_SIZE = 200L * 1024 * 1024;
    private static final String JDBC_DRIVER_DIR = "jdbc-drivers";
    private static final String JDBC_JAR_SUFFIX = ".jar";

    @Resource
    private DataSourceDao dataSourceDao;

    @Resource
    private BusinessSystemDao businessSystemDao;

    @Resource
    private DataSourceUnitDao dataSourceUnitDao;

    @Resource
    private MetadataBindingCommandService metadataBindingCommandService;

    @Resource
    private MetadataBindingDao metadataBindingDao;

    @Resource
    private JobDefinitionDao jobDefinitionDao;

    @Resource
    private StreamingJobDefinitionDao streamingJobDefinitionDao;

    @Resource
    private LakeOdsDatabaseBindingDao lakeOdsDatabaseBindingDao;

    @Resource
    private CurrentUserProvider currentUserProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DataSource createDataSource(DataSourceDTO dto) {
        validateCreateRequest(dto);
        BusinessSystemOwnership ownership = resolveActiveBusinessSystem(dto.getBusinessSystemId());

        try {
            ConnectionParam connectionParam =
                    DataSourceUtils.buildConnectionParams(dto.getDbType(), dto.getConnectionParams());

            DataSourceUtils.checkDatasourceParam(connectionParam);

            checkConnection(dto.getDbType(), connectionParam);

            DataSource entity = ConvertUtil.sourceToTarget(dto, DataSource.class);
            entity.setName(dto.getName().trim());
            entity.setBusinessSystemId(ownership.system().getId());
            // Legacy data_source_unit is nullable. Canonical ownership is kept only in business_system_id.
            entity.setDataSourceUnit(null);
            entity.setConnectionParams(JSONUtils.toJsonString(connectionParam));
            entity.setOriginalJson(dto.getConnectionParams());

            entity.setConnStatus(ConnStatus.CONNECTED_SUCCESS);
            entity.setStatus(DataSourceLifecycleStatus.ENABLED);

            Integer currentUserId = currentUserProvider.getCurrentUserId();
            entity.setCreateUserId(currentUserId);
            entity.setUpdateUserId(currentUserId);

            entity.initInsert();

            dataSourceDao.insert(entity);
            metadataBindingCommandService.createForDataSource(entity.getId());
            return entity;
        } catch (DuplicateKeyException e) {
            log.warn("Create data source failed due to duplicate key, name={}", dto.getName(), e);
            throw new ServiceException(Status.DATASOURCE_EXIST);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Create data source failed, name={}, dbType={}", dto.getName(), dto.getDbType(), e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DataSource updateDataSource(Long id, DataSourceDTO dto) {
        validateId(id);

        DataSource existing = getDataSourceOrThrow(id);
        if (existing.getStatus() == DataSourceLifecycleStatus.REVOKED) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "A data source pending metadata deletion cannot be updated.");
        }
        validateUpdateRequest(id, dto);
        BusinessSystemOwnership ownership = resolveActiveBusinessSystem(dto.getBusinessSystemId());

        try {
            ConnectionParam connectionParam =
                    DataSourceUtils.buildConnectionParams(dto.getDbType(), dto.getConnectionParams());
            DataSourceUtils.checkDatasourceParam(connectionParam);

            // 修改也需要限制，离线任务下次运行时会导致任务被变更
            checkDataSourceNotUsed(id);

            DataSource entity = ConvertUtil.sourceToTarget(dto, DataSource.class);
            entity.setId(id);
            entity.setName(dto.getName().trim());
            entity.setBusinessSystemId(ownership.system().getId());
            // Do not mutate the legacy compatibility value on canonical updates.
            entity.setDataSourceUnit(existing.getDataSourceUnit());
            entity.setConnectionParams(JSONUtils.toJsonString(connectionParam));
            entity.setOriginalJson(dto.getConnectionParams());
            entity.setConnStatus(existing.getConnStatus());
            entity.setStatus(existing.getStatus() == null
                    ? DataSourceLifecycleStatus.ENABLED
                    : existing.getStatus());
            entity.initUpdate();
            entity.setCreateUserId(existing.getCreateUserId());
            entity.setUpdateUserId(currentUserProvider.getCurrentUserId());
            entity.setCreateTime(existing.getCreateTime());

            dataSourceDao.updateById(entity);
            metadataBindingCommandService.markConfigurationChanged(id);
            return entity;
        } catch (DuplicateKeyException e) {
            log.warn("Update data source failed due to duplicate key, id={}, name={}", id, dto.getName(), e);
            throw new ServiceException(Status.DATASOURCE_EXIST);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Update data source failed, id={}, name={}, dbType={}", id, dto.getName(), dto.getDbType(), e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DataSource assignBusinessSystem(Long id, Long businessSystemId) {
        validateId(id);

        DataSource existing = getDataSourceOrThrow(id);
        if (existing.getStatus() == DataSourceLifecycleStatus.REVOKED) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "A revoked data source cannot be assigned.");
        }

        BusinessSystemOwnership ownership = resolveActiveBusinessSystem(businessSystemId);
        if (java.util.Objects.equals(existing.getBusinessSystemId(), ownership.system().getId())) {
            return existing;
        }

        DataSource entity = new DataSource();
        entity.setId(id);
        entity.setBusinessSystemId(ownership.system().getId());
        entity.setUpdateUserId(currentUserProvider.getCurrentUserId());
        entity.initUpdate();
        dataSourceDao.updateById(entity);
        metadataBindingCommandService.markConfigurationChanged(id);
        return getDataSourceOrThrow(id);
    }

    @Override
    public DataSource selectById(Long id) {
        validateId(id);
        return getDataSourceOrThrow(id);
    }

    @Override
    public PaginationResult<DataSourceVO> queryDataSourceListPaging(DataSourceDTO dto) {
        try {
            if (dto == null) {
                dto = new DataSourceDTO();
            }
            normalizePage(dto);
            Collection<Long> systemIds = null;
            if (dto.getUnitId() != null) {
                systemIds = businessSystemDao.queryByUnitId(dto.getUnitId()).stream()
                        .map(BusinessSystem::getId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
            }

            IPage<DataSource> pageResult = dataSourceDao.queryPage(dto, systemIds);
            List<DataSourceVO> records =
                    ConvertUtil.sourceListToTarget(pageResult.getRecords(), DataSourceVO.class);

            Map<Long, BusinessSystem> systems = loadBusinessSystems(pageResult.getRecords());
            Map<Long, DataSourceUnit> units = loadUnits(systems);
            Map<Long, MetadataSourceBinding> bindings = loadMetadataBindings(pageResult.getRecords());
            records.forEach(record -> {
                fillDerivedFields(record, systems, units);
                fillMetadataFields(record, bindings.get(record.getId()));
            });

            return PaginationResult.buildSuc(records, pageResult);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // Do not log the DTO: it may contain the datasource connection JSON/password.
            log.error("Query data source list paging failed: pageNo={}, pageSize={}, dbType={}, unitId={}, businessSystemId={}",
                    dto == null ? null : dto.getPageNo(),
                    dto == null ? null : dto.getPageSize(),
                    dto == null ? null : dto.getDbType(),
                    dto == null ? null : dto.getUnitId(),
                    dto == null ? null : dto.getBusinessSystemId(),
                    e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long datasourceId) {
        validateId(datasourceId);
        getDataSourceOrThrow(datasourceId);

        try {
            checkDataSourceNotUsedByAnyJob(datasourceId);
            checkDataSourceNotUsedByLake(datasourceId);
            metadataBindingCommandService.markDeleted(datasourceId);
            markDataSourcePendingDeletion(datasourceId);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Delete data source failed, id={}", datasourceId, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public boolean isDataSourceUsed(Long datasourceId) {
        validateId(datasourceId);
        getDataSourceOrThrow(datasourceId);
        return isReferencedByAnyJob(datasourceId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(Long id, DataSourceLifecycleStatus status) {
        validateId(id);
        if (status == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "status");
        }

        DataSource existing = getDataSourceOrThrow(id);
        DataSourceLifecycleStatus currentStatus = existing.getStatus() == null
                ? DataSourceLifecycleStatus.ENABLED
                : existing.getStatus();

        if (currentStatus == DataSourceLifecycleStatus.REVOKED
                && status != DataSourceLifecycleStatus.REVOKED) {
            throw new ServiceException(
                    Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "A revoked data source cannot be enabled or disabled.");
        }

        if (currentStatus == status) {
            return true;
        }

        try {
            if (status != DataSourceLifecycleStatus.ENABLED) {
                checkDataSourceNotUsed(id, status == DataSourceLifecycleStatus.REVOKED
                        ? "revoked"
                        : "disabled");
                if (status == DataSourceLifecycleStatus.REVOKED) {
                    checkDataSourceNotUsedByLake(id);
                }
            }

            DataSource entity = new DataSource();
            entity.setId(id);
            entity.setStatus(status);
            entity.initUpdate();
            return dataSourceDao.updateById(entity);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Update data source status failed, id={}, status={}", id, status, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "ids");
        }

        try {
            List<Long> distinctIds = ids.stream()
                    .filter(id -> id != null && id > 0)
                    .distinct()
                    .collect(Collectors.toList());

            if (distinctIds.isEmpty()) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "ids");
            }

            for (Long id : distinctIds) {
                getDataSourceOrThrow(id);
            }

            checkDataSourcesNotUsed(distinctIds);

            for (Long id : distinctIds) {
                checkDataSourceNotUsedByLake(id);
                metadataBindingCommandService.markDeleted(id);
                markDataSourcePendingDeletion(id);
            }
            return true;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Batch delete data sources failed, ids={}", ids, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public Boolean connectionTest(Long id) {
        validateId(id);
        DataSource dataSource = getDataSourceOrThrow(id);
        return testConnection(dataSource);
    }

    @Override
    public Boolean batchConnectionTest(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "ids");
        }

        return ids.parallelStream().allMatch(this::connectionTest);
    }

    @Override
    public Boolean connectionTestWithParam(String connJson) {
        if (StringUtils.isBlank(connJson)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "connectionParams");
        }

        try {
            DbType dbType = extractDbType(connJson);
            ConnectionParam param = DataSourceUtils.buildConnectionParams(dbType, connJson);
            return checkConnection(dbType, param);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Connection test with param failed", e);
            throw new ServiceException(Status.DATASOURCE_CONNECT_TEST_ERROR, e.getMessage());
        }
    }

    @Override
    public List<DataSourceVO> listAll() {
        try {
            List<DataSource> entities = dataSourceDao.queryAll();
            List<DataSourceVO> result = ConvertUtil.sourceListToTarget(entities, DataSourceVO.class);
            Map<Long, BusinessSystem> systems = loadBusinessSystems(entities);
            Map<Long, DataSourceUnit> units = loadUnits(systems);
            Map<Long, MetadataSourceBinding> bindings = loadMetadataBindings(entities);
            result.forEach(record -> {
                fillDerivedFields(record, systems, units);
                fillMetadataFields(record, bindings.get(record.getId()));
            });
            return result;
        } catch (Exception e) {
            log.error("List all data sources failed", e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public List<String> listDataSourceUnits() {
        try {
            return dataSourceDao.queryDataSourceUnits();
        } catch (Exception e) {
            log.error("List data source units failed", e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public Map<String, Object> uploadJdbcDriver(MultipartFile file, String pluginType, boolean overwrite) {
        validateJdbcDriverFile(file);

        String originalFilename = file.getOriginalFilename();
        assert originalFilename != null;
        String filename = Paths.get(originalFilename).getFileName().toString();
        Path targetDir = Paths.get(System.getProperty("user.dir"), JDBC_DRIVER_DIR);

        try {
            Files.createDirectories(targetDir);

            Path targetFile = targetDir.resolve(filename).normalize();
            if (!targetFile.startsWith(targetDir)) {
                throw new ServiceException(Status.DATASOURCE_FILE_NAME_INVALID);
            }

            if (Files.exists(targetFile) && !overwrite) {
                throw new ServiceException(Status.DATASOURCE_FILE_EXIST);
            }

            Path tempFile = Files.createTempFile(targetDir, filename + ".", ".uploading");
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

                CopyOption[] moveOptions = overwrite
                        ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE}
                        : new CopyOption[]{StandardCopyOption.ATOMIC_MOVE};

                Files.move(tempFile, targetFile, moveOptions);
            } finally {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ex) {
                    log.warn("Delete temp jdbc driver file failed, tempFile={}", tempFile, ex);
                }
            }

            Map<String, Object> result = new HashMap<>(4);
            result.put("fileName", filename);
            result.put("absolutePath", targetFile.toAbsolutePath().toString());
            result.put("driverLocation", filename);
            result.put("pluginType", pluginType);

            return result;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Upload jdbc driver failed, fileName={}", filename, e);
            throw new ServiceException(Status.DATASOURCE_UPLOAD_FAILED, e.getMessage());
        }
    }

    @Override
    public List<DBOptionVO> option(String dbType) {
        try {
            List<DataSource> entities = dataSourceDao.queryByDbType(dbType);
            return entities.stream()
                    .filter(entity -> entity.getStatus() == null
                            || entity.getStatus() == DataSourceLifecycleStatus.ENABLED)
                    .map(this::toOptionVO)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Query data source options failed, dbType={}", dbType, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }


    private void checkDataSourceNotUsed(Long datasourceId) {
        checkDataSourceNotUsed(datasourceId, "deleted");
    }

    private void checkDataSourceNotUsedByAnyJob(Long datasourceId) {
        if (isReferencedByAnyJob(datasourceId)) {
            throw new ServiceException("The data source is currently used by a job and cannot be deleted.");
        }
    }

    private void checkDataSourceNotUsedByLake(Long datasourceId) {
        if (lakeOdsDatabaseBindingDao != null
                && (lakeOdsDatabaseBindingDao.existsActiveBySourceDataSourceId(datasourceId)
                || lakeOdsDatabaseBindingDao.existsActiveByLakeDataSourceId(datasourceId))) {
            throw new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT,
                    "The data source is referenced by an active lake ODS database");
        }
    }

    private boolean isReferencedByAnyJob(Long datasourceId) {
        List<Long> datasourceIds = Collections.singletonList(datasourceId);
        return !jobDefinitionDao.selectReferencedDatasourceIds(datasourceIds).isEmpty()
                || !streamingJobDefinitionDao.selectReferencedDatasourceIds(datasourceIds).isEmpty();
    }

    private void checkDataSourceNotUsed(Long datasourceId, String operation) {
        boolean usedByBatchJob = jobDefinitionDao.existsByDatasourceId(datasourceId);
        boolean usedByStreamingJob = streamingJobDefinitionDao.existsByDatasourceId(datasourceId);

        if (usedByBatchJob || usedByStreamingJob) {
            throw new ServiceException("The data source is currently used by a job and cannot be "
                    + operation + "."
            );
        }
    }

    private void checkDataSourcesNotUsed(List<Long> datasourceIds) {
        List<Long> batchReferencedIds = jobDefinitionDao.selectReferencedDatasourceIds(datasourceIds);
        List<Long> streamingReferencedIds = streamingJobDefinitionDao.selectReferencedDatasourceIds(datasourceIds);

        List<Long> referencedIds = java.util.stream.Stream
                .concat(batchReferencedIds.stream(), streamingReferencedIds.stream())
                .distinct()
                .collect(Collectors.toList());

        if (!referencedIds.isEmpty()) {
            throw new ServiceException(
                    Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "Data sources are used by batch or streaming job definitions and cannot be deleted. datasourceIds=" + referencedIds
            );
        }
    }

    public Boolean checkConnection(DbType dbType, ConnectionParam param) {
        try {
            DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(dbType);
            boolean connected = processor.getConnectivityVerifier().checkDataSourceConnectivity(param);
            if (!connected) {
                throw new ServiceException(Status.DATASOURCE_CONNECT_FAILED);
            }
            return true;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Check data source connection failed, dbType={}", dbType, e);
            throw new ServiceException(Status.DATASOURCE_CONNECT_TEST_ERROR, e.getMessage());
        }
    }

    private void validateCreateRequest(DataSourceDTO dto) {
        validateDto(dto);

        String name = dto.getName().trim();
        if (dataSourceDao.checkName(name)) {
            throw new ServiceException(Status.DATASOURCE_EXIST);
        }

        if (checkDescriptionLength(dto.getRemark())) {
            throw new ServiceException(Status.DESCRIPTION_TOO_LONG_ERROR);
        }
    }

    private void validateUpdateRequest(Long id, DataSourceDTO dto) {
        validateDto(dto);

        String name = dto.getName().trim();
        if (dataSourceDao.checkNameExcludeId(name, id)) {
            throw new ServiceException(Status.DATASOURCE_EXIST);
        }

        if (checkDescriptionLength(dto.getRemark())) {
            throw new ServiceException(Status.DESCRIPTION_TOO_LONG_ERROR);
        }
    }

    private void validateDto(DataSourceDTO dto) {
        if (dto == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "dataSourceDTO");
        }
        if (StringUtils.isBlank(dto.getName())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "name");
        }
        if (dto.getBusinessSystemId() == null || dto.getBusinessSystemId() <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "businessSystemId");
        }
        if (dto.getDbType() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "dbType");
        }
        if (StringUtils.isBlank(dto.getConnectionParams())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "connectionParams");
        }
    }

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "id");
        }
    }

    private void normalizePage(DataSourceDTO dto) {
        if (dto.getPageNo() == null || dto.getPageNo() <= 0) {
            dto.setPageNo(1);
        }
        if (dto.getPageSize() == null || dto.getPageSize() <= 0) {
            dto.setPageSize(10);
        }
    }

    private BusinessSystemOwnership resolveActiveBusinessSystem(Long businessSystemId) {
        if (businessSystemId == null || businessSystemId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "businessSystemId");
        }

        BusinessSystem system = businessSystemDao.queryById(businessSystemId);
        if (system == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "businessSystemId does not exist");
        }
        if (system.getStatus() != null && !Integer.valueOf(1).equals(system.getStatus())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "businessSystemId is inactive");
        }

        DataSourceUnit unit = dataSourceUnitDao.queryById(system.getUnitId());
        if (unit == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "business system unit does not exist");
        }
        if (unit.getStatus() != null && !Integer.valueOf(1).equals(unit.getStatus())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "business system unit is inactive");
        }
        return new BusinessSystemOwnership(system, unit);
    }

    private void validateJdbcDriverFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(Status.DATASOURCE_FILE_EMPTY);
        }

        String originalFilename = file.getOriginalFilename();
        if (StringUtils.isBlank(originalFilename)) {
            throw new ServiceException(Status.DATASOURCE_FILE_NAME_INVALID);
        }

        String safeFilename = Paths.get(originalFilename).getFileName().toString();
        if (StringUtils.isBlank(safeFilename)) {
            throw new ServiceException(Status.DATASOURCE_FILE_NAME_INVALID);
        }

        if (!StringUtils.endsWithIgnoreCase(safeFilename, JDBC_JAR_SUFFIX)) {
            throw new ServiceException(Status.DATASOURCE_FILE_TYPE_ERROR);
        }

        if (file.getSize() > MAX_JDBC_DRIVER_SIZE) {
            throw new ServiceException(Status.DATASOURCE_FILE_TOO_LARGE);
        }
    }

    private DataSource getDataSourceOrThrow(Long id) {
        DataSource entity = dataSourceDao.queryById(id);
        if (entity == null) {
            throw new ServiceException(Status.DATASOURCE_NOT_EXIST);
        }
        return entity;
    }

    private void markDataSourcePendingDeletion(Long id) {
        DataSource entity = new DataSource();
        entity.setId(id);
        entity.setStatus(DataSourceLifecycleStatus.REVOKED);
        entity.setUpdateUserId(currentUserProvider.getCurrentUserId());
        entity.initUpdate();
        dataSourceDao.updateById(entity);
    }

    private DbType extractDbType(String connJson) {
        String type = JSONUtils.getNodeString(connJson, "type");
        if (StringUtils.isBlank(type)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "type");
        }

        try {
            return DbType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "type");
        }
    }

    private Boolean testConnection(DataSource dataSource) {
        updateConnectionStatus(dataSource.getId(), ConnStatus.CONNECTING);
        try {
            ConnectionParam param = DataSourceUtils.buildConnectionParams(
                    dataSource.getDbType(),
                    dataSource.getConnectionParams()
            );

            boolean connected = checkConnection(dataSource.getDbType(), param);
            updateConnectionStatus(
                    dataSource.getId(),
                    connected ? ConnStatus.CONNECTED_SUCCESS : ConnStatus.CONNECTED_FAILED
            );
            return connected;
        } catch (ServiceException e) {
            updateConnectionStatus(dataSource.getId(), ConnStatus.CONNECTED_FAILED);
            throw e;
        } catch (Exception e) {
            log.error("Connection test failed, dataSourceId={}", dataSource.getId(), e);
            updateConnectionStatus(dataSource.getId(), ConnStatus.CONNECTED_FAILED);
            throw new ServiceException(Status.DATASOURCE_CONNECT_TEST_ERROR, e.getMessage());
        }
    }

    private void updateConnectionStatus(Long id, ConnStatus status) {
        try {
            dataSourceDao.updateConnStatus(id, status);
        } catch (Exception e) {
            log.error("Update connection status failed, id={}, status={}", id, status, e);
        }
    }

    private void fillDerivedFields(
            DataSourceVO vo, Map<Long, BusinessSystem> systems, Map<Long, DataSourceUnit> units) {
        if (vo == null) {
            return;
        }

        BusinessSystem system = vo.getBusinessSystemId() == null
                ? null
                : systems.get(vo.getBusinessSystemId());
        DataSourceUnit unit = system == null ? null : units.get(system.getUnitId());
        if (system != null) {
            vo.setSystemCode(system.getSystemCode());
            vo.setBusinessSystemName(system.getSystemName());
            vo.setUnitId(system.getUnitId());
        }
        if (unit != null) {
            vo.setUnitCode(unit.getUnitCode());
            vo.setUnitName(unit.getUnitName());
            // The old string is a derived compatibility field for canonical rows.
            vo.setDataSourceUnit(unit.getUnitName());
        } else if (StringUtils.isBlank(vo.getDataSourceUnit())) {
            vo.setDataSourceUnit(UNASSIGNED_LABEL);
            vo.setUnitName(UNASSIGNED_LABEL);
        }

        try {
            String jdbcUrl = JSONUtils.getNodeString(vo.getConnectionParams(), "url");
            if (StringUtils.isBlank(jdbcUrl)) {
                jdbcUrl = JSONUtils.getNodeString(vo.getConnectionParams(), "baseUrl");
            }
            vo.setJdbcUrl(jdbcUrl);
        } catch (Exception e) {
            log.warn("Parse jdbc url from connection params failed");
        }

        if (vo.getEnvironment() != null) {
            vo.setEnvironmentName(vo.getEnvironment().getDescription());
        }
    }

    private static void fillMetadataFields(DataSourceVO vo, MetadataSourceBinding binding) {
        if (binding == null) {
            vo.setMetadataSyncStatus("NOT_INITIALIZED");
            vo.setScanStatus(org.apache.seatunnel.web.common.enums.MetadataRunStatus.NEVER);
            vo.setProfileStatus(org.apache.seatunnel.web.common.enums.MetadataRunStatus.NEVER);
            return;
        }
        vo.setMetadataSyncStatus(binding.getSyncStatus() == null ? "NOT_INITIALIZED" : binding.getSyncStatus().name());
        vo.setScanStatus(binding.getScanStatus());
        vo.setScanLastRunTime(binding.getScanLastRunTime());
        vo.setScanLastSuccessTime(binding.getScanLastSuccessTime());
        vo.setProfileStatus(binding.getProfileStatus());
        vo.setProfileLastRunTime(binding.getProfileLastRunTime());
        vo.setProfileLastSuccessTime(binding.getProfileLastSuccessTime());
    }

    private DBOptionVO toOptionVO(DataSource entity) {
        DBOptionVO option = new DBOptionVO();
        option.setValue(entity.getId());
        option.setLabel(entity.getName());
        option.setDbType(entity.getDbType());
        return option;
    }

    private Map<Long, BusinessSystem> loadBusinessSystems(List<DataSource> entities) {
        List<Long> ids = entities.stream()
                .map(DataSource::getBusinessSystemId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return businessSystemDao.queryByIds(ids).stream()
                .collect(Collectors.toMap(BusinessSystem::getId, item -> item, (left, right) -> left));
    }

    private Map<Long, MetadataSourceBinding> loadMetadataBindings(List<DataSource> entities) {
        List<Long> ids = entities.stream()
                .map(DataSource::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return metadataBindingDao.queryByDataSourceIds(ids).stream()
                .collect(Collectors.toMap(MetadataSourceBinding::getDataSourceId, item -> item, (left, right) -> left));
    }

    private Map<Long, DataSourceUnit> loadUnits(Map<Long, BusinessSystem> systems) {
        List<Long> ids = systems.values().stream()
                .map(BusinessSystem::getUnitId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return dataSourceUnitDao.queryByIds(ids).stream()
                .collect(Collectors.toMap(DataSourceUnit::getId, item -> item, (left, right) -> left));
    }

    private record BusinessSystemOwnership(BusinessSystem system, DataSourceUnit unit) {
    }
}
