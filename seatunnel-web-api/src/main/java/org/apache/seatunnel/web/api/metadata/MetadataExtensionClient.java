package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import io.netty.channel.ChannelOption;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Server-side adapter for the optional open_metadata_extension API.  This is
 * deliberately separate from the OpenMetadata client: ER data is built by
 * the official 1.12.10 SDK in {@link DataExplorationService}, while this
 * client only submits and observes the extension's asynchronous description
 * generation task.
 */
@Component
public class MetadataExtensionClient {

    private final MetadataExtensionProperties properties;
    private final WebClient webClient;

    public MetadataExtensionClient(MetadataExtensionProperties properties) {
        this.properties = properties;
        int connectTimeout = Math.max(500, properties.getConnectTimeoutMs());
        int readTimeout = Math.max(1000, properties.getReadTimeoutMs());
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
                .responseTimeout(Duration.ofMillis(readTimeout));
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public JsonNode startGenerate(String fullyQualifiedName) {
        requireConfigured();
        if (fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.METADATA_EXTENSION_ERROR,
                    "Metadata completion requires a table fully qualified name");
        }
        return await(
                webClient.post()
                        .uri(endpoint("generate", fullyQualifiedName))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                                "skip_existing", true,
                                "sample_rows", 50,
                                "max_concurrency", 3))
                        .exchangeToMono(this::readResponse),
                "generate");
    }

    public JsonNode getJob(String jobId) {
        requireConfigured();
        if (jobId == null || jobId.isBlank()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.METADATA_EXTENSION_ERROR,
                    "Metadata completion job id is required");
        }
        return await(
                webClient.get()
                        .uri(endpoint("jobs", jobId))
                        .exchangeToMono(this::readResponse),
                "job status");
    }

    private Mono<JsonNode> readResponse(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(JsonNode.class)
                    .switchIfEmpty(Mono.error(new MetadataIntegrationException(
                            MetadataErrorCode.METADATA_EXTENSION_ERROR,
                            "Metadata completion service returned an empty response (HTTP "
                                    + response.statusCode().value() + ")")));
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    String detail = body == null ? "" : body.trim();
                    if (detail.length() > 512) {
                        detail = detail.substring(0, 512) + "…";
                    }
                    String suffix = detail.isBlank() ? "" : ": " + detail;
                    return Mono.error(new MetadataIntegrationException(
                            MetadataErrorCode.METADATA_EXTENSION_ERROR,
                            "Metadata completion service rejected the request (HTTP "
                                    + response.statusCode().value() + ")" + suffix));
                });
    }

    private JsonNode await(Mono<JsonNode> request, String operation) {
        try {
            return request
                    .onErrorMap(error -> error instanceof MetadataIntegrationException
                            ? error
                            : new MetadataIntegrationException(
                                    MetadataErrorCode.METADATA_EXTENSION_ERROR,
                                    "Metadata completion service " + operation + " request failed"
                                            + (error.getMessage() == null || error.getMessage().isBlank()
                                            ? ""
                                            : ": " + error.getMessage()),
                                    error))
                    .block(timeout());
        } catch (MetadataIntegrationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.METADATA_EXTENSION_ERROR,
                    "Metadata completion service " + operation + " request failed"
                            + (error.getMessage() == null || error.getMessage().isBlank()
                            ? ""
                            : ": " + error.getMessage()),
                    error);
        }
    }

    private URI endpoint(String collection, String value) {
        try {
            return UriComponentsBuilder.fromUriString(normalizedBaseUrl())
                    .pathSegment(collection)
                    // The extension declares fqn as a `{path}` parameter. Keep
                    // separators in an FQN as path separators while encoding
                    // reserved characters within each path component.
                    .path("/")
                    .path(value)
                    .build()
                    .encode()
                    .toUri();
        } catch (RuntimeException error) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.METADATA_EXTENSION_ERROR,
                    "Metadata completion service URL is invalid",
                    error);
        }
    }

    private String normalizedBaseUrl() {
        return properties.getBaseUrl().trim().replaceAll("/+$", "");
    }

    private Duration timeout() {
        return Duration.ofMillis(Math.max(1000, properties.getReadTimeoutMs()));
    }

    private void requireConfigured() {
        if (!properties.isEnabled()
                || properties.getBaseUrl() == null
                || properties.getBaseUrl().isBlank()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.METADATA_EXTENSION_NOT_CONFIGURED,
                    "Metadata completion service is not configured");
        }
    }
}
