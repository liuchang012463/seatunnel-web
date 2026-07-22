package org.apache.seatunnel.plugin.datasource.api.jdbc;

import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.plugin.datasource.api.form.ReflectionFormGenerator;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;

import java.util.List;
import java.util.Optional;

/**
 * Data-source-level plugin entry.
 * Implementations compose datasource capabilities. JDBC-specific processors may continue to
 * expose their connection provider and catalog through the compatibility methods below.
 */
public interface DataSourceProcessor {

    /**
     * SQL builder for this database.
     */
    DataSourceHoconBuilder getQueryBuilder(String pluginName);

    /**
     * Connection factory for this database.
     */
    default JdbcConnectionProvider getConnectionManager() {
        throw new UnsupportedOperationException(getDbType() + " is not a JDBC datasource");
    }

    default ConnectivityVerifier getConnectivityVerifier() {
        return getConnectionManager();
    }

    /**
     * JSON-to-param converter for this database.
     */
    ConnectionParamConverter getParamConverter();

    /**
     * Metadata reader for this database.
     */
    default JdbcCatalog getMetadataService(BaseConnectionParam connectionParam) {
        throw new UnsupportedOperationException(getDbType() + " does not expose a JDBC catalog");
    }

    default Optional<DataSourceCatalog> getCatalog(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof BaseConnectionParam)) {
            return Optional.empty();
        }
        return Optional.of(getMetadataService((BaseConnectionParam) connectionParam));
    }

    default boolean supportsCatalog() {
        return true;
    }

    /**
     * Validated option set for source (read) side.
     */
    OptionRule sourceOptionRule(String pluginName);

    /**
     * Validated option set for sink (write) side.
     */
    OptionRule sinkOptionRule();

    default OptionRule sinkOptionRule(String pluginName) {
        return sinkOptionRule();
    }

    /**
     * Database type identifier.
     */
    DbType getDbType();

    /**
     * Create a new processor instance.
     * Allows each thread to obtain an isolated copy.
     */
    DataSourceProcessor create();

    /**
     * Job definition analyzer for this datasource processor.
     *
     * Used to extract datasource id, datasource type and table information
     * from source/sink job definition.
     */
    JobDefinitionAnalyzer getJobDefinitionAnalyzer();

    default List<FormFieldConfig> generateFormFields() {

        ConnectionParam param = getParamConverter().createConnectionParams("{}");

        return ReflectionFormGenerator.generate(param.getClass());
    }

    /**
     * Whether this processor supports the given JDBC url.
     */
    default boolean acceptsURL(String url) {
        return false;
    }

    /**
     * SQL used by SeaTunnel connectivity test job.
     *
     * <p>
     * Different databases may require different syntax.
     * For example, Oracle requires "from dual".
     * </p>
     */
    default String connectivityCheckSql() {
        return "select 1 as connectivity_check";
    }
}
