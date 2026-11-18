package org.apache.seatunnel.web.api.lake.catalog;

import java.util.Locale;
import java.util.Set;

/** Database visibility shared by the lake directory and structured internal queries. */
public final class LakeCatalogScope {

    public static final String INTERNAL = "internal";

    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "__internal_schema", "_internal_schema", "information_schema", "mysql",
            "performance_schema", "sys");

    private LakeCatalogScope() {
    }

    public static boolean isVisibleInternalDatabase(String name) {
        return name != null && !name.isBlank()
                && !SYSTEM_DATABASES.contains(name.toLowerCase(Locale.ROOT));
    }
}
