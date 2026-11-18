package org.apache.seatunnel.web.api.lake.ods;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;

import java.util.Locale;
import java.util.regex.Pattern;

/** Derives a Doris internal database identifier from business codes and a display name. */
public final class OdsDatabaseNameValidator {

    private static final int MAX_NAME_LENGTH = 64;
    private static final int HASH_LENGTH = 10;
    private static final Pattern COMPONENT = Pattern.compile("^[a-z_][a-z0-9_]*$");
    private static final Pattern DATABASE = Pattern.compile("^[a-z_][a-z0-9_]{0,63}$");

    private OdsDatabaseNameValidator() {
    }

    public static OdsDatabaseName build(String unitCode, String systemCode, String customName) {
        String unit = normalizeCode(unitCode, "unitCode");
        String system = normalizeCode(systemCode, "systemCode");
        String displayName = customName == null ? "" : customName.strip();
        if (displayName.isBlank() || displayName.codePointCount(0, displayName.length()) > MAX_NAME_LENGTH) {
            throw new LakeServiceException(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID,
                    "ODS 库名称须为 1 至 64 个字符");
        }
        String prefix = "ods_" + unit + "_" + system + "_";
        String normalizedName = displayName.toLowerCase(Locale.ROOT);
        String databaseName = prefix + normalizedName;
        if (DATABASE.matcher(databaseName).matches()) {
            return new OdsDatabaseName(unit, system, displayName, databaseName);
        }

        int suffixLength = MAX_NAME_LENGTH - prefix.length();
        if (suffixLength < 1) {
            throw new LakeServiceException(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID,
                    "单位编码和系统编码过长，无法生成 ODS 库名");
        }
        // A stable hash distinguishes names that produce the same slug or need truncation.
        String hash = DigestUtils.sha256Hex(normalizedName).substring(0, Math.min(HASH_LENGTH, suffixLength));
        String slug = normalizedName.replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        int readableLength = Math.min(slug.length(), suffixLength - hash.length() - 1);
        String suffix = readableLength > 0 ? slug.substring(0, readableLength) + "_" + hash : hash;
        return new OdsDatabaseName(unit, system, displayName, prefix + suffix);
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
