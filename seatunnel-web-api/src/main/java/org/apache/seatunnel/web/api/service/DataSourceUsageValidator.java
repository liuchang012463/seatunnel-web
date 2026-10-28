package org.apache.seatunnel.web.api.service;

import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.common.enums.DataSourceUsage;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Component;

/** Enforces datasource purpose and connector capability at job-entry points. */
@Component
public class DataSourceUsageValidator {

    @Resource
    private DataSourceDao dataSourceDao;

    public void validate(JobDefinitionAnalysisResult analysis) {
        if (analysis == null) {
            return;
        }
        validate(analysis.getSourceDatasourceId(), DataSourceUsage.SOURCE);
        validate(analysis.getSinkDatasourceId(), DataSourceUsage.SINK);
    }

    public void validate(Long dataSourceId, DataSourceUsage usage) {
        validate(dataSourceId, usage, null);
    }

    public void validate(Long dataSourceId, DataSourceUsage usage, String pluginName) {
        if (dataSourceId == null) {
            return;
        }
        DataSource dataSource = dataSourceDao.queryById(dataSourceId);
        if (dataSource == null) {
            throw new ServiceException(Status.DATASOURCE_NOT_EXIST);
        }
        boolean enabled = usage == DataSourceUsage.SOURCE
                ? dataSource.getSourceEnabled() == null || dataSource.getSourceEnabled()
                : dataSource.getSinkEnabled() == null || dataSource.getSinkEnabled();
        if (!enabled) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "Data source " + dataSourceId + " is not enabled for " + usage.name().toLowerCase() + " usage.");
        }
        if (!supportsConnector(dataSource.getDbType(), usage, pluginName)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "Connector " + dataSource.getDbType() + " does not support "
                            + usage.name().toLowerCase() + " usage.");
        }
    }

    public static boolean supportsConnector(DbType dbType, DataSourceUsage usage, String pluginName) {
        if (dbType == null || usage == null) {
            return false;
        }
        try {
            DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(dbType);
            String builderName = resolveBuilderName(dbType, pluginName);
            var builder = processor.getQueryBuilder(builderName);
            return builder != null && (usage == DataSourceUsage.SOURCE
                    ? builder.supportsSource() : builder.supportsSink());
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveBuilderName(DbType dbType, String pluginName) {
        if (StringUtils.isNotBlank(pluginName)) {
            return "Jdbc".equalsIgnoreCase(pluginName.trim())
                    ? jdbcBuilderName(dbType) : pluginName.trim();
        }
        return switch (dbType) {
            case JDBC, MYSQL, ORACLE, KINGBASE, DAMENG, VASTBASE -> jdbcBuilderName(dbType);
            case POSTGRE_SQL, ZEONEDB -> "JDBC-POSTGRESQL";
            case DORIS -> "DORIS";
            case ELASTICSEARCH -> "ELASTICSEARCH";
            case KAFKA -> "KAFKA";
            case HTTP -> "HTTP";
            case FTP -> "FtpFile";
            case SFTP -> "SftpFile";
            case S3, MINIO -> "S3File";
            case H2 -> "JDBC-H2";
        };
    }

    private static String jdbcBuilderName(DbType dbType) {
        return switch (dbType) {
            case POSTGRE_SQL, ZEONEDB -> "JDBC-POSTGRESQL";
            default -> "JDBC-" + dbType.name();
        };
    }
}
