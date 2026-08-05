package org.apache.seatunnel.plugin.datasource.elasticsearch.option;

import org.apache.seatunnel.web.common.config.Option;
import org.apache.seatunnel.web.common.config.Options;

import java.util.List;
import java.util.Map;

public final class ElasticsearchOptions {

    public static final Option<List<String>> HOSTS =
            Options.key("hosts").listType().noDefaultValue();
    public static final Option<String> AUTH_TYPE =
            Options.key("auth_type").stringType().defaultValue("basic");
    public static final Option<String> USERNAME =
            Options.key("username").stringType().noDefaultValue();
    public static final Option<String> PASSWORD =
            Options.key("password").stringType().noDefaultValue();
    public static final Option<String> API_KEY_ID =
            Options.key("auth.api_key_id").stringType().noDefaultValue();
    public static final Option<String> API_KEY =
            Options.key("auth.api_key").stringType().noDefaultValue();
    public static final Option<String> API_KEY_ENCODED =
            Options.key("auth.api_key_encoded").stringType().noDefaultValue();
    public static final Option<String> INDEX =
            Options.key("index").stringType().noDefaultValue();
    public static final Option<List<Map>> INDEX_LIST =
            Options.key("index_list").listType(Map.class).noDefaultValue();
    public static final Option<List<String>> SOURCE =
            Options.key("source").listType().noDefaultValue();
    public static final Option<Map<String, Object>> QUERY =
            Options.key("query").mapObjectType().noDefaultValue();
    public static final Option<String> SEARCH_TYPE =
            Options.key("search_type").stringType().defaultValue("DSL");
    public static final Option<String> SEARCH_API_TYPE =
            Options.key("search_api_type").stringType().defaultValue("SCROLL");
    public static final Option<String> SQL_QUERY =
            Options.key("sql_query").stringType().noDefaultValue();
    public static final Option<String> SCROLL_TIME =
            Options.key("scroll_time").stringType().defaultValue("1m");
    public static final Option<Integer> SCROLL_SIZE =
            Options.key("scroll_size").intType().defaultValue(100);
    public static final Option<Boolean> TLS_VERIFY_CERTIFICATE =
            Options.key("tls_verify_certificate").booleanType().defaultValue(true);
    public static final Option<Boolean> TLS_VERIFY_HOSTNAME =
            Options.key("tls_verify_hostname").booleanType().defaultValue(true);
    public static final Option<Map<String, Object>> ARRAY_COLUMN =
            Options.key("array_column").mapObjectType().noDefaultValue();
    public static final Option<Long> PIT_KEEP_ALIVE =
            Options.key("pit_keep_alive").longType().defaultValue(60000L);
    public static final Option<Integer> PIT_BATCH_SIZE =
            Options.key("pit_batch_size").intType().defaultValue(100);
    public static final Option<List<Map>> RUNTIME_FIELDS =
            Options.key("runtime_fields").listType(Map.class).noDefaultValue();
    public static final Option<String> INDEX_TYPE =
            Options.key("index_type").stringType().noDefaultValue();
    public static final Option<List<String>> PRIMARY_KEYS =
            Options.key("primary_keys").listType().noDefaultValue();
    public static final Option<String> KEY_DELIMITER =
            Options.key("key_delimiter").stringType().defaultValue("_");
    public static final Option<Integer> MAX_RETRY_COUNT =
            Options.key("max_retry_count").intType().defaultValue(3);
    public static final Option<Integer> MAX_BATCH_SIZE =
            Options.key("max_batch_size").intType().defaultValue(10);
    public static final Option<List<String>> VECTORIZATION_FIELDS =
            Options.key("vectorization_fields").listType().noDefaultValue();
    public static final Option<Integer> VECTOR_DIMENSIONS =
            Options.key("vector_dimensions").intType().noDefaultValue();
    public static final Option<String> SCHEMA_SAVE_MODE =
            Options.key("schema_save_mode").stringType().noDefaultValue();
    public static final Option<String> DATA_SAVE_MODE =
            Options.key("data_save_mode").stringType().noDefaultValue();

    private ElasticsearchOptions() {
    }
}
