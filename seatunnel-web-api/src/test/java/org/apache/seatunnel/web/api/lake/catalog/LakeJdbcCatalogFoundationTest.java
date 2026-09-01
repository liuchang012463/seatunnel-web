package org.apache.seatunnel.web.api.lake.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeJdbcCatalogFoundationTest {

    private static final String CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void registryContainsOnlyTheThreeFixedAdaptersAndDefaultsToDisabled() {
        LakeJdbcCatalogAdapterRegistry adapters = new LakeJdbcCatalogAdapterRegistry();
        LakeJdbcDriverRegistry drivers = new LakeJdbcDriverRegistry();

        assertTrue(adapters.find("MYSQL").isPresent());
        assertTrue(adapters.find("POSTGRE_SQL").isPresent());
        assertTrue(adapters.find("ORACLE").isPresent());
        assertFalse(adapters.find("DORIS").isPresent());

        LakeCatalogCapability capability = adapters.capability(
                LakeJdbcAdapterType.MYSQL, drivers, LakeCatalogScope.ALL,
                true, true, true);
        assertFalse(capability.enabled());
        assertEquals(List.of(LakeCatalogCapabilityReason.DRIVER_CONFIG_MISSING),
                capability.reasonCodes());
    }

    @Test
    void driverAvailabilityUsesExplicitDorisDeploymentVerificationNotWebClasspath() {
        LakeProperties.JdbcCatalog config = new LakeProperties.JdbcCatalog();
        config.setRegistryRevision("drivers-v1");
        LakeProperties.Driver mysql = LakeProperties.Driver.mysqlDefaults();
        mysql.setEnabled(true);
        mysql.setVerified(false);
        mysql.setUrl("file:/opt/drivers/mysql-8.0.39.jar");
        mysql.setChecksum(CHECKSUM);
        config.setMysql(mysql);

        LakeJdbcDriverRegistry registry = new LakeJdbcDriverRegistry(config);
        LakeJdbcDriverRegistry.DriverStatus notVerified = registry.status(LakeJdbcAdapterType.MYSQL);
        assertFalse(notVerified.available());
        assertTrue(notVerified.reasonCodes().contains(LakeCatalogCapabilityReason.DRIVER_NOT_VERIFIED));

        mysql.setVerified(true);
        LakeJdbcDriverRegistry verifiedRegistry = new LakeJdbcDriverRegistry(config);
        assertTrue(verifiedRegistry.status(LakeJdbcAdapterType.MYSQL).available());
    }

    @Test
    void driverInventoryDetailsAreNotSerializedAsCapabilityFacts() throws Exception {
        LakeJdbcDriverRegistry registry = new LakeJdbcDriverRegistry(configuredCatalog());

        String json = new ObjectMapper().writeValueAsString(
                registry.status(LakeJdbcAdapterType.MYSQL));

        assertFalse(json.contains("file:/opt/drivers"));
        assertFalse(json.contains("com.mysql.cj.jdbc.Driver"));
        assertFalse(json.contains(CHECKSUM));
        assertTrue(json.contains("available"));
    }

    @Test
    void capabilityReportsStableReasonsAndNeverClaimsSuccessWithMissingDriver() {
        LakeJdbcCatalogAdapterRegistry adapters = new LakeJdbcCatalogAdapterRegistry();
        LakeProperties.JdbcCatalog config = new LakeProperties.JdbcCatalog();
        config.setRegistryRevision("drivers-v1");
        LakeProperties.Driver mysql = LakeProperties.Driver.mysqlDefaults();
        mysql.setEnabled(true);
        mysql.setVerified(true);
        mysql.setUrl("file:/opt/drivers/mysql.jar");
        mysql.setChecksum(CHECKSUM);
        config.setMysql(mysql);
        LakeJdbcDriverRegistry drivers = new LakeJdbcDriverRegistry(config);

        LakeCatalogCapability capability = adapters.capability(
                LakeJdbcAdapterType.MYSQL, drivers, LakeCatalogScope.DATABASE,
                false, false, false);
        assertFalse(capability.enabled());
        assertEquals(List.of(
                LakeCatalogCapabilityReason.SOURCE_CONFIG_INCOMPLETE,
                LakeCatalogCapabilityReason.LAKE_DORIS_UNREACHABLE,
                LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE),
                capability.reasonCodes());
    }

    @Test
    void desiredSpecNormalizesIdentifiersAndCanonicalizesMaps() {
        Map<String, String> firstOptions = new LinkedHashMap<>();
        firstOptions.put("enable-meta-cache", "true");
        firstOptions.put("lower_case_table_names", "false");
        LakeCatalogDesiredSpec first = spec(
                "Source_Catalog", LakeCatalogScope.TABLE, List.of("Sales_DB"),
                List.of("Orders"), firstOptions);

        Map<String, String> secondOptions = new LinkedHashMap<>();
        secondOptions.put("lower_case_table_names", "false");
        secondOptions.put("enable_meta_cache", "true");
        LakeCatalogDesiredSpec second = spec(
                "source_catalog", LakeCatalogScope.TABLE, List.of("Sales_DB"),
                List.of("Orders"), secondOptions);

        LakeCatalogDesiredSpec normalized =
                LakeCatalogDesiredSpecValidator.validateAndNormalize(first);
        assertEquals("source_catalog", normalized.catalogName());
        assertEquals(List.of("Sales_DB"), normalized.databaseInclude());
        assertEquals(List.of("Orders"), normalized.tableInclude());
        assertEquals(
                LakeCatalogDesiredSpecCanonicalizer.canonicalJson(first),
                LakeCatalogDesiredSpecCanonicalizer.canonicalJson(second));
        assertEquals(
                LakeCatalogDesiredSpecCanonicalizer.sha256(first),
                LakeCatalogDesiredSpecCanonicalizer.sha256(second));
    }

    @Test
    void scopeHasExactDatabaseAndTableCardinality() {
        assertThrows(IllegalArgumentException.class,
                () -> LakeCatalogDesiredSpecValidator.validateAndNormalize(
                        spec("catalog", LakeCatalogScope.ALL, List.of("db"), List.of(), Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> LakeCatalogDesiredSpecValidator.validateAndNormalize(
                        spec("catalog", LakeCatalogScope.DATABASE, List.of(), List.of(), Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> LakeCatalogDesiredSpecValidator.validateAndNormalize(
                        spec("catalog", LakeCatalogScope.TABLE, List.of("db"), List.of(), Map.of())));
        assertDoesNotThrow(() -> LakeCatalogDesiredSpecValidator.validateAndNormalize(
                spec("catalog", LakeCatalogScope.TABLE, List.of("db"),
                        List.of("t", "T2"), Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> LakeCatalogDesiredSpecValidator.validateAndNormalize(
                        spec("catalog", LakeCatalogScope.TABLE, List.of("db", "db2"),
                                List.of("t"), Map.of())));
    }

    @Test
    void desiredSpecRejectsCredentialsAndUnsafeIdentifiers() {
        LakeCatalogDesiredSpec baseSpec = spec(
                "catalog", LakeCatalogScope.ALL, List.of(), List.of(), Map.of());
        LakeCatalogDesiredSpec withPasswordInUrl = new LakeCatalogDesiredSpec(
                baseSpec.catalogName(), baseSpec.sourceDataSourceId(),
                baseSpec.sourceDataSourceRevision(), baseSpec.adapter(),
                baseSpec.scope(), "jdbc:mysql://db/schema?password=secret",
                baseSpec.driverUrl(), baseSpec.driverClass(),
                baseSpec.driverChecksum(), baseSpec.driverRegistryRevision(),
                baseSpec.credentialRevision(), baseSpec.databaseInclude(),
                baseSpec.tableInclude(), baseSpec.options());
        assertThrows(IllegalArgumentException.class,
                () -> LakeCatalogDesiredSpecValidator.validateAndNormalize(withPasswordInUrl));

        LakeCatalogDesiredSpec unsafeName = spec(
                "catalog;drop", LakeCatalogScope.ALL, List.of(), List.of(), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> LakeCatalogDesiredSpecValidator.validateAndNormalize(unsafeName));
        assertTrue(LakeCatalogDesiredSpecCanonicalizer.canonicalJson(spec(
                "catalog", LakeCatalogScope.ALL, List.of(), List.of(), Map.of()))
                .contains("credentialRevision"));
    }

    @Test
    void createDdlUsesServerDriverAndExecutionOnlyCredentials() {
        LakeProperties.JdbcCatalog config = configuredCatalog();
        LakeJdbcDriverRegistry drivers = new LakeJdbcDriverRegistry(config);
        LakeCatalogDesiredSpec spec = spec(
                "source_catalog", LakeCatalogScope.ALL, List.of(), List.of(), Map.of());
        LakeJdbcCatalogDdlBuilder builder = new LakeJdbcCatalogDdlBuilder();

        String ddl = builder.buildCreateCatalog(
                spec, drivers, new LakeJdbcCatalogDdlBuilder.CatalogCredentials("reader", "p'ass"));

        assertTrue(ddl.startsWith("CREATE CATALOG `source_catalog` PROPERTIES"));
        assertTrue(ddl.contains("'type' = 'jdbc'"));
        assertTrue(ddl.contains("'user' = 'reader'"));
        assertTrue(ddl.contains("'password' = 'p''ass'"));
        assertTrue(ddl.contains("'driver_class' = 'com.mysql.cj.jdbc.Driver'"));
        assertTrue(ddl.contains("'only_specified_database' = 'false'"));
        assertFalse(LakeCatalogDesiredSpecCanonicalizer.canonicalJson(spec).contains("password"));
    }

    @Test
    void databaseAndTableScopeGenerateOnlyTheSupportedFilterProperties() {
        LakeJdbcDriverRegistry drivers = new LakeJdbcDriverRegistry(configuredCatalog());
        LakeJdbcCatalogDdlBuilder builder = new LakeJdbcCatalogDdlBuilder();
        LakeJdbcCatalogDdlBuilder.CatalogCredentials credentials =
                new LakeJdbcCatalogDdlBuilder.CatalogCredentials("reader", "secret");

        String databaseDdl = builder.buildCreateCatalog(
                spec("catalog", LakeCatalogScope.DATABASE, List.of("sales"), List.of(), Map.of()),
                drivers, credentials);
        assertTrue(databaseDdl.contains("'only_specified_database' = 'true'"));
        assertTrue(databaseDdl.contains("'include_database_list' = 'sales'"));
        assertFalse(databaseDdl.contains("include_table_list"));

        String tableDdl = builder.buildCreateCatalog(
                spec("catalog", LakeCatalogScope.TABLE, List.of("sales"),
                        List.of("orders", "Customers"), Map.of()),
                drivers, credentials);
        assertTrue(tableDdl.contains("'include_database_list' = 'sales'"));
        assertTrue(tableDdl.contains("'include_table_list' = 'sales.orders,sales.Customers'"));
    }

    @Test
    void actualPropertiesKeepOnlyWebOwnedKeysAndNormalizeTheirSpelling() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put(" TYPE ", " jdbc ");
        actual.put("JDBC-URL", "jdbc:postgresql://source.example/app");
        actual.put("include-table-list", "Sales.Orders");
        actual.put("LOWER_CASE_TABLE_NAMES", "false");
        actual.put("user", "must-not-be-exposed");
        actual.put("password", "must-not-be-exposed");
        actual.put("catalog_default_injected", "ignored");

        Map<String, String> owned = LakeCatalogDesiredSpecCanonicalizer
                .webOwnedActualProperties(actual);

        assertEquals(Map.of(
                "type", "jdbc",
                "jdbc_url", "jdbc:postgresql://source.example/app",
                "include_table_list", "Sales.Orders",
                "lower_case_table_names", "false"), owned);
        assertFalse(owned.containsKey("user"));
        assertFalse(owned.containsKey("password"));
        assertFalse(owned.containsKey("catalog_default_injected"));
    }

    @Test
    void externalNamesKeepAdapterCaseWhileCatalogNameIsCanonical() {
        LakeCatalogDesiredSpec oracle = new LakeCatalogDesiredSpec(
                "Oracle_Catalog", 17L, "source-v3", LakeJdbcAdapterType.ORACLE,
                LakeCatalogScope.TABLE, "jdbc:oracle:thin:@//db:1521/Service",
                "file:/opt/drivers/ojdbc8.jar", "oracle.jdbc.OracleDriver", CHECKSUM,
                "drivers-v1", "credential-v7", List.of("SALES"),
                List.of("MixedCaseTable"), Map.of());
        LakeCatalogDesiredSpec normalized =
                LakeCatalogDesiredSpecValidator.validateAndNormalize(oracle);
        assertEquals("oracle_catalog", normalized.catalogName());
        assertEquals(List.of("SALES"), normalized.databaseInclude());
        assertEquals(List.of("MixedCaseTable"), normalized.tableInclude());

        LakeCatalogDesiredSpec postgres = new LakeCatalogDesiredSpec(
                "pg_catalog", 18L, "source-v4", LakeJdbcAdapterType.POSTGRESQL,
                LakeCatalogScope.TABLE, "jdbc:postgresql://db:5432/app",
                "file:/opt/drivers/postgresql.jar", "org.postgresql.Driver", CHECKSUM,
                "drivers-v1", "credential-v8", List.of("Public"),
                List.of("OrderItems"), Map.of());
        assertEquals(List.of("Public"),
                LakeCatalogDesiredSpecValidator.validateAndNormalize(postgres).databaseInclude());
    }

    @Test
    void ddlUsesIdentifierAndLiteralEscapingAndRejectsUnverifiedDriver() {
        LakeJdbcCatalogDdlBuilder builder = new LakeJdbcCatalogDdlBuilder();
        assertEquals("REFRESH CATALOG `source_catalog`", builder.buildRefreshCatalog("source_catalog"));
        assertEquals("DROP CATALOG IF EXISTS `source_catalog`", builder.buildDropCatalog("source_catalog"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildRefreshCatalog("source_catalog;DROP"));

        LakeProperties.JdbcCatalog config = configuredCatalog();
        config.getMysql().setVerified(false);
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildCreateCatalog(
                        spec("catalog", LakeCatalogScope.ALL, List.of(), List.of(), Map.of()),
                        new LakeJdbcDriverRegistry(config),
                        new LakeJdbcCatalogDdlBuilder.CatalogCredentials("u", "p")));
    }

    @Test
    void alterCatalogUsesDorisGrammarAndGenericPathCannotAcceptSecrets() {
        LakeJdbcCatalogDdlBuilder builder = new LakeJdbcCatalogDdlBuilder();
        String ddl = builder.buildAlterCatalog(
                "source_catalog", Map.of("lower_case_meta_names", "true"));
        assertEquals("ALTER CATALOG `source_catalog` SET PROPERTIES ('lower_case_meta_names' = 'true')", ddl);
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildAlterCatalog("source_catalog", Map.of("password", "secret")));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildCreateCatalog(
                        spec("catalog", LakeCatalogScope.ALL, List.of(), List.of(), Map.of()),
                        new LakeJdbcDriverRegistry(),
                        new LakeJdbcCatalogDdlBuilder.CatalogCredentials("u", "p")));
    }

    private static LakeProperties.JdbcCatalog configuredCatalog() {
        LakeProperties.JdbcCatalog config = new LakeProperties.JdbcCatalog();
        config.setRegistryRevision("drivers-v1");
        LakeProperties.Driver mysql = LakeProperties.Driver.mysqlDefaults();
        mysql.setEnabled(true);
        mysql.setVerified(true);
        mysql.setUrl("file:/opt/drivers/mysql-8.0.39.jar");
        mysql.setChecksum(CHECKSUM);
        config.setMysql(mysql);
        return config;
    }

    private static LakeCatalogDesiredSpec spec(
            String catalogName,
            LakeCatalogScope scope,
            List<String> databases,
            List<String> tables,
            Map<String, String> options) {
        return new LakeCatalogDesiredSpec(
                catalogName,
                17L,
                "source-v3",
                LakeJdbcAdapterType.MYSQL,
                scope,
                "jdbc:mysql://source.example:3306/source",
                "file:/opt/drivers/mysql-8.0.39.jar",
                "com.mysql.cj.jdbc.Driver",
                CHECKSUM,
                "drivers-v1",
                "credential-v7",
                databases,
                tables,
                options);
    }
}
