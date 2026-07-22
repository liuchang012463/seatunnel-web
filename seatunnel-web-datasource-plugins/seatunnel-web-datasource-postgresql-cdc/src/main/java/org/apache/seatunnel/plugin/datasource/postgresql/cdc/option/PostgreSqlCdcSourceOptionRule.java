package org.apache.seatunnel.plugin.datasource.postgresql.cdc.option;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.plugin.datasource.api.option.CDCJdbcSourceOptions;
import org.apache.seatunnel.plugin.datasource.api.option.ConnectorCommonOptions;
import org.apache.seatunnel.plugin.datasource.api.option.JdbcCommonOptions;
import org.apache.seatunnel.plugin.datasource.api.option.SourceOptions;
import org.apache.seatunnel.web.common.config.Option;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.web.common.config.Options;

@AutoService(SourceOptionRule.class)
public class PostgreSqlCdcSourceOptionRule implements SourceOptionRule {

    private static final Option<String> SLOT_NAME = Options.key("slot.name").stringType().noDefaultValue();
    private static final Option<String> DECODING_PLUGIN = Options.key("decoding.plugin.name").stringType().noDefaultValue();
    private static final Option<String> STARTUP_MODE = Options.key("startup.mode").stringType().noDefaultValue();

    @Override
    public OptionRule sourceOptionRule() {
        return CDCJdbcSourceOptions.getBaseRule()
                .required(CDCJdbcSourceOptions.USERNAME, CDCJdbcSourceOptions.PASSWORD,
                        JdbcCommonOptions.URL, ConnectorCommonOptions.TABLE_NAMES,
                        SLOT_NAME)
                .optional(CDCJdbcSourceOptions.DATABASE_NAMES, CDCJdbcSourceOptions.SERVER_TIME_ZONE,
                        CDCJdbcSourceOptions.CONNECT_TIMEOUT_MS, CDCJdbcSourceOptions.CONNECT_MAX_RETRIES,
                        CDCJdbcSourceOptions.CONNECTION_POOL_SIZE,
                        CDCJdbcSourceOptions.CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_LOWER_BOUND,
                        CDCJdbcSourceOptions.CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_UPPER_BOUND,
                        CDCJdbcSourceOptions.SAMPLE_SHARDING_THRESHOLD,
                        CDCJdbcSourceOptions.INVERSE_SAMPLING_RATE,
                        CDCJdbcSourceOptions.TABLE_NAMES_CONFIG, SourceOptions.EXACTLY_ONCE,
                        DECODING_PLUGIN, STARTUP_MODE)
                .build();
    }

    @Override
    public String pluginName() {
        return "POSTGRESQL-CDC";
    }
}
