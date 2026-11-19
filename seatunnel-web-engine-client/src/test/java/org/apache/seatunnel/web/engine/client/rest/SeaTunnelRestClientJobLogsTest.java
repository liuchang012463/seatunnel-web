package org.apache.seatunnel.web.engine.client.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeaTunnelRestClientJobLogsTest {

    private static final Long CLIENT_ID = 1L;
    private static final String ENGINE_JOB_ID = "42";

    private final AtomicBoolean invalidMetadata = new AtomicBoolean();
    private final AtomicBoolean redirectLog = new AtomicBoolean();
    private final AtomicInteger redirectTargetRequests = new AtomicInteger();

    private HttpServer server;
    private SeaTunnelRestClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port + "/context";
        server.createContext("/context/logs", this::handleLogs);
        server.createContext("/context/system-monitoring-information", this::handleSystemMonitoringInformation);
        server.createContext("/redirect-target", this::handleRedirectTarget);
        server.start();

        SeaTunnelClientResolver resolver = mock(SeaTunnelClientResolver.class);
        when(resolver.resolveBaseApiUrl(CLIENT_ID)).thenReturn(baseUrl);
        when(resolver.resolveAuth(CLIENT_ID)).thenReturn(null);
        client = new SeaTunnelRestClient(new RestTemplate(), resolver);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listsValidatedNodeMetadataStreamsTheFileAndDoesNotFollowRedirects() {
        List<SeaTunnelRestClient.NodeLogFile> files = client.jobLogFiles(CLIENT_ID, ENGINE_JOB_ID);

        assertEquals(1, files.size());
        SeaTunnelRestClient.NodeLogFile file = files.get(0);
        assertEquals("127.0.0.1:" + server.getAddress().getPort(), file.node());
        assertEquals("job-42.log", file.logName());
        assertTrue(file.logLink().endsWith("/context/logs/job-42.log"));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long copied = client.writeJobLogFile(CLIENT_ID, ENGINE_JOB_ID, file, output);
        assertEquals("node log line\n".getBytes(StandardCharsets.UTF_8).length, copied);
        assertEquals("node log line\n", output.toString(StandardCharsets.UTF_8));

        redirectLog.set(true);
        ByteArrayOutputStream redirectedOutput = new ByteArrayOutputStream();
        assertEquals(0L, client.writeJobLogFile(CLIENT_ID, ENGINE_JOB_ID, file, redirectedOutput));
        assertEquals(0, redirectTargetRequests.get());
        assertEquals(0, redirectedOutput.size());
    }

    @Test
    void rejectsAFileLinkThatDoesNotMatchTheAdvertisedNode() {
        invalidMetadata.set(true);

        assertThrows(IllegalArgumentException.class, () -> client.jobLogFiles(CLIENT_ID, ENGINE_JOB_ID));
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (("/context/logs/" + ENGINE_JOB_ID).equals(path)) {
            String host = "127.0.0.1:" + server.getAddress().getPort();
            String link = invalidMetadata.get()
                    ? "http://example.invalid:8080/context/logs/job-42.log"
                    : baseUrl + "/logs/job-42.log";
            String metadata = "[{\"node\":\"" + host
                    + "\",\"logLink\":\"" + link
                    + "\",\"logName\":\"job-42.log\"}]";
            respond(exchange, 200, "application/json", metadata);
            return;
        }

        if (("/context/logs/job-42.log").equals(path) && redirectLog.get()) {
            exchange.getResponseHeaders().set("Location", baseUrl + "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        respond(exchange, 200, "text/plain", "node log line\n");
    }

    private void handleRedirectTarget(HttpExchange exchange) throws IOException {
        redirectTargetRequests.incrementAndGet();
        respond(exchange, 200, "text/plain", "redirect target must not be requested");
    }

    private void handleSystemMonitoringInformation(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "application/json", "[{\"host\":\"127.0.0.1\",\"port\":\"5801\"}]");
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
