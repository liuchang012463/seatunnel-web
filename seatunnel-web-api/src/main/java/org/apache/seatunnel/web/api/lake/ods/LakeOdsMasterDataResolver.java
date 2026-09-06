package org.apache.seatunnel.web.api.lake.ods;

import lombok.NonNull;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves ODS naming inputs exclusively from canonical master data. */
@Component
public class LakeOdsMasterDataResolver {

    private final DataSourceDao dataSourceDao;
    private final BusinessSystemDao businessSystemDao;
    private final DataSourceUnitDao dataSourceUnitDao;

    @Autowired
    public LakeOdsMasterDataResolver(
            @NonNull DataSourceDao dataSourceDao,
            @NonNull BusinessSystemDao businessSystemDao,
            @NonNull DataSourceUnitDao dataSourceUnitDao) {
        this.dataSourceDao = dataSourceDao;
        this.businessSystemDao = businessSystemDao;
        this.dataSourceUnitDao = dataSourceUnitDao;
    }

    public OdsDatabaseName resolve(Long sourceDataSourceId, String customName) {
        if (sourceDataSourceId == null || sourceDataSourceId <= 0) {
            throw incomplete("sourceDataSourceId is required");
        }
        DataSource source = dataSourceDao.queryById(sourceDataSourceId);
        if (source == null || source.getBusinessSystemId() == null) {
            throw incomplete("source data source has no BusinessSystem");
        }
        BusinessSystem system = businessSystemDao.queryById(source.getBusinessSystemId());
        if (system == null || system.getUnitId() == null) {
            throw incomplete("BusinessSystem or its unit does not exist");
        }
        DataSourceUnit unit = dataSourceUnitDao.queryById(system.getUnitId());
        if (unit == null) {
            throw incomplete("BusinessSystem unit does not exist");
        }
        if (inactive(system.getStatus()) || inactive(unit.getStatus())) {
            throw incomplete("BusinessSystem master data is inactive");
        }
        try {
            return OdsDatabaseNameValidator.build(unit.getUnitCode(), system.getSystemCode(), customName);
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID,
                    "BusinessSystem codes cannot form an ODS database name");
        }
    }

    private static boolean inactive(Integer status) {
        return !Integer.valueOf(1).equals(status);
    }

    private static LakeServiceException incomplete(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_MASTER_DATA_INCOMPLETE, message);
    }
}
