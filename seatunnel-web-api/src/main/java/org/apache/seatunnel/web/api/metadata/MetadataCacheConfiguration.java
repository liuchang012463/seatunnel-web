package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Selects the metadata cache implementation.  {@code metadata.inventory
 * .cache-type=redis} activates the shared Redis cache (the production
 * default through environment configuration); {@code memory} keeps the
 * process-local cache used by unit tests and lightweight deployments.
 */
@Configuration
public class MetadataCacheConfiguration {

    @Bean
    @ConditionalOnProperty(name = "metadata.inventory.cache-type", havingValue = "redis")
    public StringRedisMetadataStore metadataRedisStore(
            StringRedisTemplate redisTemplate, ObjectMapper mapper) {
        return new StringRedisMetadataStore(redisTemplate, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "metadata.inventory.cache-type", havingValue = "redis")
    public MetadataInventoryCache metadataInventoryCache(
            StringRedisMetadataStore store,
            @Value("${metadata.inventory.soft-ttl-ms:300000}") long softTtlMs,
            @Value("${metadata.inventory.stale-ttl-ms:1800000}") long staleTtlMs) {
        return new RedisMetadataInventoryCache(store, softTtlMs, staleTtlMs);
    }

    @Bean
    @ConditionalOnProperty(
            name = "metadata.inventory.cache-type",
            havingValue = "memory",
            matchIfMissing = true)
    public MetadataInventoryCache localMetadataInventoryCache() {
        return new LocalMetadataInventoryCache();
    }

    @Bean
    @ConditionalOnProperty(name = "metadata.inventory.cache-type", havingValue = "redis")
    public OmReadCache omReadCache(
            StringRedisMetadataStore store,
            @Value("${metadata.om-read-cache.enabled:true}") boolean enabled,
            @Value("${metadata.om-read-cache.page-ttl-ms:600000}") long pageTtlMs,
            @Value("${metadata.om-read-cache.profile-ttl-ms:900000}") long profileTtlMs) {
        if (!enabled) {
            return OmReadCache.disabled();
        }
        return OmReadCache.redis(store, Duration.ofMillis(pageTtlMs), Duration.ofMillis(profileTtlMs));
    }

    @Bean
    @ConditionalOnProperty(
            name = "metadata.inventory.cache-type",
            havingValue = "memory",
            matchIfMissing = true)
    public OmReadCache disabledOmReadCache() {
        return OmReadCache.disabled();
    }
}
