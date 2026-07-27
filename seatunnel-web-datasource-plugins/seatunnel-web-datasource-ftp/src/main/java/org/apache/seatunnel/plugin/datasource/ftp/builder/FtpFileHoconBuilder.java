package org.apache.seatunnel.plugin.datasource.ftp.builder;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;

import java.util.Map;

@AutoService(DataSourceHoconBuilder.class)
public class FtpFileHoconBuilder extends AbstractRemoteFileHoconBuilder {
    @Override public String pluginName() { return "FtpFile"; }

    @Override
    protected void appendProtocolConnection(Map<String, Object> result, Map<String, Object> connection) {
        result.put("connection_mode", enumValue(connection.get("connectionMode"), "passive_local"));
        Object verify = connection.get("remoteVerificationEnabled");
        result.put("remote_verification_enabled", verify == null || Boolean.parseBoolean(String.valueOf(verify)));
    }
}
