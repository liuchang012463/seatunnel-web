package org.apache.seatunnel.web.api.lake;

import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LakeSecurityFoundationTest {

    @Test
    void identifiersAndLiteralsHaveSeparateEscapingRules() {
        assertEquals("`lake_table`", DorisIdentifier.quote("lake_table"));
        assertEquals("`lake_db`.`lake_table`", DorisIdentifier.quoteQualified("lake_db.lake_table"));
        assertEquals("lake_table", DorisIdentifier.normalize(" Lake_Table "));
        assertThrows(IllegalArgumentException.class, () -> DorisIdentifier.quote("lake_table;DROP"));
        assertThrows(IllegalArgumentException.class, () -> DorisIdentifier.quote("lake.table"));

        assertEquals("'a''b\\\\c\\nline'", DorisSqlLiteral.quote("a'b\\c\nline"));
        assertEquals("NULL", DorisSqlLiteral.quote(null));
    }

    @Test
    void catalogPropertiesAreAllowlistedAndRedactedRecursively() {
        assertTrue(CatalogPropertyWhitelist.isAllowed("jdbc_url"));
        assertTrue(CatalogPropertyWhitelist.isAllowed("PASSWORD"));
        assertFalse(CatalogPropertyWhitelist.isAllowed("arbitrary_sql"));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogPropertyWhitelist.validateAndCopy(Map.of("arbitrary", "x")));

        Map<String, Object> redacted = CatalogPropertyRedactor.redactMap(Map.of(
                "user", "lake",
                "password", "do-not-log",
                "nested", Map.of("connectionParams", "{password:'do-not-log'}"),
                "safe", "value"));
        assertEquals(CatalogPropertyRedactor.MASK, redacted.get("password"));
        assertEquals(CatalogPropertyRedactor.MASK,
                ((Map<?, ?>) redacted.get("nested")).get("connectionParams"));
        assertEquals("value", redacted.get("safe"));
        String summary = LakeOperationLogRedactor.summary(
                "password='do-not-log' token=abc CREATE TABLE secret_table (id INT)");
        assertFalse(summary.contains("do-not-log"));
        assertFalse(summary.contains("secret_table"));
        assertEquals("[REDACTED_DDL]", summary);
    }

    @Test
    void resolverReusesPoolUntilDataSourceConfigurationChanges() {
        DataSourceDao dao = mock(DataSourceDao.class);
        DataSource entity = new DataSource();
        entity.setId(7L);
        entity.setDbType(DbType.DORIS);
        entity.setConnectionParams("{}");
        when(dao.queryById(7L)).thenReturn(entity);

        LakeProperties properties = new LakeProperties();
        properties.setEnabled(true);
        properties.setDataSourceId(7L);
        BaseConnectionParam param = new BaseConnectionParam() {
        };
        param.setUrl("jdbc:h2:mem:lake_resolver;DB_CLOSE_DELAY=-1");
        param.setDriver("org.h2.Driver");
        param.setUser("sa");
        param.setPassword("");

        try (LakeDataSourceResolver resolver = new LakeDataSourceResolver(dao, properties, ignored -> param)) {
            javax.sql.DataSource first = resolver.resolveConfigured();
            assertSame(first, resolver.resolve(7L));

            entity.setConnectionParams("{changed:true}");
            javax.sql.DataSource second = resolver.resolve(7L);
            assertNotSame(first, second);
        }
    }

    @Test
    void resolverDoesNotExposePluginConfigurationException() {
        DataSourceDao dao = mock(DataSourceDao.class);
        DataSource entity = new DataSource();
        entity.setId(8L);
        entity.setDbType(DbType.DORIS);
        entity.setConnectionParams("{password:'do-not-log'}");
        when(dao.queryById(8L)).thenReturn(entity);
        LakeProperties properties = new LakeProperties();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new LakeDataSourceResolver(dao, properties,
                        ignored -> {
                            throw new IllegalStateException("connectionParams password=do-not-log");
                        }).resolve(8L));
        assertEquals("Lake Doris data source configuration is invalid", error.getMessage());
        assertEquals(null, error.getCause());
        assertFalse(error.toString().contains("do-not-log"));
    }
}
