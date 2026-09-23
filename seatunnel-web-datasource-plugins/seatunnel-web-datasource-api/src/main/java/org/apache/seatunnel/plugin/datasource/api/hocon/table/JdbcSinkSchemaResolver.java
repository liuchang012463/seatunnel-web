package org.apache.seatunnel.plugin.datasource.api.hocon.table;

import com.typesafe.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConfigReaders;

import static org.apache.seatunnel.plugin.datasource.api.hocon.JdbcBatchConstants.*;

final class JdbcSinkSchemaResolver {

    private JdbcSinkSchemaResolver() {
    }

    static String normalizeSingleTable(Config config, Config conn, String database, String table) {
        if (isDameng(config, conn)) {
            return normalizeDamengSingleTable(config, conn, database, table);
        }

        String schema = resolvePostgreSqlSchema(config, conn);
        if (StringUtils.isBlank(schema) || StringUtils.isBlank(table)) {
            return table;
        }

        String trimmedTable = table.trim();
        String[] parts = StringUtils.split(trimmedTable, '.');
        if (parts == null || parts.length == 0) {
            return trimmedTable;
        }

        if (parts.length == 1) {
            return schema + "." + trimmedTable;
        }

        String firstPart = parts[0].trim();
        if (StringUtils.equals(firstPart, schema)) {
            return trimmedTable;
        }

        if (StringUtils.isNotBlank(database) && StringUtils.equals(firstPart, database.trim())) {
            if (parts.length == 2) {
                return schema + "." + parts[1].trim();
            }
            if (parts.length == 3) {
                return parts[1].trim() + "." + parts[2].trim();
            }
        }

        return trimmedTable;
    }

    static String defaultMultiTablePattern(Config config, Config conn) {
        if (isDameng(config, conn)) {
            String owner = resolveDamengSchema(config, conn);
            return StringUtils.isBlank(owner) ? TABLE_NAME_PLACEHOLDER : owner + "." + TABLE_NAME_PLACEHOLDER;
        }

        String schema = resolvePostgreSqlSchema(config, conn);
        if (StringUtils.isBlank(schema)) {
            return TABLE_NAME_PLACEHOLDER;
        }
        return schema + "." + TABLE_NAME_PLACEHOLDER;
    }

    private static String resolvePostgreSqlSchema(Config config, Config conn) {
        if (isDatabaseOnly(config, conn)) {
            return "";
        }

        String schema = firstNonBlank(
                JdbcConfigReaders.getString(config, SCHEMA, ""),
                JdbcConfigReaders.getString(config, SCHEMA_NAME, ""),
                JdbcConfigReaders.getString(conn, SCHEMA, ""),
                JdbcConfigReaders.getString(conn, SCHEMA_NAME, "")
        );
        if (StringUtils.isNotBlank(schema)) {
            return schema.trim();
        }

        // PostgreSQL-compatible databases use public when a legacy datasource
        // connection does not contain schema/schemaName. This mirrors the
        // PostgreSQL datasource default and ensures multi-table sinks retain a
        // schema-qualified target name.
        return isPostgreSql(config, conn) ? "public" : "";
    }

    private static String normalizeDamengSingleTable(
            Config config, Config conn, String database, String table) {
        if (StringUtils.isBlank(table)) {
            return table;
        }

        String owner = resolveDamengSchema(config, conn);
        String[] parts = StringUtils.split(table.trim(), '.');
        if (parts == null || parts.length == 0) {
            return table.trim();
        }

        if (parts.length >= 3) {
            if (StringUtils.isNotBlank(database)
                    && !StringUtils.equalsIgnoreCase(parts[0].trim(), database.trim())) {
                throw new IllegalArgumentException(
                        "Dameng sink table path database ["
                                + parts[0].trim()
                                + "] does not match the configured database instance ["
                                + database.trim()
                                + "]");
            }

            assertOwnerMatches(owner, parts[1].trim());
            return parts[1].trim() + "." + parts[2].trim();
        }

        if (parts.length == 2) {
            String firstPart = parts[0].trim();
            String tableName = parts[1].trim();
            if (StringUtils.isBlank(owner)) {
                return firstPart + "." + tableName;
            }

            if (StringUtils.equalsIgnoreCase(firstPart, owner)) {
                return firstPart + "." + tableName;
            }

            if (StringUtils.isNotBlank(database)
                    && StringUtils.equalsIgnoreCase(firstPart, database.trim())) {
                return owner + "." + tableName;
            }

            throw new IllegalArgumentException(
                    "Dameng sink table path owner ["
                            + firstPart
                            + "] does not match the configured Schema/Owner ["
                            + owner
                            + "]");
        }

        return StringUtils.isBlank(owner) ? parts[0].trim() : owner + "." + parts[0].trim();
    }

    private static String resolveDamengSchema(Config config, Config conn) {
        return firstNonBlank(
                JdbcConfigReaders.getString(config, SCHEMA, ""),
                JdbcConfigReaders.getString(config, SCHEMA_NAME, ""),
                JdbcConfigReaders.getString(conn, SCHEMA, ""),
                JdbcConfigReaders.getString(conn, SCHEMA_NAME, ""));
    }

    private static void assertOwnerMatches(String configuredOwner, String actualOwner) {
        if (StringUtils.isNotBlank(configuredOwner)
                && !StringUtils.equalsIgnoreCase(configuredOwner.trim(), actualOwner)) {
            throw new IllegalArgumentException(
                    "Dameng table path owner ["
                            + actualOwner
                            + "] does not match the configured Schema/Owner ["
                            + configuredOwner.trim()
                            + "]");
        }
    }

    private static boolean isDatabaseOnly(Config config, Config conn) {
        return containsIgnoreCase(config, DB_TYPE, "mysql")
                || containsIgnoreCase(conn, DB_TYPE, "mysql")
                || containsIgnoreCase(config, DB_TYPE, "doris")
                || containsIgnoreCase(conn, DB_TYPE, "doris")
                || containsIgnoreCase(config, PLUGIN_NAME, "mysql")
                || containsIgnoreCase(conn, PLUGIN_NAME, "mysql")
                || containsIgnoreCase(config, PLUGIN_NAME, "doris")
                || containsIgnoreCase(conn, PLUGIN_NAME, "doris")
                || containsIgnoreCase(config, DRIVER, "mysql")
                || containsIgnoreCase(conn, DRIVER, "mysql")
                || startsWithIgnoreCase(config, URL, "jdbc:mysql:")
                || startsWithIgnoreCase(conn, URL, "jdbc:mysql:");
    }

    private static boolean isPostgreSql(Config config, Config conn) {
        return containsIgnoreCase(config, PLUGIN_NAME, "postgres")
                || containsIgnoreCase(conn, PLUGIN_NAME, "postgres")
                || containsIgnoreCase(config, PLUGIN_NAME, "kingbase")
                || containsIgnoreCase(conn, PLUGIN_NAME, "kingbase")
                || containsIgnoreCase(config, PLUGIN_NAME, "dameng")
                || containsIgnoreCase(conn, PLUGIN_NAME, "dameng")
                || containsIgnoreCase(config, DB_TYPE, "postgre")
                || containsIgnoreCase(conn, DB_TYPE, "postgre")
                || containsIgnoreCase(config, DB_TYPE, "kingbase")
                || containsIgnoreCase(conn, DB_TYPE, "kingbase")
                || containsIgnoreCase(config, DRIVER, "postgresql")
                || containsIgnoreCase(conn, DRIVER, "postgresql")
                || containsIgnoreCase(config, DRIVER, "kingbase")
                || containsIgnoreCase(conn, DRIVER, "kingbase")
                || startsWithIgnoreCase(config, URL, "jdbc:postgresql:")
                || startsWithIgnoreCase(conn, URL, "jdbc:postgresql:")
                || startsWithIgnoreCase(config, URL, "jdbc:kingbase8:")
                || startsWithIgnoreCase(conn, URL, "jdbc:kingbase8:");
    }

    private static boolean isDameng(Config config, Config conn) {
        return containsIgnoreCase(config, PLUGIN_NAME, "dameng")
                || containsIgnoreCase(conn, PLUGIN_NAME, "dameng")
                || containsIgnoreCase(config, DB_TYPE, "dameng")
                || containsIgnoreCase(conn, DB_TYPE, "dameng")
                || containsIgnoreCase(config, DRIVER, "dm.jdbc")
                || containsIgnoreCase(conn, DRIVER, "dm.jdbc")
                || startsWithIgnoreCase(config, URL, "jdbc:dm:")
                || startsWithIgnoreCase(conn, URL, "jdbc:dm:");
    }

    private static boolean containsIgnoreCase(Config config, String path, String searchText) {
        return StringUtils.containsIgnoreCase(JdbcConfigReaders.getString(config, path, ""), searchText);
    }

    private static boolean startsWithIgnoreCase(Config config, String path, String prefix) {
        return StringUtils.startsWithIgnoreCase(JdbcConfigReaders.getString(config, path, ""), prefix);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }
}
