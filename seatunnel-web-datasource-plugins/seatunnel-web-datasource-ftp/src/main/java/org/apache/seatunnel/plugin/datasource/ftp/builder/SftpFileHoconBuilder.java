package org.apache.seatunnel.plugin.datasource.ftp.builder;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;

import java.util.Map;

@AutoService(DataSourceHoconBuilder.class)
public class SftpFileHoconBuilder extends AbstractRemoteFileHoconBuilder {
    @Override public String pluginName() { return "SftpFile"; }
    @Override protected void appendProtocolConnection(Map<String, Object> result, Map<String, Object> connection) { }
}
