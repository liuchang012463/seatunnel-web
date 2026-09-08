package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.dao.entity.OpenMetadataServerConfig;
import org.apache.seatunnel.web.dao.repository.OpenMetadataServerConfigDao;
import org.apache.seatunnel.web.dao.repository.impl.OpenMetadataServerConfigDaoImpl;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time bootstrap: when the OM config table is empty and env still carries
 * METADATA_OPENMETADATA_* values, seed the singleton row so existing installs
 * keep working after the ops UI becomes the source of truth.
 */
@Slf4j
@Component
public class OpenMetadataConfigSeedRunner {

    private final OpenMetadataServerConfigDao configDao;
    private final OpenMetadataProperties bootstrapProperties;
    private final OpenMetadataConfigResolver configResolver;

    public OpenMetadataConfigSeedRunner(
            OpenMetadataServerConfigDao configDao,
            OpenMetadataProperties bootstrapProperties,
            OpenMetadataConfigResolver configResolver) {
        this.configDao = configDao;
        this.bootstrapProperties = bootstrapProperties;
        this.configResolver = configResolver;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(rollbackFor = Exception.class)
    public void seedIfEmpty() {
        if (configDao.querySingleton() != null) {
            return;
        }
        String baseUrl = StringUtils.trimToNull(
                bootstrapProperties == null ? null : bootstrapProperties.getBaseUrl());
        String token = StringUtils.trimToNull(
                bootstrapProperties == null ? null : bootstrapProperties.getToken());
        if (baseUrl == null || token == null) {
            log.info("OpenMetadata server config table is empty and env has no seed values");
            return;
        }
        try {
            OpenMetadataConfigResolver.validateBaseUrl(baseUrl);
        } catch (MetadataIntegrationException exception) {
            log.warn("Skip OpenMetadata env seed: invalid baseUrl={}", sanitize(baseUrl));
            return;
        }
        OpenMetadataServerConfig row = new OpenMetadataServerConfig();
        row.initInsert();
        row.setConfigKey(OpenMetadataServerConfigDaoImpl.CONFIG_KEY);
        row.setEnabled(bootstrapProperties.isEnabled());
        row.setBaseUrl(baseUrl);
        row.setToken(token);
        row.setConnectTimeoutMs(bootstrapProperties.getConnectTimeoutMs());
        row.setReadTimeoutMs(bootstrapProperties.getReadTimeoutMs());
        row.setExpectedServerVersion(
                StringUtils.defaultIfBlank(
                        bootstrapProperties.getExpectedServerVersion(),
                        OpenMetadataRuntimeConfig.DEFAULT_SERVER_VERSION));
        row.setExpectedIngestionPatch(
                StringUtils.defaultIfBlank(
                        bootstrapProperties.getExpectedIngestionPatch(),
                        OpenMetadataRuntimeConfig.DEFAULT_INGESTION_PATCH));
        row.setKingbaseTunnelHost(bootstrapProperties.getKingbaseTunnelHost());
        row.setKingbaseTunnelPort(bootstrapProperties.getKingbaseTunnelPort());
        row.setConfigVersion(1L);
        row.setConnStatus(ConnStatus.CONNECTED_NONE);
        row.setCreateUserId(1);
        row.setUpdateUserId(1);
        configDao.insert(row);
        configResolver.invalidate();
        log.info("Seeded OpenMetadata server config from env: enabled={}, baseUrl={}",
                row.getEnabled(), sanitize(baseUrl));
    }

    private static String sanitize(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        int query = baseUrl.indexOf('?');
        return query < 0 ? baseUrl : baseUrl.substring(0, query);
    }
}
