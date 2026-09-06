package org.apache.seatunnel.web.api.lake.ods;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validates business codes before deriving a Doris Internal Database name. */
public final class OdsDatabaseNameValidator {

    private static final Pattern COMPONENT = Pattern.compile("^[a-z_][a-z0-9_]*$");
    private static final Pattern DATABASE = Pattern.compile("^[a-z_][a-z0-9_]{0,63}$");

    private OdsDatabaseNameValidator() {
    }

    public static OdsDatabaseName build(String unitCode, String systemCode, String customName) {
        String unit = normalizeCode(unitCode, "unitCode");
        String system = normalizeCode(systemCode, "systemCode");
        String custom = normalizeCode(customName, "customName");
        String databaseName = "ods_" + unit + "_" + system + "_" + custom;
        if (!DATABASE.matcher(databaseName).matches()) {
            throw new LakeServiceException(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID,
                    "ODS database name is not a valid Doris identifier");
        }
        return new OdsDatabaseName(unit, system, custom, databaseName);
    }

    private static String normalizeCode(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new LakeServiceException(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID,
                    field + " must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!COMPONENT.matcher(normalized).matches()) {
            throw new LakeServiceException(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID,
                    field + " contains unsupported characters");
        }
        return normalized;
    }

    public static boolean isValidComponent(String value) {
        return value != null && COMPONENT.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }
}
