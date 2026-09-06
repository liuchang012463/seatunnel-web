package org.apache.seatunnel.web.api.lake.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeCatalogCredentialRevisionServiceTest {

    private static final String PASSWORD = "password-must-not-escape";

    @Test
    void revisionIsStableAndChangesWhenCredentialChanges() {
        LakeProperties properties = properties();
        DataSource source = source(17L, DbType.MYSQL);
        AtomicInteger parses = new AtomicInteger();
        BaseConnectionParam parameter = parameter("reader", PASSWORD);
        LakeCatalogCredentialRevisionService service = new LakeCatalogCredentialRevisionService(
                properties, ignored -> {
                    parses.incrementAndGet();
                    return parameter;
                });

        String first = service.credentialRevision(source, LakeJdbcAdapterType.MYSQL);
        String second = service.credentialRevision(source, LakeJdbcAdapterType.MYSQL);
        assertEquals(first, second);
        assertEquals(2, parses.get(), "connection params are resolved per execution");
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));

        parameter.setPassword("a-different-password");
        assertNotEquals(first,
                service.credentialRevision(source, LakeJdbcAdapterType.MYSQL));
    }

    @Test
    void executionCredentialsAndExceptionsDoNotExposeSecrets() throws Exception {
        LakeProperties properties = properties();
        DataSource source = source(18L, DbType.MYSQL);
        LakeCatalogCredentialRevisionService service = new LakeCatalogCredentialRevisionService(
                properties, ignored -> parameter("reader", PASSWORD));

        LakeCatalogCredentialRevisionService.ExecutionCredentials execution =
                service.resolveForExecution(source, LakeJdbcAdapterType.MYSQL);
        String json = new ObjectMapper().writeValueAsString(execution);
        assertFalse(json.contains(PASSWORD));
        assertFalse(json.contains("reader"));
        assertFalse(json.contains("jdbc:mysql://source.example"));
        assertFalse(execution.toString().contains(PASSWORD));
        assertFalse(execution.toString().contains("reader"));
        assertFalse(execution.ddlCredentials().toString().contains(PASSWORD));
        assertFalse(execution.ddlCredentials().toString().contains("reader"));

        LakeCatalogCredentialRevisionService failing =
                new LakeCatalogCredentialRevisionService(properties, ignored -> {
                    throw new IllegalStateException("connectionParams password=" + PASSWORD);
                });
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> failing.resolveForExecution(source, LakeJdbcAdapterType.MYSQL));
        assertEquals("JDBC catalog source credentials are unavailable", error.getMessage());
        assertFalse(error.toString().contains(PASSWORD));
        assertFalse(error.toString().contains("connectionParams"));
    }

    @Test
    void credentialRevisionRequiresAConfiguredServerSecret() {
        LakeProperties properties = new LakeProperties();
        DataSource source = source(19L, DbType.MYSQL);
        LakeCatalogCredentialRevisionService service = new LakeCatalogCredentialRevisionService(
                properties, ignored -> parameter("reader", PASSWORD));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.credentialRevision(source, LakeJdbcAdapterType.MYSQL));
        assertEquals("Catalog credential revision secret is not configured", error.getMessage());
    }

    @Test
    void onlyMatchingEnabledJdbcSourcesCanBeResolved() {
        LakeProperties properties = properties();
        LakeCatalogCredentialRevisionService service = new LakeCatalogCredentialRevisionService(
                properties, ignored -> parameter("reader", PASSWORD));

        DataSource disabled = source(20L, DbType.MYSQL);
        disabled.setStatus(DataSourceLifecycleStatus.DISABLED);
        IllegalArgumentException disabledError = assertThrows(IllegalArgumentException.class,
                () -> service.resolveForExecution(disabled, LakeJdbcAdapterType.MYSQL));
        assertFalse(disabledError.toString().contains(PASSWORD));

        DataSource postgres = source(21L, DbType.POSTGRE_SQL);
        IllegalArgumentException typeError = assertThrows(IllegalArgumentException.class,
                () -> service.resolveForExecution(postgres, LakeJdbcAdapterType.MYSQL));
        assertFalse(typeError.toString().contains(PASSWORD));
    }

    private static LakeProperties properties() {
        LakeProperties properties = new LakeProperties();
        properties.setCatalogCredentialSecret("server-only-catalog-secret");
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

    private static BaseConnectionParam parameter(String user, String password) {
        BaseConnectionParam parameter = new BaseConnectionParam() {
        };
        parameter.setDbType(DbType.MYSQL);
        parameter.setUrl("jdbc:mysql://source.example/app");
        parameter.setUser(user);
        parameter.setPassword(password);
        return parameter;
    }
}
