package org.apache.seatunnel.plugin.datasource.http.client;

import org.apache.seatunnel.plugin.datasource.http.param.HttpAuthenticationType;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpRequestSupportTest {

    @Test
    void shouldJoinRelativePathWithoutAllowingAnotherHost() {
        assertEquals(
                "https://api.example.com/v1/orders",
                HttpRequestSupport.resolveUrl("https://api.example.com/", "/v1/orders"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpRequestSupport.resolveUrl(
                        "https://api.example.com", "https://attacker.example/path"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpRequestSupport.resolveUrl(
                        "https://api.example.com", "//attacker.example/path"));
    }

    @Test
    void shouldMergeHeadersAndProtectAuthenticationHeader() {
        HttpConnectionParam param = new HttpConnectionParam();
        param.setAuthenticationType(HttpAuthenticationType.BEARER);
        param.setBearerToken("token-value");
        param.setDefaultHeaders(Map.of("Accept", "application/json", "X-Tenant", "default"));

        Map<String, String> headers =
                HttpRequestSupport.mergeHeaders(param, Map.of("X-Tenant", "node"));

        assertEquals("node", headers.get("X-Tenant"));
        assertEquals("Bearer token-value", headers.get("Authorization"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpRequestSupport.mergeHeaders(
                        param, Map.of("authorization", "Bearer override")));
    }
}
