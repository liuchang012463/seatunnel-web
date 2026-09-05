package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.dao.entity.LakeWarehouseConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LakeWarehouseServiceImplTest {

    @Test
    void encryptsTheSubmittedPasswordForTheFirstConfiguration() {
        String propertyName = "seatunnel.web.datasource.master-key";
        String previousKey = System.getProperty(propertyName);
        String encrypted;
        try {
            System.setProperty(propertyName, "lake-warehouse-test-master-key");
            encrypted = LakeWarehouseServiceImpl.resolveEncryptedPassword(null, "doris-secret");
        } finally {
            if (previousKey == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, previousKey);
            }
        }

        assertNotNull(encrypted);
        assertFalse(encrypted.isBlank());
        // The persisted value must not be the plaintext request value.
        org.junit.jupiter.api.Assertions.assertNotEquals("doris-secret", encrypted);
    }

    @Test
    void keepsTheExistingEncryptedPasswordWhenUpdateOmitsIt() {
        LakeWarehouseConfig current = new LakeWarehouseConfig();
        current.setPassword("existing-encrypted-password");

        assertEquals("existing-encrypted-password",
                LakeWarehouseServiceImpl.resolveEncryptedPassword(current, "  "));
        assertNull(LakeWarehouseServiceImpl.resolveEncryptedPassword(null, "  "));
    }
}
