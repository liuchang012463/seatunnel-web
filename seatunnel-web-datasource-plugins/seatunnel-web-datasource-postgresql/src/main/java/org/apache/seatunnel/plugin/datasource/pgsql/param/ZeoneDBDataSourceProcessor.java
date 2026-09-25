package org.apache.seatunnel.plugin.datasource.pgsql.param;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcParamConverter;
import org.apache.seatunnel.plugin.datasource.pgsql.analysis.ZeoneDBJobDefinitionAnalyzer;
import org.apache.seatunnel.web.spi.enums.DbType;

/** PostgreSQL-compatible ZeoneDB datasource support for batch JDBC ingestion. */
@AutoService(DataSourceProcessor.class)
public class ZeoneDBDataSourceProcessor extends PgSQLDataSourceProcessor {

    private final JdbcParamConverter paramConverter = new ZeoneDBParamConverter();
    private final JobDefinitionAnalyzer jobDefinitionAnalyzer = new ZeoneDBJobDefinitionAnalyzer();

    @Override
    public JdbcParamConverter getParamConverter() {
        return paramConverter;
    }

    @Override
    public DbType getDbType() {
        return DbType.ZEONEDB;
    }

    @Override
    public boolean acceptsURL(String url) {
        // A jdbc:postgresql URL does not distinguish ZeoneDB from PostgreSQL.
        // Resolve this connector from the explicitly selected ZEONEDB type.
        return false;
    }

    @Override
    public DataSourceProcessor create() {
        return new ZeoneDBDataSourceProcessor();
    }

    @Override
    public JobDefinitionAnalyzer getJobDefinitionAnalyzer() {
        return jobDefinitionAnalyzer;
    }
}
