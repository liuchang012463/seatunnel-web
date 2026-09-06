package org.apache.seatunnel.web.api.lake.catalog;

import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeCatalogActualEvaluatorTest {

    private static final String CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void exactObservationIgnoresDorisDefaultsAndComparesRequestedOptions() {
        LakeCatalogDesiredSpec desired = desired(Map.of(
                "enable_meta_cache", "true",
                "connection_pool_max_size", "8"));
        Map<String, String> actual = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("type", "JDBC"),
                Map.entry("jdbc_url", desired.jdbcUrl()),
                Map.entry("driver_url", desired.driverUrl()),
                Map.entry("driver_class", desired.driverClass()),
                Map.entry("only_specified_database", "TRUE"),
                Map.entry("include_database_list", "Sales_DB"),
                Map.entry("include_table_list", "Sales_DB.Orders"),
                Map.entry("enable_meta_cache", "TRUE"),
                Map.entry("connection_pool_max_size", "8"),
                Map.entry("catalog_create_time", "2026-09-01 00:00:00"),
                Map.entry("user", "reader"),
                Map.entry("password", "do-not-return")));

        LakeCatalogValidationResult result = LakeCatalogActualEvaluator
                .compare(desired, actual);

        assertTrue(result.isMatch());
        assertEquals(LakeCatalogValidationCode.MATCH, result.code());
        assertFalse(result.actualProperties().containsKey("user"));
        assertFalse(result.actualProperties().containsKey("password"));
        assertFalse(result.actualProperties().containsKey("catalog_create_time"));
    }

    @Test
    void ordinaryPropertyMismatchIsReportedAsMismatchWithoutSecrets() {
        LakeCatalogDesiredSpec desired = desired(Map.of());
        Map<String, String> actual = requiredActual(desired);
        actual.put("driver_class", "org.postgresql.Driver");
        actual.put("password", "hidden");

        LakeCatalogValidationResult result = LakeCatalogActualEvaluator
                .compare(desired, actual);

        assertEquals(LakeCatalogValidationStatus.MISMATCH, result.status());
        assertEquals(LakeCatalogValidationCode.PROPERTY_MISMATCH, result.code());
        assertTrue(result.mismatches().containsKey("driver_class"));
        assertFalse(result.mismatches().toString().contains("hidden"));
    }

    @Test
    void missingRequestedOptionIsUnknownRatherThanSilentlyConsistent() {
        LakeCatalogDesiredSpec desired = desired(Map.of("enable_meta_cache", "true"));
        Map<String, String> actual = requiredActual(desired);
        actual.remove("enable_meta_cache");

        LakeCatalogValidationResult result = LakeCatalogActualEvaluator
                .compare(desired, actual);

        assertTrue(result.isUnknown());
        assertEquals(LakeCatalogValidationCode.REQUIRED_PROPERTY_UNKNOWN, result.code());
    }

    @Test
    void ambiguousJdbcUrlRewriteIsUnknownRatherThanFalseDrift() {
        LakeCatalogDesiredSpec desired = desired(Map.of());
        Map<String, String> actual = requiredActual(desired);
        actual.put("jdbc_url", desired.jdbcUrl() + "?useSSL=false");

        LakeCatalogValidationResult result = LakeCatalogActualEvaluator
                .compare(desired, actual);

        assertTrue(result.isUnknown());
        assertEquals(LakeCatalogValidationCode.JDBC_URL_AMBIGUOUS, result.code());
    }

    @Test
    void reorderedJdbcUrlParametersAreAmbiguousRatherThanDrift() {
        LakeCatalogDesiredSpec desired = new LakeCatalogDesiredSpec(
                "source_catalog", 17L, "source-v1", LakeJdbcAdapterType.MYSQL,
                LakeCatalogScope.ALL, "jdbc:mysql://source.example/source?a=1&b=2",
                "file:/opt/drivers/mysql.jar", "com.mysql.cj.jdbc.Driver", CHECKSUM,
                "drivers-v1", "credential-v1", List.of(), List.of(), Map.of());
        Map<String, String> actual = requiredActual(desired);
        actual.put("jdbc_url", "jdbc:mysql://source.example/source?b=2&a=1");

        LakeCatalogValidationResult result = LakeCatalogActualEvaluator
                .compare(desired, actual);

        assertTrue(result.isUnknown());
        assertEquals(LakeCatalogValidationCode.JDBC_URL_AMBIGUOUS, result.code());
    }

    @Test
    void differentJdbcAuthorityIsARealMismatch() {
        LakeCatalogDesiredSpec desired = desired(Map.of());
        Map<String, String> actual = requiredActual(desired);
        actual.put("jdbc_url", "jdbc:mysql://other.example:3306/source");

        LakeCatalogValidationResult result = LakeCatalogActualEvaluator
                .compare(desired, actual);

        assertTrue(result.isMismatch());
        assertEquals(LakeCatalogValidationCode.PROPERTY_MISMATCH, result.code());
        assertTrue(result.mismatches().containsKey("jdbc_url"));
    }

    @Test
    void resultMasksMaliciousActualUrlsAndMismatchValues() {
        LakeCatalogDesiredSpec desired = desired(Map.of());
        Map<String, String> actual = requiredActual(desired);
        actual.put("driver_url", "file:/opt/driver.jar?token=private-value");

        LakeCatalogValidationResult result = LakeCatalogActualEvaluator
                .compare(desired, actual);

        assertTrue(result.isMismatch());
        assertFalse(result.toString().contains("private-value"));
        assertFalse(result.actualProperties().toString().contains("private-value"));
        assertEquals("******", result.actualProperties().get("driver_url"));
        assertEquals("******", result.mismatches().get("driver_url"));
    }

    @Test
    void requestedOptionsAreComparedDynamicallyAndInjectedOptionsAreIgnored() {
        LakeCatalogDesiredSpec desired = desired(Map.of("lower_case_meta_names", "true"));
        Map<String, String> actual = requiredActual(desired);
        actual.put("connector_injected_option", "ignored");
        actual.put("lower_case_table_names", "false");

        LakeCatalogValidationResult result = LakeCatalogActualEvaluator
                .compare(desired, actual);

        assertTrue(result.isMatch());
    }

    @Test
    void tableScopeIncludesEveryCaseSensitiveTableInExpectedProperty() {
        LakeCatalogDesiredSpec desired = new LakeCatalogDesiredSpec(
                "catalog", 17L, "source-v1", LakeJdbcAdapterType.POSTGRESQL,
                LakeCatalogScope.TABLE, "jdbc:postgresql://source.example/app",
                "file:/opt/drivers/postgresql.jar", "org.postgresql.Driver", CHECKSUM,
                "drivers-v1", "credential-v1", List.of("Public"),
                List.of("Orders", "order_items"), Map.of());
        Map<String, String> actual = requiredActual(desired);
        actual.put("include_table_list", "Public.Orders,Public.order_items");

        LakeCatalogValidationResult result = LakeCatalogActualEvaluator
                .compare(desired, actual);

        assertTrue(result.isMatch());
    }

    private static LakeCatalogDesiredSpec desired(Map<String, String> options) {
        return new LakeCatalogDesiredSpec(
                "source_catalog", 17L, "source-v1", LakeJdbcAdapterType.MYSQL,
                LakeCatalogScope.TABLE, "jdbc:mysql://source.example:3306/source",
                "file:/opt/drivers/mysql.jar", "com.mysql.cj.jdbc.Driver", CHECKSUM,
                "drivers-v1", "credential-v1", List.of("Sales_DB"),
                List.of("Orders"), options);
    }

    private static Map<String, String> requiredActual(LakeCatalogDesiredSpec desired) {
        return new LinkedHashMap<>(LakeCatalogDesiredSpecCanonicalizer
                .webOwnedDesiredProperties(desired));
    }
}
