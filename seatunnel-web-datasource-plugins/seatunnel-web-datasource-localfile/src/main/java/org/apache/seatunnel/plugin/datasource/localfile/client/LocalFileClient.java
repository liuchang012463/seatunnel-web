package org.apache.seatunnel.plugin.datasource.localfile.client;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.localfile.param.LocalFileConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Checks that the configured local base directory exists and is readable. */
public class LocalFileClient implements ConnectivityVerifier {

    @Override
    public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof LocalFileConnectionParam)) {
            throw new IllegalArgumentException("Invalid LOCAL_FILE connection param type");
        }
        Path basePath = Paths.get(StringUtils.trim(((LocalFileConnectionParam) connectionParam).getBasePath()));
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            return false;
        }
        if (!Files.isReadable(basePath)) {
            return false;
        }
        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(basePath)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
