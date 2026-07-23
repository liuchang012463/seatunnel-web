package org.apache.seatunnel.plugin.datasource.sftp.param;

import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcParamConverter;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class SftpParamConverter implements JdbcParamConverter {

    @Override
    public BaseConnectionParam createConnectionParams(String connectionJson) {
        if (connectionJson == null || connectionJson.isEmpty()) {
            throw new IllegalArgumentException("Connection JSON cannot be empty");
        }

        try {
            Map<String, String> jsonMap = JSONUtils.toMap(connectionJson);

            SftpConnectionParam param = new SftpConnectionParam();

            param.setHost(getString(jsonMap, "host", "127.0.0.1"));
            param.setPort(getString(jsonMap, "port", "22"));
            // SeaTunnel 2.3.13 uses `user`. Keep `username` as a read-only
            // fallback so data sources created by the early SFTP prototype
            // can still be edited and tested.
            param.setUser(getString(jsonMap, "user", getString(jsonMap, "username", "")));
            param.setPassword(getString(jsonMap, "password", ""));
            param.setStrictHostKeyChecking(getBoolean(jsonMap, "strictHostKeyChecking", false));
            param.setKnownHostsPath(getString(jsonMap, "knownHostsPath", ""));

            param.setDbType(DbType.SFTP);

            return param;
        } catch (Exception e) {
            // Do not write connection JSON to logs because it contains the password.
            log.error("Failed to parse SFTP connection parameters", e);
            throw new IllegalArgumentException(
                    "Failed to parse SFTP connection parameters: " + e.getMessage(), e);
        }
    }

    @Override
    public void checkDatasourceParam(BaseConnectionParam baseConnectionParam) {
        if (baseConnectionParam == null) {
            throw new IllegalArgumentException("Connection parameter cannot be null");
        }

        SftpConnectionParam param = (SftpConnectionParam) baseConnectionParam;

        if (param.getHost() == null || param.getHost().isEmpty()) {
            throw new IllegalArgumentException("SFTP host cannot be empty");
        }

        if (param.getPort() == null || param.getPort().isEmpty()) {
            throw new IllegalArgumentException("SFTP port cannot be empty");
        }

        if (param.getUser() == null || param.getUser().isEmpty()) {
            throw new IllegalArgumentException("SFTP username cannot be empty");
        }

        try {
            int port = Integer.parseInt(param.getPort());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("SFTP port must be between 1 and 65535");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SFTP port must be a valid number", e);
        }

        // The SeaTunnel 2.3.13 SftpFile connector requires password
        // authentication and does not expose a private-key option.
        if (param.getPassword() == null || param.getPassword().isEmpty()) {
            throw new IllegalArgumentException("SFTP password cannot be empty");
        }

        if (Boolean.TRUE.equals(param.getStrictHostKeyChecking())
                && (param.getKnownHostsPath() == null || param.getKnownHostsPath().isEmpty())) {
            throw new IllegalArgumentException(
                    "Known hosts path is required when strict host key checking is enabled");
        }
    }

    private String getString(Map<String, String> map, String key, String defaultValue) {
        String value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value;
    }

    private Boolean getBoolean(Map<String, String> map, String key, Boolean defaultValue) {
        String value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}
