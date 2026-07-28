package org.apache.seatunnel.plugin.datasource.ftp.param;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

public abstract class AbstractRemoteFileParamConverter<T extends RemoteFileConnectionParam>
        implements ConnectionParamConverter {

    private final Class<T> type;
    private final DbType dbType;

    protected AbstractRemoteFileParamConverter(Class<T> type, DbType dbType) {
        this.type = type;
        this.dbType = dbType;
    }

    @Override
    public T createConnectionParams(String connectionJson) {
        T param = JSONUtils.parseObject(StringUtils.defaultIfBlank(connectionJson, "{}"), type);
        if (param == null) {
            throw new IllegalArgumentException(dbType + " connection param must not be null");
        }
        param.setDbType(dbType);
        return param;
    }

    @Override
    public void checkDatasourceParam(ConnectionParam connectionParam) {
        if (!type.isInstance(connectionParam)) {
            throw new IllegalArgumentException("Invalid " + dbType + " connection param type");
        }
        T param = type.cast(connectionParam);
        if (StringUtils.isBlank(param.getHost())) {
            throw new IllegalArgumentException(dbType + " host cannot be empty");
        }
        if (param.getPort() == null || param.getPort() <= 0 || param.getPort() > 65535) {
            throw new IllegalArgumentException(dbType + " port must be between 1 and 65535");
        }
        if (StringUtils.isBlank(param.getUser()) || StringUtils.isBlank(param.getPassword())) {
            throw new IllegalArgumentException(dbType + " username and password cannot be empty");
        }
        if (StringUtils.isBlank(param.getBasePath()) || !param.getBasePath().startsWith("/")) {
            throw new IllegalArgumentException(dbType + " basePath must be an absolute remote path");
        }
        if (param.getConnectTimeoutMs() == null || param.getConnectTimeoutMs() <= 0
                || param.getDataTimeoutMs() == null || param.getDataTimeoutMs() <= 0) {
            throw new IllegalArgumentException(dbType + " timeouts must be greater than 0");
        }
    }
}
