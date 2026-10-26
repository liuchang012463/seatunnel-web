package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineImportLogRedactorTest {

    @Test
    void masksSqlPasswordAssignments() {
        String masked = OfflineImportLogRedactor.mask(
                "CREATE USER app IDENTIFIED BY 'secret1'; "
                        + "ALTER ROLE app PASSWORD 'secret2'; "
                        + "CREATE USER app2 PASSWORD secret3;");
        assertFalse(masked.contains("secret1"));
        assertFalse(masked.contains("secret2"));
        assertFalse(masked.contains("secret3"));
        assertTrue(masked.contains("IDENTIFIED BY ******"));
        assertTrue(masked.contains("PASSWORD ******"));
    }

    @Test
    void masksNativeToolCredentials() {
        String masked = OfflineImportLogRedactor.mask(
                "USERID=app/\"Pwd/123\"@host:5236 PGPASSWORD=secret4 password=secret5");
        assertFalse(masked.contains("Pwd/123"));
        assertFalse(masked.contains("secret4"));
        assertFalse(masked.contains("secret5"));
    }
}
