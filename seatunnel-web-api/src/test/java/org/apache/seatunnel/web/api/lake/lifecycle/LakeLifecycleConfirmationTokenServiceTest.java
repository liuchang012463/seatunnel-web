package org.apache.seatunnel.web.api.lake.lifecycle;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeLifecycleConfirmationTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void tokenBindsUserAndLifecycleIdentityAndIsOneTime() {
        LakeProperties properties = new LakeProperties();
        properties.setPreviewTokenTtl(Duration.ofMinutes(5));
        LakeLifecycleConfirmationTokenService service = service(properties, Clock.fixed(NOW, ZoneOffset.UTC));

        String token = service.issue(7, 501L, 3, 4, 701L, 1, 2, 5,
                901L, 2, 2, "impact-hash");
        LakeLifecycleConfirmationTokenService.Payload payload = service.verify(token, 7);

        assertEquals(501L, payload.mappingId());
        assertEquals(701L, payload.bindingId());
        assertEquals(5, payload.currentDesiredRetentionCount());
        assertEquals(2, payload.newRetentionCount());
        assertThrows(IllegalArgumentException.class, () -> service.verify(token, 8));
        assertTrue(service.consume(token, payload));
        assertFalse(service.consume(token, payload));
        assertThrows(IllegalArgumentException.class, () -> service.verify(token, 7));
    }

    @Test
    void tamperingAndExpiryAreRejectedWithStableMessage() {
        LakeProperties properties = new LakeProperties();
        properties.setPreviewTokenTtl(Duration.ofMinutes(1));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        LakeLifecycleConfirmationTokenService service = service(properties, clock);
        String token = service.issue(7, 501L, 3, 4, 701L, 1, 2, 5,
                901L, 2, 2, "impact-hash");

        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> service.verify(tampered, 7));
        assertEquals("Lifecycle confirmation token is invalid", exception.getMessage());

        LakeLifecycleConfirmationTokenService expired = service(properties,
                Clock.offset(clock, Duration.ofMinutes(2)));
        assertThrows(IllegalArgumentException.class, () -> expired.verify(token, 7));
    }

    @Test
    void enabledLakeRequiresSecret() {
        LakeProperties properties = new LakeProperties();
        properties.setEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> new LakeLifecycleConfirmationTokenService(properties));
    }

    private static LakeLifecycleConfirmationTokenService service(
            LakeProperties properties, Clock clock) {
        return new LakeLifecycleConfirmationTokenService(properties, clock, "test-secret");
    }
}
