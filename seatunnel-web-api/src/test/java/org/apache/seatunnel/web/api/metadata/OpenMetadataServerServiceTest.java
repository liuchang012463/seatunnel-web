package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.service.impl.OpenMetadataServerServiceImpl;

import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationHealthService;
import org.apache.seatunnel.web.api.metadata.OpenMetadataConfigResolver;
import org.apache.seatunnel.web.api.metadata.OpenMetadataRuntimeConfig;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.dao.entity.OpenMetadataServerConfig;
import org.apache.seatunnel.web.dao.repository.OpenMetadataServerConfigDao;
import org.apache.seatunnel.web.spi.bean.dto.OpenMetadataServerConfigDTO;
import org.apache.seatunnel.web.spi.bean.vo.MetadataIntegrationHealthVO;
import org.apache.seatunnel.web.spi.bean.vo.OpenMetadataServerConfigVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenMetadataServerServiceTest {

    @Test
    void getConfigDoesNotExposeToken() {
        OpenMetadataServerConfigDao dao = mock(OpenMetadataServerConfigDao.class);
        OpenMetadataServerConfig row = sampleRow();
        when(dao.querySingleton()).thenReturn(row);
        MetadataIntegrationHealthService health = mock(MetadataIntegrationHealthService.class);
        when(health.health()).thenReturn(new MetadataIntegrationHealthVO());

        OpenMetadataServerConfigVO vo = service(dao, health).getConfig();

        assertTrue(vo.isTokenConfigured());
        assertTrue(vo.isConfigured());
        assertEquals("http://om.example/api", vo.getBaseUrl());
    }

    @Test
    void resolveTokenKeepsExistingWhenBlank() {
        OpenMetadataServerConfig current = sampleRow();
        assertEquals("secret-token", OpenMetadataServerServiceImpl.resolveToken(current, "  "));
        assertEquals("new-token", OpenMetadataServerServiceImpl.resolveToken(current, "new-token"));
    }

    @Test
    void sanitizeUrlStripsQuery() {
        assertEquals(
                "http://om.example:8585/api",
                OpenMetadataServerServiceImpl.sanitizeUrl("http://om.example:8585/api?token=secret"));
    }

    @Test
    void saveRejectsInvalidBaseUrlWithoutPersisting() {
        OpenMetadataServerConfigDao dao = mock(OpenMetadataServerConfigDao.class);
        when(dao.querySingleton()).thenReturn(null);
        MetadataIntegrationHealthService health = mock(MetadataIntegrationHealthService.class);
        OpenMetadataServerConfigDTO request = new OpenMetadataServerConfigDTO();
        request.setEnabled(true);
        request.setBaseUrl("http://localhost:8082/api");
        request.setToken("token");

        assertThrows(MetadataIntegrationException.class,
                () -> service(dao, health).saveConfig(request));
        verify(dao, never()).insert(any());
    }

    private static OpenMetadataServerServiceImpl service(
            OpenMetadataServerConfigDao dao, MetadataIntegrationHealthService health) {
        OpenMetadataConfigResolver resolver = OpenMetadataConfigResolver.fixed(
                OpenMetadataRuntimeConfig.disabledPlaceholder());
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.getCurrentUserId()).thenReturn(1);
        return new OpenMetadataServerServiceImpl(dao, resolver, health, users);
    }

    private static OpenMetadataServerConfig sampleRow() {
        OpenMetadataServerConfig row = new OpenMetadataServerConfig();
        row.setId(1L);
        row.setConfigKey("OPENMETADATA");
        row.setEnabled(true);
        row.setBaseUrl("http://om.example/api");
        row.setToken("secret-token");
        row.setConnectTimeoutMs(2000);
        row.setReadTimeoutMs(10000);
        row.setExpectedServerVersion(OpenMetadataRuntimeConfig.DEFAULT_SERVER_VERSION);
        row.setExpectedIngestionPatch(OpenMetadataRuntimeConfig.DEFAULT_INGESTION_PATCH);
        row.setConfigVersion(3L);
        row.setConnStatus(ConnStatus.CONNECTED_SUCCESS);
        return row;
    }
}
