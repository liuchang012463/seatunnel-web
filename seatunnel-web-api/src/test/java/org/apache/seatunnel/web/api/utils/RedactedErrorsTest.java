package org.apache.seatunnel.web.api.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactedErrorsTest {

    @Test
    void masksCredentialsBeforeReturningConnectionFailureText() {
        String masked = RedactedErrors.mask(
                "connection failed: password=example-password jdbc:postgresql://user:example-secret@host/db");

        assertFalse(masked.contains("example-password"));
        assertFalse(masked.contains("example-secret"));
        assertTrue(masked.contains("******"));
    }
}
