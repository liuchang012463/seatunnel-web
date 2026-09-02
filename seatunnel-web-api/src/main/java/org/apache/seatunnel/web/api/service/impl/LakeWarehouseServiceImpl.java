package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeJdbcDriverLoader;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeDataSourceAlias;
import org.apache.seatunnel.web.dao.entity.LakeJdbcDriver;
import org.apache.seatunnel.web.dao.entity.LakeWarehouseConfig;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeDataSourceAliasDao;
import org.apache.seatunnel.web.dao.repository.LakeJdbcDriverDao;
import org.apache.seatunnel.web.dao.repository.LakeWarehouseConfigDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeWarehouseConfigDTO;
import org.apache.seatunnel.web.spi.bean.vo.LakeJdbcDriverVO;
import org.apache.seatunnel.web.spi.bean.vo.LakeWarehouseConfigVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Persists the single Doris ODS configuration and its task-compatible projection. */
@Slf4j
@Service
public class LakeWarehouseServiceImpl implements LakeWarehouseService {

    public static final String CONFIG_KEY = "ODS_DORIS";
    public static final String SYSTEM_KEY = "LAKE_ODS_DORIS";
    private static final String DEFAULT_NAME = "湖 ODS 数仓";
    private static final String DEFAULT_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final Pattern DRIVER_CLASS = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private final LakeWarehouseConfigDao configDao;
    private final LakeJdbcDriverDao driverDao;
    private final LakeDataSourceAliasDao aliasDao;
    private final DataSourceDao dataSourceDao;
    private final CurrentUserProvider currentUserProvider;

    public LakeWarehouseServiceImpl(
            LakeWarehouseConfigDao configDao,
            LakeJdbcDriverDao driverDao,
            LakeDataSourceAliasDao aliasDao,
            DataSourceDao dataSourceDao,
            CurrentUserProvider currentUserProvider) {
        this.configDao = Objects.requireNonNull(configDao, "configDao");
        this.driverDao = Objects.requireNonNull(driverDao, "driverDao");
        this.aliasDao = Objects.requireNonNull(aliasDao, "aliasDao");
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
    }

    @Override
    public LakeWarehouseConfigVO getConfig() {
        return toVO(configDao.querySingleton());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LakeWarehouseConfigVO saveConfig(LakeWarehouseConfigDTO request) {
        ValidConfig valid = validate(request, false);
        LakeWarehouseConfig current = configDao.querySingleton();
        String effectiveDriverLocation = StringUtils.defaultIfBlank(valid.driverLocation(),
                current == null ? null : current.getDriverLocation());
        if (StringUtils.isBlank(effectiveDriverLocation)) {
            throw invalid("driverLocation");
        }
        effectiveDriverLocation = validateDriverLocation(effectiveDriverLocation, null);
        String effectiveDriverSha256 = StringUtils.defaultIfBlank(valid.driverSha256(),
                current == null ? null : current.getDriverSha256());
        String computedDriverSha256 = sha256Of(resolveDriverPath(effectiveDriverLocation));
        if (StringUtils.isNotBlank(effectiveDriverSha256)
                && !computedDriverSha256.equalsIgnoreCase(effectiveDriverSha256.trim())) {
            throw invalid("driverSha256");
        }
        effectiveDriverSha256 = computedDriverSha256;
        String effectiveDriverClass = StringUtils.defaultIfBlank(request.getDriverClass(),
                current == null ? DEFAULT_DRIVER : current.getDriverClass());
        validateDriverClass(effectiveDriverClass);
        ValidConfig effective = new ValidConfig(valid.jdbcUrl(), valid.username(),
                effectiveDriverClass, effectiveDriverLocation, effectiveDriverSha256);
        LakeWarehouseConfigDTO testRequest = copyForTest(request, effectiveDriverLocation, effectiveDriverClass);
        LakeWarehouseConfigVO connectionTest = testConfig(testRequest);
        if (!connectionTest.isConfigured() || !ConnStatus.CONNECTED_SUCCESS.getCode().equals(connectionTest.getConnStatus())) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "无法连接 Doris ODS，请检查地址、账号、密码和本地驱动");
        }
        String encryptedPassword = current == null || StringUtils.isBlank(request.getPassword())
                ? current == null ? null : current.getPassword()
                : PasswordUtils.encodePassword(request.getPassword());
        if (StringUtils.isBlank(encryptedPassword)) {
            throw invalid("password");
        }

        Long adoptedId = current == null ? request.getAdoptDataSourceId() : current.getSystemDataSourceId();
        DataSource projection = resolveProjection(adoptedId);
        if (projection == null) {
            projection = new DataSource();
            projection.initInsert();
            projection.setCreateUserId(currentUserId());
        } else if ((Boolean.TRUE.equals(projection.getSystemManaged())
                || StringUtils.isNotBlank(projection.getSystemKey()))
                && !SYSTEM_KEY.equals(projection.getSystemKey())) {
            throw invalid("adoptDataSourceId");
        }

        Long oldProjectionId = current == null ? null : current.getSystemDataSourceId();
        projection.setName(StringUtils.defaultIfBlank(request.getName(), DEFAULT_NAME).trim());
        projection.setDbType(DbType.DORIS);
        projection.setConnectionParams(connectionJson(effective, encryptedPassword, effective.driverLocation()));
        projection.setOriginalJson(projection.getConnectionParams());
        projection.setSystemManaged(true);
        projection.setSystemKey(SYSTEM_KEY);
        projection.setConnStatus(ConnStatus.CONNECTED_SUCCESS);
        projection.setStatus(DataSourceLifecycleStatus.ENABLED);
        projection.setUpdateUserId(currentUserId());
        projection.initUpdate();
        if (projection.getId() == null) {
            dataSourceDao.insert(projection);
        } else {
            dataSourceDao.updateById(projection);
        }
        if (oldProjectionId != null && !oldProjectionId.equals(projection.getId())) {
            saveAlias(oldProjectionId, projection.getId(), "system projection replaced");
        }
        if (request.getAdoptDataSourceId() != null
                && !request.getAdoptDataSourceId().equals(projection.getId())) {
            saveAlias(request.getAdoptDataSourceId(), projection.getId(), "legacy datasource adopted");
        }

        LakeWarehouseConfig target = current == null ? new LakeWarehouseConfig() : current;
        if (current == null) {
            target.initInsert();
            target.setConfigKey(CONFIG_KEY);
            target.setConfigVersion(1L);
            target.setCreateUserId(currentUserId());
        } else {
            target.setConfigVersion(Math.max(1L, target.getConfigVersion() == null ? 1L : target.getConfigVersion() + 1L));
        }
        target.setName(StringUtils.defaultIfBlank(request.getName(), DEFAULT_NAME).trim());
        target.setJdbcUrl(effective.jdbcUrl());
        target.setUsername(effective.username());
        target.setPassword(encryptedPassword);
        target.setDriverClass(effective.driverClass());
        target.setDriverLocation(effective.driverLocation());
        target.setDriverSha256(effective.driverSha256());
        target.setSystemDataSourceId(projection.getId());
        target.setConnStatus(ConnStatus.CONNECTED_SUCCESS);
        target.setLastError(null);
        target.setUpdateUserId(currentUserId());
        if (current == null) {
            configDao.insert(target);
        } else {
            target.initUpdate();
            configDao.updateSingleton(target);
        }
        // Keep the logical catalog registry in sync with the same verified
        // local artifact used by the ODS connection.  This is idempotent and
        // never downloads a driver from a remote repository.
        registerDriver(LakeJdbcAdapterType.MYSQL.code(),
                Paths.get(effective.driverLocation()).getFileName().toString(),
                effective.driverLocation(), effective.driverClass(), effective.driverSha256(), null);
        return toVO(target);
    }

    @Override
    public LakeWarehouseConfigVO testConfig(LakeWarehouseConfigDTO request) {
        ValidConfig valid = validate(request, true);
        String password = StringUtils.defaultString(request.getPassword());
        LakeWarehouseConfig existing = configDao.querySingleton();
        if (password.isBlank() && existing != null) {
            password = PasswordUtils.decodePassword(existing.getPassword());
        }
        if (password.isBlank()) {
            throw invalid("password");
        }
        String driverLocation = StringUtils.defaultIfBlank(valid.driverLocation(),
                existing == null ? null : existing.getDriverLocation());
        String driverClass = StringUtils.defaultIfBlank(request.getDriverClass(),
                existing == null ? valid.driverClass() : existing.getDriverClass());
        validateDriverClass(driverClass);
        if (StringUtils.isBlank(driverLocation)) {
            throw invalid("driverLocation");
        }
        driverLocation = validateDriverLocation(driverLocation, null);
        try {
            try (Connection ignored = LakeJdbcDriverLoader.connect(valid.jdbcUrl(), valid.username(), password,
                    driverClass, driverLocation)) {
                LakeWarehouseConfigVO result = toVO(existing);
                if (result == null) {
                    result = new LakeWarehouseConfigVO();
                }
                result.setConfigured(true);
                result.setConnStatus(ConnStatus.CONNECTED_SUCCESS.getCode());
                result.setLastError(null);
                return result;
            }
        } catch (Exception exception) {
            LakeWarehouseConfigVO result = toVO(existing);
            if (result == null) {
                result = new LakeWarehouseConfigVO();
            }
            result.setConfigured(false);
            result.setConnStatus(ConnStatus.CONNECTED_FAILED.getCode());
            result.setLastError("Doris 连接失败，请检查地址、账号、密码和驱动");
            return result;
        }
    }

    @Override
    public List<LakeJdbcDriverVO> listDrivers() {
        List<LakeJdbcDriverVO> result = new ArrayList<>();
        for (LakeJdbcDriver driver : driverDao.queryAll()) {
            result.add(toDriverVO(driver));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LakeJdbcDriverVO registerDriver(String adapter, String fileName, String driverLocation,
                                           String driverClass, String sha256, String dorisMd5) {
        LakeJdbcAdapterType type = parseAdapter(adapter);
        String safeLocation = validateDriverLocation(driverLocation, fileName);
        String computed = sha256Of(resolveDriverPath(safeLocation));
        if (StringUtils.isNotBlank(sha256) && !computed.equalsIgnoreCase(sha256.trim())) {
            throw invalid("sha256");
        }
        String normalizedDriverClass = StringUtils.defaultIfBlank(
                driverClass, defaultDriver(type)).trim();
        validateDriverClass(normalizedDriverClass);
        LakeJdbcDriver existing = driverDao.queryByAdapter(type.name());
        LakeJdbcDriver target = existing == null ? new LakeJdbcDriver() : existing;
        boolean registrationChanged = existing == null
                || !Objects.equals(existing.getSha256(), computed)
                || !Objects.equals(existing.getDriverLocation(), safeLocation)
                || !Objects.equals(existing.getDriverClass(), normalizedDriverClass)
                || !Objects.equals(existing.getDorisMd5(), StringUtils.trimToNull(dorisMd5));
        if (existing == null) {
            target.initInsert();
            target.setCreateUserId(currentUserId());
        }
        target.setAdapter(type.name());
        target.setFileName(StringUtils.defaultIfBlank(fileName, Paths.get(safeLocation).getFileName().toString()));
        target.setDriverLocation(safeLocation);
        target.setDriverClass(normalizedDriverClass);
        // Registration is a verification step, not just metadata insertion:
        // load the class from the constrained local jar before marking it
        // READY.  This keeps a missing/incorrect class from surfacing later
        // during a catalog or ODS connection operation.
        try {
            LakeJdbcDriverLoader.ensureLoaded(target.getDriverClass(), safeLocation);
        } catch (RuntimeException exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID,
                    "JDBC 驱动类无法从本地文件加载");
        }
        target.setSha256(computed);
        target.setDorisMd5(StringUtils.trimToNull(dorisMd5));
        target.setEnabled(true);
        target.setVerified(true);
        target.setStatus("READY");
        long currentVersion = existing == null || existing.getVersion() == null
                ? 0L : Math.max(0L, existing.getVersion());
        target.setVersion(registrationChanged ? currentVersion + 1L : Math.max(1L, currentVersion));
        target.setLastError(null);
        target.setUpdateUserId(currentUserId());
        target.initUpdate();
        if (existing == null) {
            driverDao.insert(target);
        } else {
            driverDao.updateDriver(target);
        }
        return toDriverVO(target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LakeJdbcDriverVO uploadDriver(MultipartFile file, String adapter,
                                         String driverClass, boolean overwrite) {
        if (file == null || file.isEmpty() || StringUtils.isBlank(file.getOriginalFilename())) {
            throw invalid("file");
        }
        String filename = Paths.get(file.getOriginalFilename()).getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw invalid("file");
        }
        Path directory = LakeJdbcDriverLoader.resolveDriverDirectory();
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(filename).normalize();
            if (!target.startsWith(directory) || Files.exists(target) && !overwrite) {
                throw invalid("file");
            }
            Path temp = Files.createTempFile(directory, filename + ".", ".uploading");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return registerDriver(adapter, filename, filename, driverClass, sha256Of(target), null);
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID,
                    "JDBC 驱动上传失败");
        }
    }

    @Override
    public LakeWarehouseConfig requireConfig() {
        LakeWarehouseConfig config = configDao.querySingleton();
        if (config == null || StringUtils.isBlank(config.getJdbcUrl())
                || StringUtils.isBlank(config.getUsername()) || StringUtils.isBlank(config.getPassword())) {
            throw new LakeServiceException(LakeErrorCode.LAKE_WAREHOUSE_NOT_CONFIGURED,
                    "湖 ODS 数仓尚未配置，请先完成数仓配置");
        }
        return config;
    }

    @Override
    public Long requireSystemDataSourceId() {
        LakeWarehouseConfig config = requireConfig();
        if (config.getSystemDataSourceId() == null || config.getSystemDataSourceId() <= 0) {
            throw new LakeServiceException(LakeErrorCode.LAKE_WAREHOUSE_NOT_CONFIGURED,
                    "湖 ODS 数仓尚未生成系统数据源，请保存数仓配置");
        }
        DataSource projection = dataSourceDao.queryById(config.getSystemDataSourceId());
        if (projection == null || !Boolean.TRUE.equals(projection.getSystemManaged())
                || !SYSTEM_KEY.equals(projection.getSystemKey())
                || projection.getDbType() != DbType.DORIS) {
            throw new LakeServiceException(LakeErrorCode.LAKE_WAREHOUSE_NOT_CONFIGURED,
                    "湖 ODS 系统数据源投影不存在，请重新保存数仓配置");
        }
        return config.getSystemDataSourceId();
    }

    @Override
    public Long canonicalDataSourceId(Long lakeDataSourceId) {
        if (lakeDataSourceId == null || lakeDataSourceId <= 0) {
            return requireSystemDataSourceId();
        }
        LakeWarehouseConfig config = configDao.querySingleton();
        if (config == null || config.getSystemDataSourceId() == null
                || config.getSystemDataSourceId() <= 0) {
            return requireSystemDataSourceId();
        }
        if (lakeDataSourceId.equals(config.getSystemDataSourceId())) {
            return requireSystemDataSourceId();
        }
        LakeDataSourceAlias alias = aliasDao.queryByLegacyId(lakeDataSourceId);
        if (alias != null
                && Objects.equals(alias.getCanonicalDataSourceId(), config.getSystemDataSourceId())) {
            // Alias rows are only compatibility evidence.  Never use their
            // value as a new connection source; always return the current
            // system projection from the singleton config.
            return requireSystemDataSourceId();
        }
        throw new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID,
                "历史湖数据源 ID 未注册兼容映射，请重新保存数仓配置");
    }

    private DataSource resolveProjection(Long adoptedId) {
        if (adoptedId != null) {
            DataSource candidate = dataSourceDao.queryById(adoptedId);
            if (candidate == null || candidate.getDbType() != DbType.DORIS) {
                throw invalid("adoptDataSourceId");
            }
            return candidate;
        }
        return dataSourceDao.queryBySystemKey(SYSTEM_KEY);
    }

    private void saveAlias(Long legacyId, Long canonicalId, String reason) {
        if (legacyId == null || canonicalId == null || legacyId.equals(canonicalId)) {
            return;
        }
        LakeDataSourceAlias alias = aliasDao.queryByLegacyId(legacyId);
        boolean created = alias == null;
        if (alias == null) {
            alias = new LakeDataSourceAlias();
            alias.initInsert();
            alias.setLegacyDataSourceId(legacyId);
        }
        alias.setCanonicalDataSourceId(canonicalId);
        alias.setReason(reason);
        alias.initUpdate();
        if (created) {
            aliasDao.insert(alias);
        } else {
            aliasDao.updateById(alias);
        }
    }

    private ValidConfig validate(LakeWarehouseConfigDTO request, boolean allowExistingPassword) {
        if (request == null) {
            throw invalid("request");
        }
        String url = StringUtils.trimToNull(request.getJdbcUrl());
        String username = StringUtils.trimToNull(request.getUsername());
        String driverClass = StringUtils.defaultIfBlank(request.getDriverClass(), DEFAULT_DRIVER).trim();
        validateDriverClass(driverClass);
        String location = StringUtils.trimToNull(request.getDriverLocation());
        if (url == null || !url.startsWith("jdbc:") || url.matches("(?i).*(https?|ftp)://.*")
                || containsCredential(url)) {
            throw invalid("jdbcUrl");
        }
        if (username == null) {
            throw invalid("username");
        }
        if (!allowExistingPassword && StringUtils.isBlank(request.getPassword())
                && configDao.querySingleton() == null) {
            throw invalid("password");
        }
        if (location != null) {
            location = validateDriverLocation(location, null);
        }
        String sha = StringUtils.trimToNull(request.getDriverSha256());
        if (sha != null && !sha.matches("(?i)[0-9a-f]{64}")) {
            throw invalid("driverSha256");
        }
        return new ValidConfig(url, username, driverClass, location, sha);
    }

    private LakeWarehouseConfigDTO copyForTest(
            LakeWarehouseConfigDTO request, String driverLocation, String driverClass) {
        LakeWarehouseConfigDTO copy = new LakeWarehouseConfigDTO();
        copy.setName(request.getName());
        copy.setJdbcUrl(request.getJdbcUrl());
        copy.setUsername(request.getUsername());
        copy.setPassword(request.getPassword());
        copy.setDriverLocation(driverLocation);
        copy.setDriverClass(driverClass);
        copy.setDriverSha256(request.getDriverSha256());
        copy.setAdoptDataSourceId(request.getAdoptDataSourceId());
        return copy;
    }

    private String connectionJson(ValidConfig config, String encryptedPassword, String requestedLocation) {
        ObjectNode node = JSONUtils.createObjectNode();
        node.put("url", config.jdbcUrl());
        node.put("user", config.username());
        node.put("password", encryptedPassword);
        node.put("driver", config.driverClass());
        String location = StringUtils.defaultIfBlank(requestedLocation, config.driverLocation());
        if (StringUtils.isNotBlank(location)) {
            node.put("driverLocation", location);
        }
        node.put("database", "");
        return node.toString();
    }

    private String validateDriverLocation(String location, String fallbackFileName) {
        String value = StringUtils.trimToNull(location);
        if (value == null) {
            value = fallbackFileName;
        }
        if (value == null || value.contains("://") || value.contains("..")) {
            throw invalid("driverLocation");
        }
        Path path = resolveDriverPath(value);
        if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw invalid("driverLocation");
        }
        return LakeJdbcDriverLoader.resolveDriverDirectory().relativize(path).toString();
    }

    private Path resolveDriverPath(String location) {
        try {
            return LakeJdbcDriverLoader.resolveLocalPath(location);
        } catch (IllegalArgumentException exception) {
            throw invalid("driverLocation");
        }
    }

    private String sha256Of(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return DigestUtils.sha256Hex(input);
        } catch (IOException exception) {
            throw invalid("driverLocation");
        }
    }

    private LakeJdbcAdapterType parseAdapter(String adapter) {
        try {
            return LakeJdbcAdapterType.parse(adapter);
        } catch (RuntimeException exception) {
            throw invalid("adapter");
        }
    }

    private static String defaultDriver(LakeJdbcAdapterType type) {
        return switch (type) {
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case POSTGRESQL -> "org.postgresql.Driver";
            case ORACLE -> "oracle.jdbc.OracleDriver";
        };
    }

    private static void validateDriverClass(String driverClass) {
        if (StringUtils.isBlank(driverClass) || !DRIVER_CLASS.matcher(driverClass.trim()).matches()) {
            throw invalid("driverClass");
        }
    }

    private static boolean containsCredential(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(?:password|passwd|pwd|secret|token)\\s*[=:].*")
                || normalized.matches("jdbc:[^:]+://[^/@:]+:[^/@]+@.*");
    }

    private int currentUserId() {
        Integer id = currentUserProvider.getCurrentUserId();
        return id == null || id <= 0 ? 1 : id;
    }

    private static LakeServiceException invalid(String field) {
        return new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID,
                "数仓配置参数无效：" + field);
    }

    private static LakeWarehouseConfigVO toVO(LakeWarehouseConfig config) {
        if (config == null) {
            return null;
        }
        LakeWarehouseConfigVO result = new LakeWarehouseConfigVO();
        result.setName(config.getName());
        result.setJdbcUrl(config.getJdbcUrl());
        result.setUsername(config.getUsername());
        result.setPasswordConfigured(StringUtils.isNotBlank(config.getPassword()));
        result.setDriverClass(config.getDriverClass());
        result.setDriverLocation(config.getDriverLocation());
        result.setDriverSha256(config.getDriverSha256());
        result.setSystemDataSourceId(config.getSystemDataSourceId());
        result.setConfigVersion(config.getConfigVersion());
        result.setConnStatus(config.getConnStatus() == null ? null : config.getConnStatus().getCode());
        result.setLastError(config.getLastError());
        result.setConfigured(StringUtils.isNotBlank(config.getJdbcUrl())
                && StringUtils.isNotBlank(config.getUsername())
                && StringUtils.isNotBlank(config.getPassword()));
        return result;
    }

    private static LakeJdbcDriverVO toDriverVO(LakeJdbcDriver driver) {
        LakeJdbcDriverVO result = new LakeJdbcDriverVO();
        result.setId(driver.getId());
        result.setAdapter(driver.getAdapter());
        result.setFileName(driver.getFileName());
        result.setDriverLocation(driver.getDriverLocation());
        result.setDriverClass(driver.getDriverClass());
        result.setSha256(driver.getSha256());
        result.setDorisMd5(driver.getDorisMd5());
        result.setEnabled(driver.getEnabled());
        result.setVerified(driver.getVerified());
        result.setStatus(driver.getStatus());
        result.setVersion(driver.getVersion());
        result.setLastError(driver.getLastError());
        result.setUpdateTime(driver.getUpdateTime());
        return result;
    }

    private record ValidConfig(String jdbcUrl, String username, String driverClass,
                               String driverLocation, String driverSha256) {
    }
}
