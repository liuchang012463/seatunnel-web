package org.apache.seatunnel.web.spi.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Offline import task types. DMP dumps use the vendor's native tool, while
 * SQL scripts and encrypted data packages are imported by the web side.
 */
@Getter
public enum OfflineImportType {

    KINGBASE_DMP("KINGBASE_DMP", Category.DMP, DbType.KINGBASE),
    DAMENG_DMP("DAMENG_DMP", Category.DMP, DbType.DAMENG),
    KINGBASE_SQL("KINGBASE_SQL", Category.SQL, DbType.KINGBASE),
    DAMENG_SQL("DAMENG_SQL", Category.SQL, DbType.DAMENG),
    DATAPACK("DATAPACK", Category.DATAPACK, null);

    private enum Category {
        DMP, SQL, DATAPACK
    }

    private static final Map<String, OfflineImportType> TYPE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(Enum::name, Function.identity()));

    private final String code;
    private final Category category;
    private final DbType targetDbType;

    OfflineImportType(String code, Category category, DbType targetDbType) {
        this.code = code;
        this.category = category;
        this.targetDbType = targetDbType;
    }

    public boolean isSql() {
        return category == Category.SQL;
    }

    public boolean isDatapack() {
        return category == Category.DATAPACK;
    }

    /** Lowercase file extension (with dot) expected for this import type. */
    public String expectedExtension() {
        return isDatapack() ? ".datapack" : isSql() ? ".sql" : ".dmp";
    }

    public static OfflineImportType of(String code) {
        return code == null ? null : TYPE_MAP.get(code.trim().toUpperCase(Locale.ROOT));
    }
}
