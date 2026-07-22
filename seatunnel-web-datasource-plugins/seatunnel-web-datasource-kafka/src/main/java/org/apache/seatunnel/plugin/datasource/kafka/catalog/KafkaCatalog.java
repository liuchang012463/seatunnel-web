package org.apache.seatunnel.plugin.datasource.kafka.catalog;

import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.kafka.client.KafkaAdminClientFacade;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.List;
import java.util.stream.Collectors;

public class KafkaCatalog implements DataSourceCatalog {

    private final KafkaConnectionParam param;
    private final KafkaAdminClientFacade adminClient;

    public KafkaCatalog(KafkaConnectionParam param, KafkaAdminClientFacade adminClient) {
        this.param = param;
        this.adminClient = adminClient;
    }

    @Override
    public List<OptionVO> listOptions() {
        return adminClient.listTopics(param).stream().map(this::toOption).collect(Collectors.toList());
    }

    private OptionVO toOption(String topic) {
        OptionVO option = new OptionVO();
        option.setLabel(topic);
        option.setValue(topic);
        option.setDescription("Kafka topic");
        return option;
    }
}
