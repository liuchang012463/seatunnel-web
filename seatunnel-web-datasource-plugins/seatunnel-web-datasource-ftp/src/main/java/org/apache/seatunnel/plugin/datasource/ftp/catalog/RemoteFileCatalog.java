package org.apache.seatunnel.plugin.datasource.ftp.catalog;

import org.apache.seatunnel.plugin.datasource.api.datasource.FileDataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.ftp.client.RemoteFileClient;
import org.apache.seatunnel.plugin.datasource.ftp.param.RemoteFileConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class RemoteFileCatalog implements FileDataSourceCatalog {
    private final RemoteFileConnectionParam param;
    private final RemoteFileClient client;

    public RemoteFileCatalog(RemoteFileConnectionParam param, RemoteFileClient client) {
        this.param = param;
        this.client = client;
    }

    @Override
    public List<FileEntryVO> listEntries(String path) {
        return client.listEntries(param, path).stream()
                .sorted(Comparator.comparing((FileEntryVO item) -> !"DIRECTORY".equals(item.getType()))
                        .thenComparing(FileEntryVO::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    @Override
    public List<OptionVO> listOptions() {
        return listEntries(param.getBasePath()).stream().map(entry -> {
            OptionVO option = new OptionVO();
            option.setLabel(entry.getName());
            option.setValue(entry.getPath());
            option.setDescription(entry.getType());
            return option;
        }).collect(Collectors.toList());
    }
}
