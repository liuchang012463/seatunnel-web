package org.apache.seatunnel.web.api.lake.ods;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OdsDatabaseNameValidatorTest {

    @Test
    void lowerCasesBusinessCodesAndBuildsBoundedName() {
        OdsDatabaseName name = OdsDatabaseNameValidator.build("HQ_1", "OMS", "orders_2026");

        assertEquals("hq_1", name.unitCode());
        assertEquals("oms", name.systemCode());
        assertEquals("ods_hq_1_oms_orders_2026", name.databaseName());
    }

    @Test
    void rejectsLegacyCodeCharactersAndOverlongComposedName() {
        LakeServiceException invalidCode = assertThrows(LakeServiceException.class,
                () -> OdsDatabaseNameValidator.build("Head Office", "oms", "orders"));
        assertEquals(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID, invalidCode.getLakeErrorCode());

        String custom = "a".repeat(60);
        LakeServiceException tooLong = assertThrows(LakeServiceException.class,
                () -> OdsDatabaseNameValidator.build("h", "o", custom));
        assertEquals(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID, tooLong.getLakeErrorCode());
    }
}
