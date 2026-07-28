package org.apache.seatunnel.plugin.datasource.http.option;

import org.apache.seatunnel.web.common.config.Option;
import org.apache.seatunnel.web.common.config.Options;

import java.util.Map;

public final class HttpOptions {

    public static final Option<String> URL =
            Options.key("url").stringType().noDefaultValue();
    public static final Option<String> METHOD =
            Options.key("method").stringType().defaultValue("GET");
    public static final Option<String> FORMAT =
            Options.key("format").stringType().defaultValue("text");
    public static final Option<Map<String, String>> HEADERS =
            Options.key("headers").mapType().noDefaultValue();
    public static final Option<Map<String, String>> PARAMS =
            Options.key("params").mapType().noDefaultValue();
    public static final Option<String> BODY =
            Options.key("body").stringType().noDefaultValue();
    public static final Option<String> SCHEMA =
            Options.key("schema").stringType().noDefaultValue();
    public static final Option<String> CONTENT_FIELD =
            Options.key("content_field").stringType().noDefaultValue();
    public static final Option<String> JSON_FIELD =
            Options.key("json_field").stringType().noDefaultValue();
    public static final Option<String> PAGEING =
            Options.key("pageing").stringType().noDefaultValue();
    public static final Option<Long> POLL_INTERVAL =
            Options.key("poll_interval_millis").longType().noDefaultValue();
    public static final Option<Integer> RETRY =
            Options.key("retry").intType().noDefaultValue();
    public static final Option<Integer> RETRY_MULTIPLIER =
            Options.key("retry_backoff_multiplier_ms").intType().defaultValue(100);
    public static final Option<Integer> RETRY_MAX =
            Options.key("retry_backoff_max_ms").intType().defaultValue(10000);
    public static final Option<Integer> CONNECT_TIMEOUT =
            Options.key("connect_timeout_ms").intType().defaultValue(12000);
    public static final Option<Integer> SOCKET_TIMEOUT =
            Options.key("socket_timeout_ms").intType().defaultValue(60000);
    public static final Option<Boolean> ENABLE_MULTI_LINES =
            Options.key("enable_multi_lines").booleanType().defaultValue(false);
    public static final Option<Boolean> KEEP_PARAMS_AS_FORM =
            Options.key("keep_params_as_form").booleanType().defaultValue(false);
    public static final Option<Boolean> KEEP_PAGE_PARAM =
            Options.key("keep_page_param_as_http_param").booleanType().defaultValue(false);
    public static final Option<Boolean> JSON_MISSED_RETURN_NULL =
            Options.key("json_filed_missed_return_null").booleanType().defaultValue(false);

    private HttpOptions() {
    }
}
