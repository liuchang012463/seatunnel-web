package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.LakeDataSourceResolver;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LakeDorisClientProviderTest {

    @Test
    void resolverFailureIsClassifiedWithoutUntrustedCauseOrMessage() {
        DataSourceDao dataSourceDao = mock(DataSourceDao.class);
        LakeDataSourceResolver resolver = mock(LakeDataSourceResolver.class);
        LakeProperties properties = new LakeProperties();
        properties.setEnabled(true);
        DataSource lake = new DataSource();
        lake.setId(99L);
        lake.setDbType(DbType.DORIS);
        lake.setStatus(DataSourceLifecycleStatus.ENABLED);
        when(dataSourceDao.queryById(99L)).thenReturn(lake);
        when(resolver.resolve(99L)).thenThrow(new IllegalArgumentException(
                "jdbc password=should-not-escape"));

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> new LakeDorisClientProvider(dataSourceDao, resolver, properties).get(99L));

        assertEquals(LakeErrorCode.LAKE_DORIS_UNAVAILABLE, exception.getLakeErrorCode());
        assertNull(exception.getCause());
        assertFalse(exception.getMessage().contains("should-not-escape"));
    }
}
