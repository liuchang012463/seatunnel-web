package org.apache.seatunnel.web.api.lake.ods;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OdsDatabaseNameValidatorTest {

    @Test
    void lowerCasesBusinessCodesAndBuildsBoundedName() {
        OdsDatabaseName name = OdsDatabaseNameValidator.build("HQ_1", "OMS", "orders_2026");

        assertEquals("hq_1", name.unitCode());
        assertEquals("oms", name.systemCode());
        assertEquals("ods_hq_1_oms_orders_2026", name.databaseName());
    }

    @Test
    void acceptsSourceNamesWithPunctuationAndKeepsTheDisplayName() {
        String displayName = "192.168.100.95_Kingbase-订单";
        OdsDatabaseName name = OdsDatabaseNameValidator.build("UNIT", "SYSTEM", " " + displayName + " ");
        assertEquals(displayName, name.displayName());
        assertTrue(name.databaseName().matches("ods_unit_system_[a-z0-9_]+"));
        assertEquals(name.databaseName(), OdsDatabaseNameValidator.build("unit", "system", displayName).databaseName());
        assertNotEquals(OdsDatabaseNameValidator.build("unit", "system", "sales.east").databaseName(),
                OdsDatabaseNameValidator.build("unit", "system", "sales-east").databaseName());
    }

    @Test
    void shortensTheDatabaseIdentifierButPreservesTheFullLabel() {
        String displayName = "入湖库" + "a".repeat(61);
        OdsDatabaseName name = OdsDatabaseNameValidator.build("unit", "system", displayName);
        assertEquals(displayName, name.displayName());
        assertEquals(64, name.databaseName().length());
    }

    @Test
    void rejectsInvalidBusinessCodesAndOutOfBoundsDisplayNames() {
        LakeServiceException invalidCode = assertThrows(LakeServiceException.class,
                () -> OdsDatabaseNameValidator.build("Head Office", "oms", "orders"));
        assertEquals(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID, invalidCode.getLakeErrorCode());

        String custom = "a".repeat(65);
        LakeServiceException tooLong = assertThrows(LakeServiceException.class,
                () -> OdsDatabaseNameValidator.build("h", "o", custom));
        assertEquals(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID, tooLong.getLakeErrorCode());
        assertThrows(LakeServiceException.class,
                () -> OdsDatabaseNameValidator.build("a".repeat(60), "o", "库"));
    }
}
