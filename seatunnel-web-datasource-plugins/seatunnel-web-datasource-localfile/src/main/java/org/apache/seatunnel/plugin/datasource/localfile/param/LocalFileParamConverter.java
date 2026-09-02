package org.apache.seatunnel.plugin.datasource.localfile.param;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

public class LocalFileParamConverter implements ConnectionParamConverter {

    private static final DbType DB_TYPE = DbType.LOCAL_FILE;

    @Override
    public LocalFileConnectionParam createConnectionParams(String connectionJson) {
        LocalFileConnectionParam param =
                JSONUtils.parseObject(StringUtils.defaultIfBlank(connectionJson, "{}"), LocalFileConnectionParam.class);
        if (param == null) {
            throw new IllegalArgumentException(DB_TYPE + " connection param must not be null");
        }
        param.setDbType(DB_TYPE);
        return param;
    }

    @Override
    public void checkDatasourceParam(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof LocalFileConnectionParam)) {
            throw new IllegalArgumentException("Invalid " + DB_TYPE + " connection param type");
        }
        LocalFileConnectionParam param = (LocalFileConnectionParam) connectionParam;
        if (StringUtils.isBlank(param.getBasePath()) || !param.getBasePath().startsWith("/")) {
            throw new IllegalArgumentException(DB_TYPE + " basePath must be an absolute local path");
        }
    }
}
