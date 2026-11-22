package org.apache.seatunnel.web.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMvcConfigTest {

    @Test
    void passwordModeDefaultsToSameOriginAndAllowsOnlyExactConfiguredOrigins() {
        CorsConfiguration sameOrigin = configuration("PASSWORD", "");
        assertTrue(sameOrigin.getAllowCredentials());
        assertNull(sameOrigin.checkOrigin("https://attacker.example"));
        assertNull(sameOrigin.getAllowedOriginPatterns());

        CorsConfiguration configured = configuration(
                "PASSWORD", "https://ui.example.com, http://localhost:8000,https://ui.example.com");
        assertEquals("https://ui.example.com", configured.checkOrigin("https://ui.example.com"));
        assertEquals("http://localhost:8000", configured.checkOrigin("http://localhost:8000"));
        assertNull(configured.checkOrigin("https://attacker.example"));
        assertEquals(2, configured.getAllowedOrigins().size());
        assertNull(configured.getAllowedOriginPatterns());
    }

    @Test
    void devBypassKeepsWildcardWithoutCredentialCors() {
        CorsConfiguration configuration = configuration("DEV_BYPASS", "");

        assertFalse(configuration.getAllowCredentials());
        assertEquals("https://any-origin.example", configuration.checkOrigin("https://any-origin.example"));
        assertEquals(java.util.List.of("*"), configuration.getAllowedOriginPatterns());
    }

    @Test
    void passwordCredentialOriginsMustBeCompleteHttpOriginsWithoutPatterns() {
        assertThrows(IllegalArgumentException.class, () -> configuration("PASSWORD", "*"));
        assertThrows(IllegalArgumentException.class, () -> configuration("PASSWORD", "https://ui.example/path"));
        assertThrows(IllegalArgumentException.class, () -> configuration("PASSWORD", "https://*.example.com"));
    }

    private static CorsConfiguration configuration(String mode, String origins) {
        WebMvcConfig config = new WebMvcConfig();
        ReflectionTestUtils.setField(config, "authenticationMode", mode);
        ReflectionTestUtils.setField(config, "allowedCredentialOrigins", origins);
        InspectableCorsRegistry registry = new InspectableCorsRegistry();
        config.addCorsMappings(registry);
        return registry.configurations().get("/**");
    }

    private static final class InspectableCorsRegistry extends CorsRegistry {
        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
