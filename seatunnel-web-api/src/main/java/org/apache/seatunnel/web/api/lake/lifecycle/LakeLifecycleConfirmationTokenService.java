package org.apache.seatunnel.web.api.lake.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues short-lived plan fingerprints for retention decreases.
 *
 * <p>The fingerprint is a concurrency marker, not a permission or bearer
 * token.  Submission re-reads the current mapping, policy and lock versions
 * before applying a change.</p>
 */
@Component
public final class LakeLifecycleConfirmationTokenService {

    private static final String TOKEN_KIND = "LAKE_LIFECYCLE_RETENTION_DECREASE";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Clock clock;
    private final long ttlSeconds;
    private final Map<String, Payload> issued = new ConcurrentHashMap<>();
    private final Map<String, Long> consumed = new ConcurrentHashMap<>();

    @Autowired
    public LakeLifecycleConfirmationTokenService(LakeProperties properties) {
        this(properties, Clock.systemUTC(), null);
    }

    /** Visible for deterministic token tests. */
    public LakeLifecycleConfirmationTokenService(
            LakeProperties properties, Clock clock, String configuredSecret) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
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
            String fingerprint = fingerprint(body);
            issued.put(fingerprint, payload);
            return fingerprint;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Lifecycle confirmation token could not be issued");
        }
    }

    /** Resolves a fingerprinted plan and checks only expiry/replay state. */
    public Payload verify(String token, Integer currentUserId) {
        cleanup();
        try {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException();
            }
            Payload payload = issued.get(token);
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
        issued.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private static String fingerprint(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Plan fingerprint is unavailable");
        }
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
