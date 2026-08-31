package org.apache.seatunnel.web.api.lake.table;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.lake.LakeProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues short-lived, signed and one-time preview tokens.
 *
 * <p>The signed payload contains the complete server-generated contract, not
 * a client DDL string.  A create request therefore has enough information to
 * rebuild the contract while still being unable to alter it without knowing
 * the HMAC key.  The process-local consume set gives the token explicit
 * one-time/replay semantics; deployments with several Web instances should
 * configure the same secret and route token consumption through a shared
 * store in a future infrastructure change.</p>
 */
public final class LakePreviewTokenService {

    private static final String TOKEN_KIND = "MANAGED_TABLE_PREVIEW";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Clock clock;
    private final long ttlSeconds;
    private final Map<String, Long> consumed = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LakePreviewTokenService(LakeProperties properties) {
        this(properties, Clock.systemUTC(), properties == null ? null : properties.getPreviewTokenSecret());
    }

    /** Visible for deterministic token tests. */
    public LakePreviewTokenService(LakeProperties properties, Clock clock, String configuredSecret) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.secret = secret(configuredSecret);
        Duration ttl = properties == null ? null : properties.getPreviewTokenTtl();
        this.ttlSeconds = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Duration.ofMinutes(5).toSeconds() : Math.max(1, ttl.toSeconds());
    }

    public String issue(
            Integer userId,
            Long sourceDataSourceId,
            String omEntityId,
            Long odsDatabaseBindingId,
            Long mappingId,
            String targetTableName,
            String sourceSchemaHash,
            String targetContractHash,
            String targetContractJson,
            String fieldMappingsJson) {
        long expiresAt = Instant.now(clock).getEpochSecond() + ttlSeconds;
        Payload payload = new Payload(
                TOKEN_KIND, userId, sourceDataSourceId, omEntityId, odsDatabaseBindingId,
                mappingId, targetTableName, sourceSchemaHash, targetContractHash,
                targetContractJson, fieldMappingsJson, expiresAt, UUID.randomUUID().toString());
        validatePayload(payload, null);
        try {
            String body = ENCODER.encodeToString(MAPPER.writeValueAsBytes(payload));
            return body + "." + ENCODER.encodeToString(sign(body));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Preview token could not be issued");
        }
    }

    /** Verifies signature, expiry, user binding and one-time state. */
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
            if (!java.security.MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException();
            }
            Payload payload = MAPPER.readValue(DECODER.decode(parts[0]), Payload.class);
            validatePayload(payload, currentUserId);
            if (payload.expiresAt() <= Instant.now(clock).getEpochSecond()) {
                throw new IllegalArgumentException();
            }
            Long consumedUntil = consumed.get(token);
            if (consumedUntil != null && consumedUntil > Instant.now(clock).getEpochSecond()) {
                throw new IllegalArgumentException();
            }
            return payload;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Preview token is invalid");
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
            throw new IllegalStateException("Preview token signing is unavailable");
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

    private static void validatePayload(Payload payload, Integer currentUserId) {
        if (payload == null || !TOKEN_KIND.equals(payload.kind())
                || payload.userId() == null || payload.userId() <= 0
                || (currentUserId != null && !currentUserId.equals(payload.userId()))
                || payload.sourceDataSourceId() == null || payload.sourceDataSourceId() <= 0
                || payload.odsDatabaseBindingId() == null || payload.odsDatabaseBindingId() <= 0
                || payload.omEntityId() == null || payload.omEntityId().isBlank()
                || payload.targetTableName() == null || payload.targetTableName().isBlank()
                || payload.sourceSchemaHash() == null || payload.sourceSchemaHash().isBlank()
                || payload.targetContractHash() == null || payload.targetContractHash().isBlank()
                || payload.targetContractJson() == null || payload.targetContractJson().isBlank()
                || payload.fieldMappingsJson() == null || payload.fieldMappingsJson().isBlank()
                || payload.expiresAt() <= 0 || payload.nonce() == null || payload.nonce().isBlank()) {
            throw new IllegalArgumentException("Preview token payload is invalid");
        }
        if (payload.mappingId() != null && payload.mappingId() <= 0) {
            throw new IllegalArgumentException("Preview token mapping is invalid");
        }
    }

    /** Signed token contents; never expose a raw DDL field here. */
    public record Payload(
            String kind,
            Integer userId,
            Long sourceDataSourceId,
            String omEntityId,
            Long odsDatabaseBindingId,
            Long mappingId,
            String targetTableName,
            String sourceSchemaHash,
            String targetContractHash,
            String targetContractJson,
            String fieldMappingsJson,
            long expiresAt,
            String nonce) {
    }
}
