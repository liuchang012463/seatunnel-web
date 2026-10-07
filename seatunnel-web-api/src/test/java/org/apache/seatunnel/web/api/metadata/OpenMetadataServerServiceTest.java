package org.apache.seatunnel.web.api.metadata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.MetadataBindingCommandService;
import org.apache.seatunnel.web.api.service.impl.OpenMetadataServerServiceImpl;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.dao.entity.OpenMetadataServerConfig;
import org.apache.seatunnel.web.dao.repository.OpenMetadataServerConfigDao;
import org.apache.seatunnel.web.spi.bean.dto.OpenMetadataServerConfigDTO;
import org.apache.seatunnel.web.spi.bean.vo.MetadataIntegrationHealthVO;
import org.apache.seatunnel.web.spi.bean.vo.OpenMetadataServerConfigVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenMetadataServerServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

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
        request.setBaseUrl("http://localhost:8082/api");
        request.setToken("token");

        assertThrows(MetadataIntegrationException.class,
                () -> service(dao, health).saveConfig(request));
        verify(dao, never()).insert(any());
    }

    @Test
    void explicitRebuildRequeuesBindingsWhenNewInstanceKeepsTheSameBaseUrl() throws Exception {
        String baseUrl = startHealthyServer();
        OpenMetadataServerConfig current = sampleRow();
        current.setBaseUrl(baseUrl);
        OpenMetadataServerConfigDao dao = mock(OpenMetadataServerConfigDao.class);
        when(dao.querySingleton()).thenReturn(current, current, current);
        MetadataIntegrationHealthService health = mock(MetadataIntegrationHealthService.class);
        when(health.health()).thenReturn(new MetadataIntegrationHealthVO());
        MetadataBindingCommandService bindings = mock(MetadataBindingCommandService.class);
        when(bindings.resetForOpenMetadataInstanceChange()).thenReturn(3);
        OpenMetadataServerConfigDTO request = new OpenMetadataServerConfigDTO();
        request.setBaseUrl(baseUrl);
        request.setRebuildBindings(true);

        service(dao, health, bindings).saveConfig(request);

        verify(bindings).resetForOpenMetadataInstanceChange();
        verify(dao).updateSingleton(any(OpenMetadataServerConfig.class));
    }

    @Test
    void tokenRotationDoesNotResetBindingsWhenBaseUrlIsUnchanged() throws Exception {
        String baseUrl = startHealthyServer();
        OpenMetadataServerConfig current = sampleRow();
        current.setBaseUrl(baseUrl);
        OpenMetadataServerConfigDao dao = mock(OpenMetadataServerConfigDao.class);
        when(dao.querySingleton()).thenReturn(current, current, current);
        MetadataIntegrationHealthService health = mock(MetadataIntegrationHealthService.class);
        when(health.health()).thenReturn(new MetadataIntegrationHealthVO());
        MetadataBindingCommandService bindings = mock(MetadataBindingCommandService.class);
        OpenMetadataServerConfigDTO request = new OpenMetadataServerConfigDTO();
        request.setBaseUrl(baseUrl);
        request.setToken("rotated-token");

        service(dao, health, bindings).saveConfig(request);

        verify(bindings, never()).resetForOpenMetadataInstanceChange();
    }

    private static OpenMetadataServerServiceImpl service(
            OpenMetadataServerConfigDao dao, MetadataIntegrationHealthService health) {
        return service(dao, health, mock(MetadataBindingCommandService.class));
    }

    private static OpenMetadataServerServiceImpl service(
            OpenMetadataServerConfigDao dao,
            MetadataIntegrationHealthService health,
            MetadataBindingCommandService bindings) {
        OpenMetadataConfigResolver resolver = OpenMetadataConfigResolver.fixed(
                OpenMetadataRuntimeConfig.notConfigured());
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.getCurrentUserId()).thenReturn(1);
        return new OpenMetadataServerServiceImpl(dao, resolver, health, users, bindings);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String startHealthyServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/system/version",
                exchange -> respond(exchange, 200, "{\"version\":\"2.0.4\"}"));
        server.createContext("/api/v1/services/ingestionPipelines/status",
                exchange -> respond(exchange, 200,
                        "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"2.0.4.0\"}"));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
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
