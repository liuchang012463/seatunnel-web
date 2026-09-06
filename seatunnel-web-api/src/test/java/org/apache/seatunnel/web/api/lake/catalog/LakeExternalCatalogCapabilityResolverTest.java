package org.apache.seatunnel.web.api.lake.catalog;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LakeExternalCatalogCapabilityResolverTest {

    private static final String CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void verifiedMysqlIsTheOnlyDefaultOpenCapability() {
        DataSourceDao dao = mock(DataSourceDao.class);
        DataSource mysql = source(7L, DbType.MYSQL);
        when(dao.queryById(7L)).thenReturn(mysql);
        LakeProperties properties = properties(true);
        LakeJdbcDriverRegistry drivers = new LakeJdbcDriverRegistry(mysqlOnlyVerified());
        LakeExternalCatalogCapabilityResolver resolver = resolver(
                dao, properties, drivers);

        LakeCatalogCapability enabled = resolver.resolve(
                7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL);
        assertTrue(enabled.enabled());
        assertTrue(enabled.reasonCodes().isEmpty());

        DataSource postgres = source(8L, DbType.POSTGRE_SQL);
        when(dao.queryById(8L)).thenReturn(postgres);
        LakeCatalogCapability disabled = resolver.resolve(
                8L, LakeJdbcAdapterType.POSTGRESQL, LakeCatalogScope.ALL);
        assertFalse(disabled.enabled());
        assertTrue(disabled.reasonCodes().contains(
                LakeCatalogCapabilityReason.DRIVER_CONFIG_MISSING));
        assertTrue(disabled.reasonCodes().contains(
                LakeCatalogCapabilityReason.ADAPTER_DISABLED));
    }

    @Test
    void PostgresOrOracleOpenOnlyAfterCompleteVerifiedRegistryEntry() {
        DataSourceDao dao = mock(DataSourceDao.class);
        DataSource postgres = source(9L, DbType.POSTGRE_SQL);
        when(dao.queryById(9L)).thenReturn(postgres);
        LakeProperties properties = properties(true);
        LakeProperties.JdbcCatalog configuration = mysqlOnlyVerified();
        LakeProperties.Driver pg = LakeProperties.Driver.postgresqlDefaults();
        pg.setEnabled(true);
        pg.setVerified(true);
        pg.setUrl("file:/opt/drivers/postgresql.jar");
        pg.setChecksum(CHECKSUM);
        configuration.setPostgresql(pg);

        LakeExternalCatalogCapabilityResolver resolver = resolver(
                dao, properties, new LakeJdbcDriverRegistry(configuration));
        LakeCatalogCapability capability = resolver.resolve(
                9L, LakeJdbcAdapterType.POSTGRESQL, LakeCatalogScope.DATABASE);
        assertTrue(capability.enabled());
        assertFalse(capability.reasonCodes().contains(
                LakeCatalogCapabilityReason.ADAPTER_DISABLED));
    }

    @Test
    void resolverReportsStableSafeReasonsForSourceAndControlPlaneState() {
        DataSourceDao dao = mock(DataSourceDao.class);
        when(dao.queryById(404L)).thenThrow(new IllegalStateException(
                "jdbc password=should-not-escape"));
        LakeExternalCatalogCapabilityResolver resolver = resolver(
                dao, properties(false), new LakeJdbcDriverRegistry(mysqlOnlyVerified()));

        LakeCatalogCapability missing = resolver.resolve(
                404L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL);
        assertFalse(missing.enabled());
        assertTrue(missing.reasonCodes().contains(
                LakeCatalogCapabilityReason.LAKE_CONTROL_PLANE_DISABLED));
        assertTrue(missing.reasonCodes().contains(
                LakeCatalogCapabilityReason.SOURCE_NOT_FOUND));
        assertFalse(missing.toString().contains("should-not-escape"));

        DataSource disabled = source(10L, DbType.MYSQL);
        disabled.setStatus(DataSourceLifecycleStatus.DISABLED);
        LakeCatalogCapability unavailable = resolver.resolve(
                disabled, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL);
        assertFalse(unavailable.enabled());
        assertTrue(unavailable.reasonCodes().contains(
                LakeCatalogCapabilityReason.SOURCE_DISABLED));
    }

    @Test
    void explicitReachabilityFailuresRemainDisabledWithoutLeakingConfiguration() {
        DataSourceDao dao = mock(DataSourceDao.class);
        DataSource mysql = source(11L, DbType.MYSQL);
        when(dao.queryById(11L)).thenReturn(mysql);
        LakeExternalCatalogCapabilityResolver resolver = resolver(
                dao, properties(true), new LakeJdbcDriverRegistry(mysqlOnlyVerified()));

        LakeCatalogCapability capability = resolver.resolve(
                11L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL, false, false);
        assertFalse(capability.enabled());
        assertTrue(capability.reasonCodes().contains(
                LakeCatalogCapabilityReason.LAKE_DORIS_UNREACHABLE));
        assertTrue(capability.reasonCodes().contains(
                LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE));
        assertFalse(capability.toString().contains("jdbc:mysql"));
    }

    private static LakeExternalCatalogCapabilityResolver resolver(
            DataSourceDao dao,
            LakeProperties properties,
            LakeJdbcDriverRegistry drivers) {
        return new LakeExternalCatalogCapabilityResolver(
                dao, properties, drivers, new LakeJdbcCatalogAdapterRegistry());
    }

    private static LakeProperties properties(boolean enabled) {
        LakeProperties properties = new LakeProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private static DataSource source(Long id, DbType dbType) {
        DataSource source = new DataSource();
        source.setId(id);
        source.setDbType(dbType);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        source.setConnectionParams("{\"url\":\"jdbc:mysql://source.example/app\"}");
        return source;
    }

    private static LakeProperties.JdbcCatalog mysqlOnlyVerified() {
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
}
