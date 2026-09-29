package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin string-value Redis facade shared by the metadata cache components.
 * Every Redis failure degrades to "cache miss" so an unavailable Redis can
 * never break the metadata APIs; the warning is rate limited.
 */
@Slf4j
public class StringRedisMetadataStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final AtomicLong lastFailureLog = new AtomicLong();

    public StringRedisMetadataStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public Optional<String> getString(String key) {
        return call(() -> Optional.ofNullable(redis.opsForValue().get(key)), Optional.empty());
    }

    public void putString(String key, String value, Duration ttl) {
        call(() -> {
            redis.opsForValue().set(key, value, ttl);
            return null;
        }, null);
    }

    public long increment(String key) {
        return call(() -> redis.opsForValue().increment(key), 0L);
    }

    public long current(String key) {
        String value = getString(key).orElse(null);
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public <T> Optional<String> writeJson(T value) {
        try {
            return Optional.of(mapper.writeValueAsString(value));
        } catch (JsonProcessingException error) {
            log.warn("Metadata cache value serialization failed: type={}",
                    error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Serializes {@code value} and stores it under {@code key} when serializable. */
    public void writeJson(Object value, String key, Duration ttl) {
        writeJson(value).ifPresent(json -> putString(key, json, ttl));
    }

    public <T> Optional<T> readJson(String json, JavaType type) {
        try {
            return Optional.ofNullable(mapper.readValue(json, type));
        } catch (Exception error) {
            log.warn("Metadata cache value deserialization failed: type={}",
                    error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private <T> T call(java.util.function.Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (org.springframework.dao.DataAccessException error) {
            warnThrottled(error);
            return fallback;
        }
    }

    private void warnThrottled(Exception error) {
        long now = System.currentTimeMillis();
        long last = lastFailureLog.get();
        if (now - last > 60_000L && lastFailureLog.compareAndSet(last, now)) {
            log.warn("Metadata Redis cache unavailable, serving without cache: type={}, message={}",
                    error.getClass().getSimpleName(), error.getMessage());
        }
    }
}
