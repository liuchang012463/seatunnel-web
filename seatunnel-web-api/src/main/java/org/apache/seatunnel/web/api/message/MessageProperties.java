package org.apache.seatunnel.web.api.message;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the generic message push/pull endpoints.
 *
 * <p>
 * Bound from the {@code seatunnel.message.*} block. Every value has an
 * environment-variable override so operators can tune the service without
 * rebuilding, and so the API key never has to be committed to
 * {@code application.yml}.
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "seatunnel.message")
public class MessageProperties {

    /**
     * Shared secret required in the {@code X-Api-Key} header.
     *
     * <p><b>Blank means authentication is disabled entirely</b> (design
     * decision F1). That keeps local development frictionless, but it also
     * means an unconfigured deployment exposes these endpoints to anyone who
     * can reach them. Production deployments must set
     * {@code SEATUNNEL_MESSAGE_API_KEY}.</p>
     */
    private String apiKey = "";

    /**
     * Maximum accepted payload size in bytes. Default 1 MB (decision D2).
     */
    private int maxBodyBytes = 1024 * 1024;

    /**
     * Hard ceiling on messages returned by one pull, regardless of what the
     * caller asked for. Default 100 (decision B1).
     */
    private int maxBatchSize = 100;

    /**
     * Default pull deadline in milliseconds when the caller omits one.
     */
    private long pullDefaultTimeoutMs = 3000L;

    /**
     * Maximum JSON nesting depth accepted in a payload. Default 200, down from
     * Jackson's own 1000, to bound recursion cost on attacker-controlled
     * structure (decision D1).
     */
    private int maxNestingDepth = 200;

    /**
     * Maximum length of a single JSON string token.
     */
    private int maxStringLength = 1024 * 1024;

    /**
     * Maximum length of a single JSON number token.
     */
    private int maxNumberLength = 1000;

    /**
     * Whether authentication is active.
     */
    public boolean isAuthEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
