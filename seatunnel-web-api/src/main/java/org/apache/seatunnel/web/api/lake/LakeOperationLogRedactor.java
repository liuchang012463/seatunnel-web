package org.apache.seatunnel.web.api.lake;

import java.util.Map;

/** Explicit name used at operation-journal boundaries. */
public final class LakeOperationLogRedactor {

    private LakeOperationLogRedactor() {
    }

    public static String summary(String errorOrSummary) {
        return CatalogPropertyRedactor.redactText(errorOrSummary);
    }

    public static Map<String, Object> details(Map<?, ?> details) {
        return CatalogPropertyRedactor.redactMap(details);
    }
}
