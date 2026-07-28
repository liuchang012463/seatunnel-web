package org.apache.seatunnel.plugin.datasource.ftp.client;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.apache.seatunnel.plugin.datasource.ftp.param.RemoteFileConnectionParam;
import org.apache.seatunnel.plugin.datasource.ftp.param.SftpConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

public class SftpRemoteFileClient implements RemoteFileClient {
    @Override
    public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
        requireParam(connectionParam);
        listEntries((SftpConnectionParam) connectionParam, ((SftpConnectionParam) connectionParam).getBasePath());
        return true;
    }

    @Override
    public List<FileEntryVO> listEntries(RemoteFileConnectionParam connectionParam, String path) {
        SftpConnectionParam param = requireParam(connectionParam);
        String resolved = RemotePathUtils.resolveWithinBase(param.getBasePath(), path);
        Session session = null;
        ChannelSftp sftp = null;
        try {
            session = new JSch().getSession(param.getUser(), param.getHost(), param.getPort());
            session.setPassword(param.getPassword());
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect(param.getConnectTimeoutMs());
            Channel channel = session.openChannel("sftp");
            channel.connect(param.getDataTimeoutMs());
            sftp = (ChannelSftp) channel;
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(resolved);
            List<FileEntryVO> result = new ArrayList<>();
            for (ChannelSftp.LsEntry entry : entries) {
                if (".".equals(entry.getFilename()) || "..".equals(entry.getFilename())) {
                    continue;
                }
                String type = entry.getAttrs().isDir() ? "DIRECTORY" : entry.getAttrs().isLink() ? "LINK" : "FILE";
                Long size = entry.getAttrs().isDir() ? null : entry.getAttrs().getSize();
                result.add(new FileEntryVO(entry.getFilename(), RemotePathUtils.child(resolved, entry.getFilename()),
                        type, size, entry.getAttrs().getMTime() * 1000L));
            }
            return result;
        } catch (JSchException | SftpException ex) {
            throw new IllegalStateException("SFTP operation failed: " + ex.getMessage(), ex);
        } finally {
            if (sftp != null) { sftp.disconnect(); }
            if (session != null) { session.disconnect(); }
        }
    }

    private SftpConnectionParam requireParam(ConnectionParam param) {
        if (!(param instanceof SftpConnectionParam)) {
            throw new IllegalArgumentException("Invalid SFTP connection param type");
        }
        return (SftpConnectionParam) param;
    }
}
