package org.apache.seatunnel.plugin.datasource.ftp.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteFileHoconBuilderTest {
    @Test void buildsFtpBinaryIncrementalSource() {
        Config connection = ConfigFactory.parseMap(Map.of(
                "host", "ftp.example.com", "port", 21, "user", "sync", "password", "secret",
                "connectionMode", "PASSIVE_LOCAL", "remoteVerificationEnabled", true));
        Config node = ConfigFactory.parseMap(Map.of(
                "path", "/incoming", "targetPath", "/archive", "syncType", "INCREMENTAL",
                "binaryChunkSize", 1048576));
        Config result = new FtpFileHoconBuilder().buildSourceHocon(HoconBuildContext.builder()
                .connectionConfig(connection).connectionParam("{}").nodeConfig(node).build());
        assertEquals("binary", result.getString("file_format_type"));
        assertEquals("passive_local", result.getString("connection_mode"));
        assertTrue(result.getBoolean("read_update_info"));
        assertEquals("len_mtime", result.getString("file_details_info"));
        assertFalse(result.hasPath("syncType"));
    }

    @Test void buildsSftpBinarySinkWithTransaction() {
        Config connection = ConfigFactory.parseMap(Map.of(
                "host", "sftp.example.com", "port", 22, "user", "sync", "password", "secret"));
        Config node = ConfigFactory.parseMap(Map.of("targetPath", "/archive"));
        Config result = new SftpFileHoconBuilder().buildSinkHocon(HoconBuildContext.builder()
                .connectionConfig(connection).connectionParam("{}").nodeConfig(node).build());
        assertEquals("/archive", result.getString("path"));
        assertEquals("/archive-seatunnel-tmp", result.getString("tmp_path"));
        assertTrue(result.getBoolean("is_enable_transaction"));
        assertFalse(result.hasPath("sink_columns"));
    }
}
