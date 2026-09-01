package org.apache.seatunnel.web.api.lake.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues short-lived, user-bound and one-time tokens for retention decreases.
 *
 * <p>The payload contains only immutable lifecycle identities and a hash of
 * the observed impact.  It never contains Doris credentials, SQL, or table
 * properties.  Consumption is process-local, matching the existing preview
 * token boundary; a shared deployment should move the consumed nonce to a
 * shared store before enabling cross-node token consumption.</p>
 */
@Component
public final class LakeLifecycleConfirmationTokenService {

    private static final String TOKEN_KIND = "LAKE_LIFECYCLE_RETENTION_DECREASE";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Clock clock;
    private final long ttlSeconds;
    private final Map<String, Long> consumed = new ConcurrentHashMap<>();

    @Autowired
    public LakeLifecycleConfirmationTokenService(LakeProperties properties) {
        this(properties, Clock.systemUTC(), properties == null ? null : properties.getPreviewTokenSecret());
    }

    /** Visible for deterministic token tests. */
    public LakeLifecycleConfirmationTokenService(
            LakeProperties properties, Clock clock, String configuredSecret) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.secret = secret(configuredSecret(properties, configuredSecret));
        Duration ttl = properties == null ? null : properties.getPreviewTokenTtl();
        this.ttlSeconds = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Duration.ofMinutes(5).toSeconds() : Math.max(1, ttl.toSeconds());
    }

    /** Creates a signed payload whose expiry is controlled by server settings. */
    public String issue(
            Integer userId,
            Long mappingId,
            Integer mappingGeneration,
            Integer mappingLockVersion,
            Long bindingId,
            Integer bindingGeneration,
            Integer bindingLockVersion,
            Integer currentDesiredRetentionCount,
            Long policyId,
            Integer policyVersion,
            Integer newRetentionCount,
            String observedImpactHash) {
        long expiresAt = Instant.now(clock).getEpochSecond() + ttlSeconds;
        Payload payload = new Payload(
                TOKEN_KIND, userId, mappingId, mappingGeneration, mappingLockVersion,
                bindingId, bindingGeneration, bindingLockVersion,
                currentDesiredRetentionCount, policyId, policyVersion,
                newRetentionCount, observedImpactHash, expiresAt, UUID.randomUUID().toString());
        validatePayload(payload, null);
        try {
            String body = ENCODER.encodeToString(MAPPER.writeValueAsBytes(payload));
            return body + "." + ENCODER.encodeToString(sign(body));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Lifecycle confirmation token could not be issued");
        }
    }

    /** Verifies signature, expiry and user binding without consuming the token. */
    public Payload verify(String token, Integer currentUserId) {
        cleanup();
        try {
            if (token == null || token.isBlank() || currentUserId == null || currentUserId <= 0) {
                throw new IllegalArgumentException();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException();
            }
            byte[] expected = sign(parts[0]);
            byte[] actual = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException();
            }
            Payload payload = MAPPER.readValue(DECODER.decode(parts[0]), Payload.class);
            validatePayload(payload, currentUserId);
            if (payload.expiresAt() <= Instant.now(clock).getEpochSecond()
                    || consumed.containsKey(token)) {
                throw new IllegalArgumentException();
            }
            return payload;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Lifecycle confirmation token is invalid");
        }
    }

    /** Atomically consumes a previously verified token. */
    public boolean consume(String token, Payload payload) {
        if (token == null || token.isBlank() || payload == null) {
            return false;
        }
        cleanup();
        long now = Instant.now(clock).getEpochSecond();
        if (payload.expiresAt() <= now) {
            return false;
        }
        return consumed.putIfAbsent(token, payload.expiresAt()) == null;
    }

    private void cleanup() {
        long now = Instant.now(clock).getEpochSecond();
        consumed.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Lifecycle confirmation signing is unavailable");
        }
    }

    private static byte[] secret(String configuredSecret) {
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            return configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        return generated;
    }

    private static String configuredSecret(LakeProperties properties, String configuredSecret) {
        String effective = configuredSecret;
        if (effective == null || effective.isBlank()) {
            effective = properties == null ? null : properties.getPreviewTokenSecret();
        }
        if (properties != null && properties.isEnabled()
                && (effective == null || effective.isBlank())) {
            throw new IllegalStateException(
                    "Lake lifecycle confirmation token secret is required when lake control plane is enabled");
        }
        return effective;
    }

    private static void validatePayload(Payload payload, Integer currentUserId) {
        if (payload == null || !TOKEN_KIND.equals(payload.kind())
                || payload.userId() == null || payload.userId() <= 0
                || (currentUserId != null && !currentUserId.equals(payload.userId()))
                || positive(payload.mappingId()) == false
                || positive(payload.mappingGeneration()) == false
                || positive(payload.mappingLockVersion()) == false
                || positive(payload.bindingId()) == false
                || positive(payload.bindingGeneration()) == false
                || positive(payload.bindingLockVersion()) == false
                || positive(payload.currentDesiredRetentionCount()) == false
                || positive(payload.policyId()) == false
                || positive(payload.policyVersion()) == false
                || positive(payload.newRetentionCount()) == false
                || payload.observedImpactHash() == null
                || payload.observedImpactHash().isBlank()
                || payload.observedImpactHash().length() > 128
                || payload.expiresAt() <= 0
                || payload.nonce() == null || payload.nonce().isBlank()) {
            throw new IllegalArgumentException("Lifecycle confirmation payload is invalid");
        }
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    /** Signed contents used by the retention update CAS. */
    public record Payload(
            String kind,
            Integer userId,
            Long mappingId,
            Integer mappingGeneration,
            Integer mappingLockVersion,
            Long bindingId,
            Integer bindingGeneration,
            Integer bindingLockVersion,
            Integer currentDesiredRetentionCount,
            Long policyId,
            Integer policyVersion,
            Integer newRetentionCount,
            String observedImpactHash,
            long expiresAt,
            String nonce) {
    }
}
