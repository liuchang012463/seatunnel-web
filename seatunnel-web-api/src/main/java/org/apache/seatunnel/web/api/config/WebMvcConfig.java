package org.apache.seatunnel.web.api.config;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${security.authentication.mode:DEV_BYPASS}")
    private String authenticationMode = "DEV_BYPASS";

    @Value("${security.cors.allowed-origins:}")
    private String allowedCredentialOrigins = "";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        CorsRegistration cors = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
        if (StringUtils.equalsIgnoreCase("DEV_BYPASS", authenticationMode)) {
            cors.allowedOriginPatterns("*").allowCredentials(false);
            return;
        }
        List<String> origins = parseAllowedCredentialOrigins(allowedCredentialOrigins);
        // CorsRegistry starts with permit-all defaults; clear its wildcard even when the list is empty.
        cors.allowedOrigins(origins.toArray(String[]::new));
        cors.allowCredentials(true);
    }

    static List<String> parseAllowedCredentialOrigins(String configuredOrigins) {
        if (StringUtils.isBlank(configuredOrigins)) {
            return List.of();
        }
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .forEach(origin -> {
                    validateCredentialOrigin(origin);
                    origins.add(origin);
                });
        return List.copyOf(origins);
    }

    private static void validateCredentialOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (origin.contains("*") || scheme == null || host == null
                    || !("http".equals(scheme) || "https".equals(scheme))
                    || !scheme.equals(scheme.toLowerCase(Locale.ROOT))
                    || !host.equals(host.toLowerCase(Locale.ROOT))
                    || uri.getRawUserInfo() != null
                    || StringUtils.isNotEmpty(uri.getRawPath())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
                    || !origin.equals(scheme + "://" + uri.getRawAuthority())) {
                throw new IllegalArgumentException("CORS origin must be a complete HTTP(S) origin: " + origin);
            }
        } catch (IllegalArgumentException invalidOrigin) {
            throw new IllegalArgumentException(
                    "Invalid credential CORS origin; configure an exact HTTP(S) origin without path or wildcard",
                    invalidOrigin);
        }
    }
}
