package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.common.enums.DataSourceUsage;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceUsageValidatorTest {

    private static final long DATA_SOURCE_ID = 73L;

    private DataSourceDao dataSourceDao;
    private DataSourceUsageValidator validator;

    @BeforeEach
    void setUp() {
        dataSourceDao = mock(DataSourceDao.class);
        validator = new DataSourceUsageValidator();
        ReflectionTestUtils.setField(validator, "dataSourceDao", dataSourceDao);
    }

    @Test
    void mapsJdbcPluginAliasesToRegisteredPostgresBuilders() {
        assertTrue(DataSourceUsageValidator.supportsConnector(
                DbType.POSTGRE_SQL, DataSourceUsage.SOURCE, "Jdbc"));
        assertTrue(DataSourceUsageValidator.supportsConnector(
                DbType.POSTGRE_SQL, DataSourceUsage.SINK, "Jdbc"));
        assertTrue(DataSourceUsageValidator.supportsConnector(
                DbType.ZEONEDB, DataSourceUsage.SOURCE, "Jdbc"));
        assertTrue(DataSourceUsageValidator.supportsConnector(
                DbType.ZEONEDB, DataSourceUsage.SINK, "Jdbc"));
        assertTrue(DataSourceUsageValidator.supportsConnector(
                DbType.HTTP, DataSourceUsage.SOURCE, "Http"));
        assertFalse(DataSourceUsageValidator.supportsConnector(
                DbType.HTTP, DataSourceUsage.SINK, "Http"));
    }

    @Test
    void rejectsAConnectionWhosePurposeWasDisabled() {
        DataSource sourceOnly = dataSource(DbType.MYSQL, true, false);
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(sourceOnly);

        assertThrows(ServiceException.class,
                () -> validator.validate(DATA_SOURCE_ID, DataSourceUsage.SINK));
        assertDoesNotThrow(() -> validator.validate(DATA_SOURCE_ID, DataSourceUsage.SOURCE));
    }

    @Test
    void treatsNullPurposeFlagsAsLegacyDualPurposeButStillChecksConnectorCapability() {
        DataSource legacyMysql = dataSource(DbType.MYSQL, null, null);
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(legacyMysql);
        assertDoesNotThrow(() -> validator.validate(DATA_SOURCE_ID, DataSourceUsage.SOURCE));
        assertDoesNotThrow(() -> validator.validate(DATA_SOURCE_ID, DataSourceUsage.SINK));

        DataSource legacyHttp = dataSource(DbType.HTTP, null, null);
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(legacyHttp);
        assertThrows(ServiceException.class,
                () -> validator.validate(DATA_SOURCE_ID, DataSourceUsage.SINK));
    }

    @Test
    void validatesEverySourceAndSinkIdFromMultiPluginScriptAnalysis() {
        when(dataSourceDao.queryById(11L)).thenReturn(dataSource(11L, DbType.MYSQL, true, true));
        when(dataSourceDao.queryById(12L)).thenReturn(dataSource(12L, DbType.MYSQL, true, true));
        when(dataSourceDao.queryById(21L)).thenReturn(dataSource(21L, DbType.MYSQL, true, true));
        when(dataSourceDao.queryById(22L)).thenReturn(dataSource(22L, DbType.MYSQL, true, false));

        JobDefinitionAnalysisResult analysis = JobDefinitionAnalysisResult.builder()
                .sourceDatasourceId(11L)
                .sourceDatasourceIds(java.util.List.of(11L, 12L))
                .sinkDatasourceId(21L)
                .sinkDatasourceIds(java.util.List.of(21L, 22L))
                .build();

        assertThrows(ServiceException.class, () -> validator.validate(analysis));
        when(dataSourceDao.queryById(22L)).thenReturn(dataSource(22L, DbType.MYSQL, true, true));
        assertDoesNotThrow(() -> validator.validate(analysis));
    }

    private static DataSource dataSource(DbType dbType, Boolean sourceEnabled, Boolean sinkEnabled) {
        return dataSource(DATA_SOURCE_ID, dbType, sourceEnabled, sinkEnabled);
    }

    private static DataSource dataSource(
            Long id, DbType dbType, Boolean sourceEnabled, Boolean sinkEnabled) {
        DataSource dataSource = new DataSource();
        dataSource.setId(id);
        dataSource.setDbType(dbType);
        dataSource.setSourceEnabled(sourceEnabled);
        dataSource.setSinkEnabled(sinkEnabled);
        return dataSource;
    }
}
