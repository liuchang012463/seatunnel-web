package org.apache.seatunnel.web.api.lake.catalog;

import org.apache.seatunnel.web.api.lake.CatalogPropertyRedactor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Safe result of comparing a desired catalog spec with an actual observation.
 * Actual properties have already passed the Web-owned allowlist and therefore
 * contain no credentials or arbitrary Doris defaults.
 */
public record LakeCatalogValidationResult(
        LakeCatalogValidationStatus status,
        String code,
        Map<String, String> actualProperties,
        Map<String, String> mismatches) {

    private static final Pattern JWT = Pattern.compile(
            "(?i)(?:^|[^A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}"
                    + "\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"
                    + "(?:$|[^A-Za-z0-9_-])");

    public LakeCatalogValidationResult {
        status = status == null ? LakeCatalogValidationStatus.UNKNOWN : status;
        code = code == null ? LakeCatalogValidationCode.UNKNOWN : code;
        actualProperties = safeProperties(actualProperties);
        mismatches = safeMismatchProperties(mismatches);
    }

    public boolean isMatch() {
        return status == LakeCatalogValidationStatus.MATCH;
    }

    public boolean isMismatch() {
        return status == LakeCatalogValidationStatus.MISMATCH;
    }

    public boolean isUnknown() {
        return status == LakeCatalogValidationStatus.UNKNOWN;
    }

    public boolean isMissing() {
        return status == LakeCatalogValidationStatus.MISSING;
    }

    public String reasonCode() {
        return code;
    }

    public String getReasonCode() {
        return code;
    }

    public Map<String, String> getActualProperties() {
        return actualProperties;
    }

    public Map<String, String> getMismatches() {
        return mismatches;
    }

    public List<String> mismatchKeys() {
        return List.copyOf(mismatches.keySet());
    }

    public static LakeCatalogValidationResult match(Map<String, String> actualProperties) {
        return new LakeCatalogValidationResult(
                LakeCatalogValidationStatus.MATCH,
                LakeCatalogValidationCode.MATCH,
                actualProperties,
                Map.of());
    }

    public static LakeCatalogValidationResult mismatch(
            Map<String, String> actualProperties,
            Map<String, String> mismatches) {
        return mismatch(LakeCatalogValidationCode.PROPERTY_MISMATCH,
                actualProperties, mismatches);
    }

    public static LakeCatalogValidationResult mismatch(
            String code,
            Map<String, String> actualProperties,
            Map<String, String> mismatches) {
        return new LakeCatalogValidationResult(
                LakeCatalogValidationStatus.MISMATCH,
                code,
                actualProperties,
                mismatches);
    }

    public static LakeCatalogValidationResult missing() {
        return new LakeCatalogValidationResult(
                LakeCatalogValidationStatus.MISSING,
                LakeCatalogValidationCode.MISSING,
                Map.of(),
                Map.of());
    }

    public static LakeCatalogValidationResult missing(
            String code,
            Map<String, String> actualProperties,
            Map<String, String> mismatches) {
        return new LakeCatalogValidationResult(
                LakeCatalogValidationStatus.MISSING,
                code,
                actualProperties,
                mismatches);
    }

    public static LakeCatalogValidationResult unknown(String code) {
        return unknown(code, Map.of());
    }

    public static LakeCatalogValidationResult unknown(
            String code,
            Map<String, String> actualProperties) {
        return new LakeCatalogValidationResult(
                LakeCatalogValidationStatus.UNKNOWN,
                code,
                actualProperties,
                Map.of());
    }

    private static Map<String, String> safeProperties(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                safe.put(entry.getKey(), safeValue(entry.getKey(), entry.getValue()));
            }
        }
        return immutable(safe);
    }

    private static Map<String, String> safeMismatchProperties(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (String key : properties.keySet()) {
            if (key != null && !key.isBlank()) {
                // Diagnostics carry the key only.  Actual values may contain
                // a DBA-inserted credential even when the key is allowlisted.
                safe.put(key, CatalogPropertyRedactor.MASK);
            }
        }
        return immutable(safe);
    }

    private static String safeValue(String key, String value) {
        if (CatalogPropertyRedactor.isSensitiveKey(key)) {
            return CatalogPropertyRedactor.MASK;
        }
        String redacted = CatalogPropertyRedactor.redactText(value);
        // A signed bearer/JWT token is not necessarily introduced through a
        // sensitive property key.  Do not let one escape through a diagnostic
        // value supplied by a connector or a future Web-owned property.
        return JWT.matcher(redacted).find() ? CatalogPropertyRedactor.MASK : redacted;
    }

    private static Map<String, String> immutable(Map<String, String> values) {
        return values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
