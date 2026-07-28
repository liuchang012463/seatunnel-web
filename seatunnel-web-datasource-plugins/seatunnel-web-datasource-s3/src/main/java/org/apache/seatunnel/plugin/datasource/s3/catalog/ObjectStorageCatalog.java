package org.apache.seatunnel.plugin.datasource.s3.catalog;

import org.apache.seatunnel.plugin.datasource.api.datasource.FileDataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.s3.client.ObjectStorageClient;
import org.apache.seatunnel.plugin.datasource.s3.param.ObjectStorageConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class ObjectStorageCatalog implements FileDataSourceCatalog {
    private final ObjectStorageConnectionParam param;
    private final ObjectStorageClient client;

    public ObjectStorageCatalog(ObjectStorageConnectionParam param, ObjectStorageClient client) {
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
