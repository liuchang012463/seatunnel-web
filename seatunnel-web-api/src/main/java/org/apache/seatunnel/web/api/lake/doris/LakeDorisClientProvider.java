package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.LakeDataSourceResolver;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates the bounded Doris client for the configured single lake data source. */
@Component
public class LakeDorisClientProvider {

    private final DataSourceDao dataSourceDao;
    private final LakeDataSourceResolver dataSourceResolver;
    private final LakeProperties properties;

    @Autowired
    public LakeDorisClientProvider(
            DataSourceDao dataSourceDao,
            LakeDataSourceResolver dataSourceResolver,
            LakeProperties properties) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.dataSourceResolver = Objects.requireNonNull(dataSourceResolver, "dataSourceResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public DorisLakeClient get(Long lakeDataSourceId) {
        if (!properties.isEnabled() || lakeDataSourceId == null || lakeDataSourceId <= 0) {
            throw unavailable();
        }
        DataSource dataSource = dataSourceDao.queryById(lakeDataSourceId);
        if (dataSource == null || dataSource.getDbType() != DbType.DORIS
                || (dataSource.getStatus() != null
                && dataSource.getStatus() != DataSourceLifecycleStatus.ENABLED)) {
            throw unavailable();
        }
        try {
            return new JdbcDorisLakeClient(dataSourceResolver.resolve(lakeDataSourceId), properties);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static LakeServiceException unavailable() {
        return new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                "Lake Doris data source is unavailable");
    }
}
