package org.apache.seatunnel.plugin.datasource.http.option;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.web.common.config.OptionRule;

import static org.apache.seatunnel.plugin.datasource.http.option.HttpOptions.*;

@AutoService(SourceOptionRule.class)
public class HttpSourceOptionRule implements SourceOptionRule {

    @Override
    public OptionRule sourceOptionRule() {
        return OptionRule.builder()
                .required(URL)
                .optional(METHOD, FORMAT, HEADERS, PARAMS, BODY, SCHEMA, CONTENT_FIELD,
                        JSON_FIELD, PAGEING, POLL_INTERVAL, RETRY, RETRY_MULTIPLIER,
                        RETRY_MAX, CONNECT_TIMEOUT, SOCKET_TIMEOUT, ENABLE_MULTI_LINES,
                        KEEP_PARAMS_AS_FORM, KEEP_PAGE_PARAM, JSON_MISSED_RETURN_NULL)
                .build();
    }

    @Override
    public String pluginName() {
        return "HTTP";
    }
}
