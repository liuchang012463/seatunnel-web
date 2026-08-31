package org.apache.seatunnel.web.api.lake.ods;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LakeOdsMasterDataResolverTest {

    @Test
    void missingBusinessSystemHasStableIncompleteError() {
        DataSourceDao sourceDao = mock(DataSourceDao.class);
        BusinessSystemDao systemDao = mock(BusinessSystemDao.class);
        DataSourceUnitDao unitDao = mock(DataSourceUnitDao.class);
        DataSource source = source(7L);
        when(sourceDao.queryById(7L)).thenReturn(source);

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> new LakeOdsMasterDataResolver(sourceDao, systemDao, unitDao)
                        .resolve(7L, "orders"));

        assertEquals(LakeErrorCode.LAKE_MASTER_DATA_INCOMPLETE, exception.getLakeErrorCode());
    }

    @Test
    void legacyDisplayNameInUnitCodeHasStableInvalidCodeError() {
        DataSourceDao sourceDao = mock(DataSourceDao.class);
        BusinessSystemDao systemDao = mock(BusinessSystemDao.class);
        DataSourceUnitDao unitDao = mock(DataSourceUnitDao.class);
        when(sourceDao.queryById(7L)).thenReturn(source(7L));

        BusinessSystem system = new BusinessSystem();
        system.setId(11L);
        system.setUnitId(12L);
        system.setSystemCode("OMS");
        system.setStatus(1);
        when(systemDao.queryById(11L)).thenReturn(system);

        DataSourceUnit unit = new DataSourceUnit();
        unit.setId(12L);
        unit.setUnitCode("Head Office");
        unit.setStatus(1);
        when(unitDao.queryById(12L)).thenReturn(unit);

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> new LakeOdsMasterDataResolver(sourceDao, systemDao, unitDao)
                        .resolve(7L, "orders"));

        assertEquals(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID, exception.getLakeErrorCode());
    }

    private static DataSource source(Long id) {
        DataSource source = new DataSource();
        source.setId(id);
        source.setDbType(DbType.MYSQL);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        source.setBusinessSystemId(11L);
        return source;
    }
}
