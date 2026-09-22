package org.apache.seatunnel.plugin.datasource.vastbase.param;

import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.constants.DataSourceConstants;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcParamConverter;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.Map;
import java.util.stream.Collectors;

/** Converts Web form values to Vastbase's PostgreSQL-compatible JDBC URL. */
public class VastbaseParamConverter implements JdbcParamConverter {

    @Override
    public BaseConnectionParam createConnectionParams(String connectionJson) {
        VastbaseConnectionParam connectionParam =
                JSONUtils.parseObject(connectionJson, VastbaseConnectionParam.class);
        if (connectionParam == null) {
            throw new IllegalArgumentException("Vastbase connection param must not be null");
        }

        connectionParam.setDbType(DbType.VASTBASE);
        connectionParam.setUrl(buildUrl(connectionParam));
        if (StringUtils.isBlank(connectionParam.getDriver())) {
            connectionParam.setDriver(DataSourceConstants.ORG_POSTGRESQL_DRIVER);
        }
        return connectionParam;
    }

    @Override
    public void checkDatasourceParam(BaseConnectionParam baseConnectionParam) {
        // The JDBC provider performs driver and connectivity validation.
    }

    private String buildUrl(VastbaseConnectionParam connectionParam) {
        String base = String.format("%s%s:%s/%s",
                DataSourceConstants.JDBC_VASTBASE,
                connectionParam.getHost(),
                connectionParam.getPort(),
                connectionParam.getDatabase());

        Map<String, String> other = connectionParam.getOtherAsMap();
        if (MapUtils.isEmpty(other)) {
            return base;
        }
        return base + "?" + other.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }
}
