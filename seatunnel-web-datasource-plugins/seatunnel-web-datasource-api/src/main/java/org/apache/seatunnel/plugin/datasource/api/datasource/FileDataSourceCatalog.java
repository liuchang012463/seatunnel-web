package org.apache.seatunnel.plugin.datasource.api.datasource;

import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;

import java.io.InputStream;
import java.util.List;

public interface FileDataSourceCatalog extends DataSourceCatalog {
    List<FileEntryVO> listEntries(String path);

    /**
     * Stores one uploaded file under {@code path} (a directory relative to the
     * datasource base directory) and returns the stored absolute path.
     *
     * <p>Only datasources backed by storage writable from the web server support
     * uploads; others throw {@link UnsupportedOperationException}.</p>
     */
    default String uploadEntry(String path, String fileName, InputStream inputStream, long size) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support file uploads");
    }
}
