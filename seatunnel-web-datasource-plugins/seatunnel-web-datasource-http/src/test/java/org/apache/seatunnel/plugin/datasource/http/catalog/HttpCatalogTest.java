package org.apache.seatunnel.plugin.datasource.http.catalog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.seatunnel.plugin.datasource.http.param.HttpAuthenticationType;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpCatalogTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openapi.json", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer catalog-token".equals(authorization)) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            respond(exchange, 200, "{\"openapi\":\"3.0.0\",\"paths\":{"
                    + "\"/pets\":{\"get\":{\"summary\":\"List pets\"}}}}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void fetchesConfiguredDocumentWithExistingAuthenticationAndReturnsOperations() {
        HttpConnectionParam param = new HttpConnectionParam();
        param.setBaseUrl(baseUrl);
        param.setOpenApiSpecUrl(baseUrl + "/openapi.json");
        param.setAuthenticationType(HttpAuthenticationType.BEARER);
        param.setBearerToken("catalog-token");

        List<OptionVO> options = new HttpCatalog(param).listOptions();

        assertEquals(1, options.size());
        assertEquals("GET /pets", options.get(0).getValue());
        assertEquals("List pets", options.get(0).getDescription());
    }

    @Test
    void returnsEmptyOptionsWithoutDocumentUrl() {
        HttpConnectionParam param = new HttpConnectionParam();
        param.setBaseUrl(baseUrl);

        assertTrue(new HttpCatalog(param).listOptions().isEmpty());
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
