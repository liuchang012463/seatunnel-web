package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.LakeDataSourceResolver;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Provides Doris clients from the persisted ODS configuration. */
@Component
public class LakeDorisClientProvider {

    private final LakeWarehouseService warehouseService;
    private final DataSourceDao legacyDataSourceDao;
    private final LakeDataSourceResolver dataSourceResolver;
    private final LakeProperties properties;

    @Autowired
    public LakeDorisClientProvider(
            LakeWarehouseService warehouseService,
            LakeDataSourceResolver dataSourceResolver,
            LakeProperties properties) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService");
        this.legacyDataSourceDao = null;
        this.dataSourceResolver = Objects.requireNonNull(dataSourceResolver, "dataSourceResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** Compatibility constructor retained for focused legacy tests. */
    public LakeDorisClientProvider(
            DataSourceDao dataSourceDao,
            LakeDataSourceResolver dataSourceResolver,
            LakeProperties properties) {
        this.warehouseService = null;
        this.legacyDataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.dataSourceResolver = Objects.requireNonNull(dataSourceResolver, "dataSourceResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public DorisLakeClient get(Long requestedLakeDataSourceId) {
        try {
            Long id;
            if (warehouseService != null) {
                id = warehouseService.canonicalDataSourceId(requestedLakeDataSourceId);
                // The resolver reads the warehouse table directly.  The ID is
                // retained only for task compatibility and alias validation.
                return new JdbcDorisLakeClient(dataSourceResolver.resolve(id), properties);
            }
            id = requestedLakeDataSourceId;
            if (id == null || id <= 0) {
                throw unavailable();
            }
            DataSource dataSource = legacyDataSourceDao.queryById(id);
            if (dataSource == null || dataSource.getDbType() != DbType.DORIS
                    || (dataSource.getStatus() != null
                    && dataSource.getStatus() != DataSourceLifecycleStatus.ENABLED)) {
                throw unavailable();
            }
            return new JdbcDorisLakeClient(dataSourceResolver.resolve(id), properties);
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static LakeServiceException unavailable() {
        return new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                "湖 ODS 数据湖不可用，请先完成数据湖配置并检查连接");
    }
}
