package org.apache.seatunnel.plugin.datasource.elasticsearch.option;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.web.common.config.OptionRule;

import static org.apache.seatunnel.plugin.datasource.elasticsearch.option.ElasticsearchOptions.*;

@AutoService(SourceOptionRule.class)
public class ElasticsearchSourceOptionRule implements SourceOptionRule {

    @Override
    public OptionRule sourceOptionRule() {
        return OptionRule.builder()
                .required(HOSTS)
                .optional(AUTH_TYPE, USERNAME, PASSWORD, API_KEY_ID, API_KEY, API_KEY_ENCODED,
                        INDEX, INDEX_LIST, SOURCE, QUERY, SEARCH_TYPE, SEARCH_API_TYPE, SQL_QUERY,
                        SCROLL_TIME, SCROLL_SIZE, TLS_VERIFY_CERTIFICATE, TLS_VERIFY_HOSTNAME,
                        ARRAY_COLUMN, PIT_KEEP_ALIVE, PIT_BATCH_SIZE, RUNTIME_FIELDS)
                .build();
    }

    @Override
    public String pluginName() {
        return "ELASTICSEARCH";
    }
}
