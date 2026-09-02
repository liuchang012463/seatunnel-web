package org.apache.seatunnel.web.api.lake.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeReadOnlyQueryCancellationRegistryTest {

    @Test
    void cancellationBeforeJdbcRegistrationIsAppliedWhenQueryRegisters() {
        LakeReadOnlyQueryCancellationRegistry registry = new LakeReadOnlyQueryCancellationRegistry();

        assertTrue(registry.cancel("query-before-register"));
        try (LakeReadOnlyQueryCancellationRegistry.Registration registration =
                     registry.register("query-before-register")) {
            assertTrue(registration.cancelled());
            assertTrue(registry.isActive("query-before-register"));
        }
        assertFalse(registry.isActive("query-before-register"));
    }

    @Test
    void invalidCancellationIdDoesNotCreatePendingState() {
        LakeReadOnlyQueryCancellationRegistry registry = new LakeReadOnlyQueryCancellationRegistry();

        assertFalse(registry.cancel("not a valid query id"));
        assertFalse(registry.isActive("not a valid query id"));
    }
}
