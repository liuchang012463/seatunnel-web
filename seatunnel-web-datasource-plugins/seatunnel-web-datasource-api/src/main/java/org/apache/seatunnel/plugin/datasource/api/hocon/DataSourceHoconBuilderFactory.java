package org.apache.seatunnel.plugin.datasource.api.hocon;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

public class DataSourceHoconBuilderFactory {

    private static final Map<String, DataSourceHoconBuilder> BUILDER_MAP = new HashMap<>();

    static {
        ServiceLoader<DataSourceHoconBuilder> loader = ServiceLoader.load(DataSourceHoconBuilder.class);
        for (DataSourceHoconBuilder builder : loader) {
            BUILDER_MAP.put(normalize(builder.pluginName()), builder);
        }
    }

    /**
     * Resolves a builder by plugin name.
     *
     * <p>Plugin names originate from front-end options, saved job definitions and
     * legacy records, so the lookup must not depend on letter case. Register and
     * resolve names in upper case to keep {@code MySQL-CDC} and {@code MYSQL-CDC}
     * equivalent.</p>
     */
    public static DataSourceHoconBuilder getBuilder(String pluginName) {
        DataSourceHoconBuilder builder = BUILDER_MAP.get(normalize(pluginName));
        if (builder == null) {
            throw new IllegalArgumentException("No builder found for plugin: " + pluginName);
        }
        return builder;
    }

    private static String normalize(String pluginName) {
        return pluginName == null ? "" : pluginName.trim().toUpperCase(Locale.ROOT);
    }
}
