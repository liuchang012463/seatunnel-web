package org.apache.seatunnel.plugin.datasource.s3.client;

import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.s3.param.ObjectStorageConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;

import java.util.List;

public interface ObjectStorageClient extends ConnectivityVerifier {
    List<FileEntryVO> listEntries(ObjectStorageConnectionParam param, String path);
}
