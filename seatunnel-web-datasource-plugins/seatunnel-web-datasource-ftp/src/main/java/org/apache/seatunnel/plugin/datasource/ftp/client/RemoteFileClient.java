package org.apache.seatunnel.plugin.datasource.ftp.client;

import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.ftp.param.RemoteFileConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;

import java.util.List;

public interface RemoteFileClient extends ConnectivityVerifier {
    List<FileEntryVO> listEntries(RemoteFileConnectionParam param, String path);
}
