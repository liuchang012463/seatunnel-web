package org.apache.seatunnel.plugin.datasource.sftp.connection;

import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.sftp.param.SftpConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.JSchException;

import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

@Slf4j
public class SftpConnectionProvider implements JdbcConnectionProvider {

    private static final int CONNECT_TIMEOUT = 10000;

    @Override
    public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
        SftpConnectionParam sftpParam = (SftpConnectionParam) connectionParam;

        JSch jsch = new JSch();
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            session = createSession(jsch, sftpParam);
            session.connect(CONNECT_TIMEOUT);

            sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect(CONNECT_TIMEOUT);

            return true;
        } catch (JSchException e) {
            log.error("SFTP connection test failed: host={}, port={}, user={}",
                    sftpParam.getHost(), sftpParam.getPort(), sftpParam.getUser(), e);
            return false;
        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                sftpChannel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @Override
    public java.sql.Connection getConnection(ConnectionParam connectionParam) {
        throw new UnsupportedOperationException("SFTP does not support JDBC connection");
    }

    private Session createSession(JSch jsch, SftpConnectionParam sftpParam) throws JSchException {
        Session session = jsch.getSession(
                sftpParam.getUser(),
                sftpParam.getHost(),
                Integer.parseInt(sftpParam.getPort())
        );

        if (sftpParam.getPassword() != null && !sftpParam.getPassword().isEmpty()) {
            session.setPassword(sftpParam.getPassword());
        }

        Properties config = new Properties();
        if (Boolean.TRUE.equals(sftpParam.getStrictHostKeyChecking())) {
            config.put("StrictHostKeyChecking", "yes");
            if (sftpParam.getKnownHostsPath() != null && !sftpParam.getKnownHostsPath().isEmpty()) {
                jsch.setKnownHosts(sftpParam.getKnownHostsPath());
            }
        } else {
            config.put("StrictHostKeyChecking", "no");
        }
        session.setConfig(config);

        return session;
    }
}
