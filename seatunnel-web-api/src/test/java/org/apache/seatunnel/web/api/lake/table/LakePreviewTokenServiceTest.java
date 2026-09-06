package org.apache.seatunnel.web.api.lake.table;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakePreviewTokenServiceTest {

    @Test
    void planFingerprintDoesNotRequireAnAuthorizationSecret() {
        LakeProperties properties = new LakeProperties();
        properties.setEnabled(true);

        assertNotNull(new LakePreviewTokenService(properties));
        assertNotNull(new LakePreviewTokenService(properties, Clock.systemUTC(), null));
    }

    @Test
    void tokenBindsIdentityContractAndUserAndIsOneTime() {
        LakeProperties properties = new LakeProperties();
        properties.setPreviewTokenTtl(java.time.Duration.ofMinutes(5));
        LakePreviewTokenService service = new LakePreviewTokenService(
                properties, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), "test-secret");

        String token = service.issue(7, 11L, "om-table", 21L, 31L, "orders",
                "source-hash", "contract-hash", "{\"version\":2}", "[]");
        LakePreviewTokenService.Payload payload = service.verify(token, 7);

        assertEquals(11L, payload.sourceDataSourceId());
        assertEquals(21L, payload.odsDatabaseBindingId());
        assertEquals(31L, payload.mappingId());
        assertEquals("contract-hash", payload.targetContractHash());
        assertThrows(IllegalArgumentException.class, () -> service.verify(token, 8));
        assertTrue(service.consume(token, payload));
        assertFalse(service.consume(token, payload));
        assertThrows(IllegalArgumentException.class, () -> service.verify(token, 7));
    }

    @Test
    void tokenTamperingAndExpiryAreRejectedWithoutExposingPayload() {
        LakeProperties properties = new LakeProperties();
        properties.setPreviewTokenTtl(java.time.Duration.ofMinutes(1));
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        LakePreviewTokenService service = new LakePreviewTokenService(properties, clock, "test-secret");
        String token = service.issue(7, 11L, "om-table", 21L, null, "orders",
                "source-hash", "contract-hash", "contract", "mappings");

        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");
        IllegalArgumentException tamperedError = assertThrows(
                IllegalArgumentException.class, () -> service.verify(tampered, 7));
        assertEquals("Preview token is invalid", tamperedError.getMessage());

        LakePreviewTokenService expired = new LakePreviewTokenService(properties,
                Clock.offset(clock, java.time.Duration.ofMinutes(2)), "test-secret");
        assertThrows(IllegalArgumentException.class, () -> expired.verify(token, 7));
    }

    @Test
    void lifecycleCreateFieldsAreSignedAndTamperingIsRejected() {
        LakeProperties properties = new LakeProperties();
        properties.setPreviewTokenTtl(java.time.Duration.ofMinutes(5));
        LakePreviewTokenService service = new LakePreviewTokenService(
                properties, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                "test-secret");

        String token = service.issue(7, 11L, "om-table", 21L, null, "orders",
                "source-hash", "contract-hash", "contract", "mappings",
                901L, 3, "{\"policyId\":901,\"version\":3}",
                "event_time", "DAY", 7, "7");
        LakePreviewTokenService.Payload payload = service.verify(token, 7);

        assertEquals(901L, payload.lifecyclePolicyId());
        assertEquals(3, payload.lifecyclePolicyVersion());
        assertEquals("event_time", payload.lifecyclePartitionColumn());
        assertEquals("DAY", payload.lifecycleGranularity());
        assertEquals(7, payload.lifecycleRetentionCount());
        assertNotNull(payload.lifecycleIntentHash());
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");
        assertThrows(IllegalArgumentException.class, () -> service.verify(tampered, 7));
    }
}
