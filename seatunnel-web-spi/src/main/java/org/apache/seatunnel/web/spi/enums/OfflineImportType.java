package org.apache.seatunnel.web.spi.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Offline import task types. DMP dumps are restored with the vendor's native
 * tool; SQL scripts are parsed and executed over JDBC, both on the web side
 * instead of the SeaTunnel engine.
 */
@Getter
public enum OfflineImportType {

    KINGBASE_DMP("KINGBASE_DMP", Category.DMP, DbType.KINGBASE),
    DAMENG_DMP("DAMENG_DMP", Category.DMP, DbType.DAMENG),
    KINGBASE_SQL("KINGBASE_SQL", Category.SQL, DbType.KINGBASE),
    DAMENG_SQL("DAMENG_SQL", Category.SQL, DbType.DAMENG);

    private enum Category {
        DMP, SQL
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

    /** Lowercase file extension (with dot) expected for this import type. */
    public String expectedExtension() {
        return isSql() ? ".sql" : ".dmp";
    }

    public static OfflineImportType of(String code) {
        return code == null ? null : TYPE_MAP.get(code.trim().toUpperCase());
    }
}
