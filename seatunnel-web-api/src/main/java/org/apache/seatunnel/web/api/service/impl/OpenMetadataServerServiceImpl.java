package org.apache.seatunnel.web.api.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationHealthService;
import org.apache.seatunnel.web.api.metadata.OpenMetadataConfigResolver;
import org.apache.seatunnel.web.api.metadata.OpenMetadataRuntimeConfig;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataHealth;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataRestClient;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.OpenMetadataServerService;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.dao.entity.OpenMetadataServerConfig;
import org.apache.seatunnel.web.dao.repository.OpenMetadataServerConfigDao;
import org.apache.seatunnel.web.dao.repository.impl.OpenMetadataServerConfigDaoImpl;
import org.apache.seatunnel.web.spi.bean.dto.OpenMetadataServerConfigDTO;
import org.apache.seatunnel.web.spi.bean.vo.OpenMetadataServerConfigVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

/** Persists the singleton OpenMetadata connection used by exploration and lake dual-mode. */
@Slf4j
@Service
public class OpenMetadataServerServiceImpl implements OpenMetadataServerService {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10000;

    private final OpenMetadataServerConfigDao configDao;
    private final OpenMetadataConfigResolver configResolver;
    private final MetadataIntegrationHealthService healthService;
    private final CurrentUserProvider currentUserProvider;

    public OpenMetadataServerServiceImpl(
            OpenMetadataServerConfigDao configDao,
            OpenMetadataConfigResolver configResolver,
            MetadataIntegrationHealthService healthService,
            CurrentUserProvider currentUserProvider) {
        this.configDao = Objects.requireNonNull(configDao, "configDao");
        this.configResolver = Objects.requireNonNull(configResolver, "configResolver");
        this.healthService = Objects.requireNonNull(healthService, "healthService");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
    }

    @Override
    public OpenMetadataServerConfigVO getConfig() {
        OpenMetadataServerConfigVO vo = toVO(configDao.querySingleton());
        if (vo == null) {
            vo = emptyVo();
        }
        vo.setHealth(healthService.health());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OpenMetadataServerConfigVO saveConfig(OpenMetadataServerConfigDTO request) {
        ValidConfig valid = validate(request, false);
        OpenMetadataServerConfig current = configDao.querySingleton();
        String token = resolveToken(current, request == null ? null : request.getToken());
        if (StringUtils.isBlank(token)) {
            throw invalid("token");
        }

        OpenMetadataRuntimeConfig probeConfig = toRuntime(valid, token, current);
        OpenMetadataServerConfigVO connectionTest = probe(probeConfig);
        if (!connectionTest.isConfigured()
                || !ConnStatus.CONNECTED_SUCCESS.getCode().equals(connectionTest.getConnStatus())) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "无法连接 OpenMetadata，请检查 Base URL、Token 与版本契约");
        }

        OpenMetadataServerConfig target = current == null ? new OpenMetadataServerConfig() : current;
        if (current == null) {
            target.initInsert();
            target.setConfigKey(OpenMetadataServerConfigDaoImpl.CONFIG_KEY);
            target.setConfigVersion(1L);
            target.setCreateUserId(currentUserId());
            target.setExpectedServerVersion(OpenMetadataRuntimeConfig.DEFAULT_SERVER_VERSION);
            target.setExpectedIngestionPatch(OpenMetadataRuntimeConfig.DEFAULT_INGESTION_PATCH);
        } else {
            target.setConfigVersion(Math.max(1L,
                    target.getConfigVersion() == null ? 1L : target.getConfigVersion() + 1L));
        }
        // Configured rows are always-on; no operator enable toggle.
        target.setEnabled(true);
        target.setBaseUrl(valid.baseUrl());
        target.setToken(token);
        target.setConnectTimeoutMs(valid.connectTimeoutMs());
        target.setReadTimeoutMs(valid.readTimeoutMs());
        target.setConnStatus(ConnStatus.CONNECTED_SUCCESS);
        target.setLastError(null);
        target.setUpdateUserId(currentUserId());
        if (current == null) {
            configDao.insert(target);
        } else {
            target.initUpdate();
            configDao.updateSingleton(target);
        }
        configResolver.invalidate();
        log.info("OpenMetadata server config saved: baseUrl={}, configVersion={}",
                sanitizeUrl(target.getBaseUrl()), target.getConfigVersion());
        OpenMetadataServerConfigVO vo = toVO(target);
        vo.setHealth(healthService.health());
        return vo;
    }

    @Override
    public OpenMetadataServerConfigVO testConfig(OpenMetadataServerConfigDTO request) {
        ValidConfig valid = validate(request, true);
        OpenMetadataServerConfig existing = configDao.querySingleton();
        String token = StringUtils.defaultString(request == null ? null : request.getToken());
        if (token.isBlank() && existing != null) {
            token = existing.getToken();
        }
        if (token.isBlank()) {
            throw invalid("token");
        }
        return probe(toRuntime(valid, token, existing));
    }

    private OpenMetadataServerConfigVO probe(OpenMetadataRuntimeConfig config) {
        OpenMetadataServerConfigVO result = toVO(configDao.querySingleton());
        if (result == null) {
            result = emptyVo();
        }
        result.setBaseUrl(config.getBaseUrl());
        result.setTokenConfigured(StringUtils.isNotBlank(config.getToken()));
        result.setConnectTimeoutMs(config.getConnectTimeoutMs());
        result.setReadTimeoutMs(config.getReadTimeoutMs());
        result.setConfigured(config.isConfigured());
        try {
            OpenMetadataConfigResolver.validateBaseUrl(config.getBaseUrl());
            OpenMetadataRestClient probeClient =
                    new OpenMetadataRestClient(OpenMetadataConfigResolver.fixed(config));
            OpenMetadataHealth health = probeClient.health();
            if (health == null || !health.openMetadataUp()) {
                result.setConfigured(false);
                result.setConnStatus(ConnStatus.CONNECTED_FAILED.getCode());
                result.setLastError("OpenMetadata 不可达，请检查 Base URL 与网络");
                return result;
            }
            probeClient.assertFixedVersion();
            result.setConfigured(true);
            result.setConnStatus(ConnStatus.CONNECTED_SUCCESS.getCode());
            result.setLastError(null);
            return result;
        } catch (MetadataIntegrationException exception) {
            log.warn("OpenMetadata connect-test failed: baseUrl={}, code={}, message={}",
                    sanitizeUrl(config.getBaseUrl()),
                    exception.getErrorCode(),
                    exception.getMessage());
            result.setConfigured(false);
            result.setConnStatus(ConnStatus.CONNECTED_FAILED.getCode());
            result.setLastError(exception.getMessage());
            return result;
        } catch (RuntimeException exception) {
            log.warn("OpenMetadata connect-test failed: baseUrl={}, type={}, message={}",
                    sanitizeUrl(config.getBaseUrl()),
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
            result.setConfigured(false);
            result.setConnStatus(ConnStatus.CONNECTED_FAILED.getCode());
            result.setLastError("OpenMetadata 连接失败，请检查地址、Token 与版本");
            return result;
        }
    }

    private ValidConfig validate(OpenMetadataServerConfigDTO request, boolean allowExistingToken) {
        if (request == null) {
            throw invalid("request");
        }
        String baseUrl = StringUtils.trimToNull(request.getBaseUrl());
        OpenMetadataServerConfig existing = configDao.querySingleton();
        if (baseUrl == null && existing != null) {
            baseUrl = existing.getBaseUrl();
        }
        OpenMetadataConfigResolver.validateBaseUrl(baseUrl);

        if (!allowExistingToken && StringUtils.isBlank(request.getToken()) && existing == null) {
            throw invalid("token");
        }

        int connectTimeoutMs = request.getConnectTimeoutMs() == null
                ? (existing == null || existing.getConnectTimeoutMs() == null
                        ? DEFAULT_CONNECT_TIMEOUT_MS
                        : existing.getConnectTimeoutMs())
                : request.getConnectTimeoutMs();
        int readTimeoutMs = request.getReadTimeoutMs() == null
                ? (existing == null || existing.getReadTimeoutMs() == null
                        ? DEFAULT_READ_TIMEOUT_MS
                        : existing.getReadTimeoutMs())
                : request.getReadTimeoutMs();
        if (connectTimeoutMs <= 0 || connectTimeoutMs > 120_000) {
            throw invalid("connectTimeoutMs");
        }
        if (readTimeoutMs <= 0 || readTimeoutMs > 300_000) {
            throw invalid("readTimeoutMs");
        }
        return new ValidConfig(baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    private static OpenMetadataRuntimeConfig toRuntime(
            ValidConfig valid, String token, OpenMetadataServerConfig current) {
        long version = current == null || current.getConfigVersion() == null
                ? 0L
                : current.getConfigVersion();
        return new OpenMetadataRuntimeConfig(
                valid.baseUrl(),
                token,
                valid.connectTimeoutMs(),
                valid.readTimeoutMs(),
                OpenMetadataRuntimeConfig.DEFAULT_SERVER_VERSION,
                OpenMetadataRuntimeConfig.DEFAULT_INGESTION_PATCH,
                version);
    }

    public static String resolveToken(OpenMetadataServerConfig current, String requestedToken) {
        return StringUtils.isBlank(requestedToken)
                ? current == null ? null : current.getToken()
                : requestedToken;
    }

    private static OpenMetadataServerConfigVO toVO(OpenMetadataServerConfig config) {
        if (config == null) {
            return null;
        }
        OpenMetadataServerConfigVO result = new OpenMetadataServerConfigVO();
        result.setBaseUrl(config.getBaseUrl());
        result.setTokenConfigured(StringUtils.isNotBlank(config.getToken()));
        result.setConnectTimeoutMs(config.getConnectTimeoutMs());
        result.setReadTimeoutMs(config.getReadTimeoutMs());
        result.setExpectedServerVersion(config.getExpectedServerVersion());
        result.setExpectedIngestionPatch(config.getExpectedIngestionPatch());
        result.setConfigVersion(config.getConfigVersion());
        result.setConnStatus(config.getConnStatus() == null ? null : config.getConnStatus().getCode());
        result.setLastError(config.getLastError());
        result.setConfigured(StringUtils.isNotBlank(config.getBaseUrl())
                && StringUtils.isNotBlank(config.getToken()));
        return result;
    }

    private static OpenMetadataServerConfigVO emptyVo() {
        OpenMetadataServerConfigVO result = new OpenMetadataServerConfigVO();
        result.setTokenConfigured(false);
        result.setConfigured(false);
        result.setExpectedServerVersion(OpenMetadataRuntimeConfig.DEFAULT_SERVER_VERSION);
        result.setExpectedIngestionPatch(OpenMetadataRuntimeConfig.DEFAULT_INGESTION_PATCH);
        result.setConnectTimeoutMs(DEFAULT_CONNECT_TIMEOUT_MS);
        result.setReadTimeoutMs(DEFAULT_READ_TIMEOUT_MS);
        result.setConnStatus(ConnStatus.CONNECTED_NONE.getCode());
        return result;
    }

    private int currentUserId() {
        Integer id = currentUserProvider.getCurrentUserId();
        return id == null || id <= 0 ? 1 : id;
    }

    private static MetadataIntegrationException invalid(String field) {
        return new MetadataIntegrationException(
                MetadataErrorCode.OM_CONNECTION_ERROR,
                "OpenMetadata 配置参数无效：" + field);
    }

    public static String sanitizeUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl.trim());
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();
            return (uri.getScheme() == null ? "" : uri.getScheme() + "://")
                    + (host == null ? baseUrl : host)
                    + (port < 0 ? "" : ":" + port)
                    + (path == null ? "" : path);
        } catch (IllegalArgumentException ignored) {
            return baseUrl.toLowerCase(Locale.ROOT).replaceAll("(?i)(token|password)=[^&]*", "$1=***");
        }
    }

    private record ValidConfig(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
    }
}
