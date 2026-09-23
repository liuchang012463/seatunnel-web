package org.apache.seatunnel.plugin.datasource.dameng.connection;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.plugin.datasource.api.constants.DataSourceConstants;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.dameng.param.DamengConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.trimToEmpty;

@Slf4j
public class DamengConnectionProvider
        extends AbstractJdbcConnectionProvider<DamengConnectionParam> {

    private static final String INSTANCE_NAME_SQL = "SELECT name FROM v$database";

    @Override
    protected String defaultDriverClass() {
        return DataSourceConstants.COM_DAMENG_JDBC_DRIVER;
    }

    @Override
    protected String resolveDriverLocation(DamengConnectionParam t) {
        return defaultBaseUrl() + t.getDriverLocation();
    }

    /**
     * A TCP/JDBC handshake is not sufficient for Dameng.  The database form
     * value is also used as the catalog component of SeaTunnel table paths,
     * so it must match the live database instance name.
     */
    @Override
    public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof DamengConnectionParam param)) {
            return super.checkDataSourceConnectivity(connectionParam);
        }

        try (Connection connection = getConnection(param);
                PreparedStatement statement = connection.prepareStatement(INSTANCE_NAME_SQL);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Dameng did not return V$DATABASE.NAME");
            }

            String actualDatabase = trimToEmpty(resultSet.getString(1));
            String configuredDatabase = param.getDatabase();
            if (isNotBlank(configuredDatabase)
                    && !configuredDatabase.trim().equalsIgnoreCase(actualDatabase)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Dameng database instance mismatch: configured [%s], actual [%s]. "
                                        + "Use the actual V$DATABASE.NAME as database instance; "
                                        + "a table Owner belongs in the Schema/Owner field.",
                                configuredDatabase.trim(), actualDatabase));
            }

            return true;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Dameng connection or instance validation failed", e);
            return false;
        }
    }
}
