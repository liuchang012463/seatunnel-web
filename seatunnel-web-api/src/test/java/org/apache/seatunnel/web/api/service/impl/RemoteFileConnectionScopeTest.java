package org.apache.seatunnel.web.api.service.impl;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.seatunnel.plugin.datasource.ftp.client.FtpRemoteFileClient;
import org.apache.seatunnel.plugin.datasource.ftp.client.SftpRemoteFileClient;
import org.apache.seatunnel.plugin.datasource.ftp.param.FtpConnectionParam;
import org.apache.seatunnel.plugin.datasource.ftp.param.SftpConnectionParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteFileConnectionScopeTest {
    @ParameterizedTest
    @ValueSource(ints = {226, 550})
    void distinguishesAnEmptyFtpDirectoryFromAFailedListing(int listReplyCode) {
        FtpConnectionParam param = new FtpConnectionParam();
        param.setBasePath("/data");
        try (var clients = mockConstruction(FTPClient.class, (client, context) -> {
            when(client.getReplyCode()).thenReturn(220, listReplyCode);
            when(client.login(param.getUser(), param.getPassword())).thenReturn(true);
            when(client.setFileType(FTP.BINARY_FILE_TYPE)).thenReturn(true);
            when(client.changeWorkingDirectory("/data")).thenReturn(true);
            when(client.listFiles()).thenReturn(new FTPFile[0]);
        })) {
            FtpRemoteFileClient client = new FtpRemoteFileClient();
            if (listReplyCode == 226) {
                assertTrue(client.checkDataSourceConnectivity(param));
            } else {
                IllegalStateException error = assertThrows(IllegalStateException.class,
                        () -> client.checkDataSourceConnectivity(param));
                assertTrue(error.getMessage().contains("/data"));
                assertTrue(error.getMessage().contains("550"));
            }
        }
    }

    @Test
    void rejectsSftpRootThatCannotBeOpenedAsADirectory() throws Exception {
        SftpConnectionParam param = new SftpConnectionParam();
        param.setBasePath("/data.csv");
        Session session = mock(Session.class);
        ChannelSftp channel = mock(ChannelSftp.class);
        when(session.openChannel("sftp")).thenReturn(channel);
        doThrow(new SftpException(ChannelSftp.SSH_FX_FAILURE, "Not a directory"))
                .when(channel).cd("/data.csv");

        try (var clients = mockConstruction(JSch.class, (client, context) ->
                when(client.getSession(param.getUser(), param.getHost(), param.getPort())).thenReturn(session))) {
            assertThrows(IllegalStateException.class,
                    () -> new SftpRemoteFileClient().checkDataSourceConnectivity(param));
            verify(channel, never()).ls(".");
        }
    }
}
