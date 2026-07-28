package org.apache.seatunnel.plugin.datasource.ftp.client;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.seatunnel.plugin.datasource.ftp.param.FtpConnectionMode;
import org.apache.seatunnel.plugin.datasource.ftp.param.FtpConnectionParam;
import org.apache.seatunnel.plugin.datasource.ftp.param.RemoteFileConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class FtpRemoteFileClient implements RemoteFileClient {
    @Override
    public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
        requireParam(connectionParam);
        listEntries((FtpConnectionParam) connectionParam, ((FtpConnectionParam) connectionParam).getBasePath());
        return true;
    }

    @Override
    public List<FileEntryVO> listEntries(RemoteFileConnectionParam connectionParam, String path) {
        FtpConnectionParam param = requireParam(connectionParam);
        String resolved = RemotePathUtils.resolveWithinBase(param.getBasePath(), path);
        FTPClient client = new FTPClient();
        try {
            client.setDefaultTimeout(param.getConnectTimeoutMs());
            client.setConnectTimeout(param.getConnectTimeoutMs());
            client.setDataTimeout(Duration.ofMillis(param.getDataTimeoutMs()));
            client.connect(param.getHost(), param.getPort());
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new IllegalStateException("FTP server rejected connection, reply=" + client.getReplyCode());
            }
            if (!client.login(param.getUser(), param.getPassword())) {
                throw new IllegalStateException("FTP login failed");
            }
            if (param.getConnectionMode() == FtpConnectionMode.ACTIVE_LOCAL) {
                client.enterLocalActiveMode();
            } else {
                client.enterLocalPassiveMode();
            }
            client.setRemoteVerificationEnabled(Boolean.TRUE.equals(param.getRemoteVerificationEnabled()));
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw new IllegalStateException("FTP server rejected binary transfer mode");
            }
            FTPFile[] files = client.listFiles(resolved);
            List<FileEntryVO> result = new ArrayList<>();
            for (FTPFile file : files) {
                if (".".equals(file.getName()) || "..".equals(file.getName())) {
                    continue;
                }
                String type = file.isDirectory() ? "DIRECTORY" : file.isSymbolicLink() ? "LINK" : "FILE";
                Long modified = file.getTimestampInstant() == null ? null : file.getTimestampInstant().toEpochMilli();
                result.add(new FileEntryVO(file.getName(), RemotePathUtils.child(resolved, file.getName()),
                        type, file.isFile() ? file.getSize() : null, modified));
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("FTP operation failed: " + ex.getMessage(), ex);
        } finally {
            if (client.isConnected()) {
                try { client.logout(); } catch (IOException ignored) { }
                try { client.disconnect(); } catch (IOException ignored) { }
            }
        }
    }

    private FtpConnectionParam requireParam(ConnectionParam param) {
        if (!(param instanceof FtpConnectionParam)) {
            throw new IllegalArgumentException("Invalid FTP connection param type");
        }
        return (FtpConnectionParam) param;
    }
}
