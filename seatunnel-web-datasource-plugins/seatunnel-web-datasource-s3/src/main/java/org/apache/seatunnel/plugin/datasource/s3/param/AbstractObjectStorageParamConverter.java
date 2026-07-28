package org.apache.seatunnel.plugin.datasource.s3.param;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.s3.client.ObjectStoragePathUtils;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.net.URI;

public abstract class AbstractObjectStorageParamConverter<T extends ObjectStorageConnectionParam>
        implements ConnectionParamConverter {

    private final Class<T> type;
    private final DbType dbType;

    protected AbstractObjectStorageParamConverter(Class<T> type, DbType dbType) {
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
        if (StringUtils.isNotBlank(param.getEndpoint())) {
            param.setEndpoint(stripTrailingSlash(param.getEndpoint().trim()));
        }
        param.setBasePath(ObjectStoragePathUtils.normalizeAbsolute(param.getBasePath()));
        return param;
    }

    @Override
    public void checkDatasourceParam(ConnectionParam connectionParam) {
        if (!type.isInstance(connectionParam)) {
            throw new IllegalArgumentException("Invalid " + dbType + " connection param type");
        }
        T param = type.cast(connectionParam);
        validateEndpoint(param.getEndpoint());
        if (StringUtils.isBlank(param.getRegion())) {
            throw new IllegalArgumentException(dbType + " region cannot be empty");
        }
        if (StringUtils.isBlank(param.getBucket())
                || param.getBucket().contains("/")
                || param.getBucket().contains(":")) {
            throw new IllegalArgumentException(dbType + " bucket must be a bucket name without protocol or path");
        }
        ObjectStoragePathUtils.normalizeAbsolute(param.getBasePath());
        if (param.getConnectTimeoutMs() == null || param.getConnectTimeoutMs() <= 0
                || param.getRequestTimeoutMs() == null || param.getRequestTimeoutMs() <= 0) {
            throw new IllegalArgumentException(dbType + " timeouts must be greater than 0");
        }
        validateCredentials(param);
    }

    protected abstract void validateCredentials(T param);

    protected static void requireStaticCredentials(ObjectStorageConnectionParam param) {
        if (StringUtils.isBlank(param.accessKey()) || StringUtils.isBlank(param.secretKey())) {
            throw new IllegalArgumentException(param.getDbType() + " accessKey and secretKey cannot be empty");
        }
    }

    private void validateEndpoint(String endpoint) {
        if (StringUtils.isBlank(endpoint)) {
            throw new IllegalArgumentException(dbType + " endpoint cannot be empty");
        }
        try {
            URI uri = URI.create(endpoint);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || StringUtils.isBlank(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException(dbType + " endpoint must be an http(s) URL without credentials, query, or fragment");
            }
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith(dbType.toString())) {
                throw ex;
            }
            throw new IllegalArgumentException(dbType + " endpoint is invalid", ex);
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
