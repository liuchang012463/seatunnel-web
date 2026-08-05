package org.apache.seatunnel.plugin.datasource.elasticsearch.catalog;

import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.elasticsearch.client.ElasticsearchHttpClient;
import org.apache.seatunnel.plugin.datasource.elasticsearch.param.ElasticsearchConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.List;
import java.util.stream.Collectors;

public class ElasticsearchCatalog implements DataSourceCatalog {

    private final ElasticsearchConnectionParam param;
    private final ElasticsearchHttpClient client;

    public ElasticsearchCatalog(
            ElasticsearchConnectionParam param,
            ElasticsearchHttpClient client) {
        this.param = param;
        this.client = client;
    }

    @Override
    public List<OptionVO> listOptions() {
        return client.listIndices(param).stream()
                .map(this::toOption)
                .collect(Collectors.toList());
    }

    private OptionVO toOption(String index) {
        OptionVO option = new OptionVO();
        option.setLabel(index);
        option.setValue(index);
        option.setDescription("Elasticsearch index");
        return option;
    }
}
