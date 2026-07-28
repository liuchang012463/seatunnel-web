package org.apache.seatunnel.plugin.datasource.api.datasource;

import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;

import java.util.List;

public interface FileDataSourceCatalog extends DataSourceCatalog {
    List<FileEntryVO> listEntries(String path);
}
