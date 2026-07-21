package org.apache.seatunnel.plugin.datasource.jdbc.param;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcParamConverter;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

public class JdbcConnectionParamConverter implements JdbcParamConverter {

    @Override
    public BaseConnectionParam createConnectionParams(String connectionJson) {
        JdbcConnectionParam param = JSONUtils.parseObject(connectionJson, JdbcConnectionParam.class);
        if (param == null) {
            throw new IllegalArgumentException("JDBC connection param must not be null");
        }
        param.setDbType(DbType.JDBC);
        return param;
    }

    @Override
    public void checkDatasourceParam(BaseConnectionParam baseConnectionParam) {
        if (!(baseConnectionParam instanceof JdbcConnectionParam)) {
            throw new IllegalArgumentException("Invalid JDBC connection param type");
        }

        JdbcConnectionParam param = (JdbcConnectionParam) baseConnectionParam;
        require(param.getUrl(), "JDBC URL");
        require(param.getDriver(), "JDBC driver class");
        require(param.getDriverLocation(), "JDBC driver location");
        require(param.getUser(), "JDBC user");
        require(param.getPassword(), "JDBC password");
    }

    private void require(String value, String label) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(label + " cannot be empty");
        }
    }
}
