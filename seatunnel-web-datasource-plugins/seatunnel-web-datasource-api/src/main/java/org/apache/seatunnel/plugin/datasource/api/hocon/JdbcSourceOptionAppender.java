package org.apache.seatunnel.plugin.datasource.api.hocon;

import com.typesafe.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConfigReaders;

import java.util.Map;

public class JdbcSourceOptionAppender {

    public void append(Config config, Map<String, Object> map) {
        appendAdvancedOptions(config, map);
    }

    private void appendAdvancedOptions(Config config, Map<String, Object> map) {
        Integer fetchSize = JdbcConfigReaders.getInteger(config, "fetchSize", null);
        if (fetchSize != null && fetchSize > 0) {
            map.put("fetch_size", fetchSize);
        }

        Integer splitSize = JdbcConfigReaders.getInteger(config, "splitSize", null);
        if (splitSize != null && splitSize > 0) {
            map.put("split.size", splitSize);
        }

        appendPartitionOptions(config, map);
    }

    /**
     * split.size only takes effect on table_path sources; custom SQL sources
     * need an explicit partition column, so forward the first-class keys too
     * (they are also reachable through extraParams).
     */
    private void appendPartitionOptions(Config config, Map<String, Object> map) {
        String partitionColumn = JdbcConfigReaders.getString(config, "partitionColumn", "");
        if (StringUtils.isBlank(partitionColumn)) {
            partitionColumn = JdbcConfigReaders.getString(config, "partition_column", "");
        }
        if (StringUtils.isNotBlank(partitionColumn)) {
            map.put("partition_column", partitionColumn);
        }

        for (String[] bound : new String[][] {
                {"partitionUpperBound", "partition_upper_bound"},
                {"partitionLowerBound", "partition_lower_bound"},
                {"partitionNum", "partition_num"}}) {
            String value = JdbcConfigReaders.getString(config, bound[0], "");
            if (StringUtils.isBlank(value)) {
                value = JdbcConfigReaders.getString(config, bound[1], "");
            }
            if (StringUtils.isNotBlank(value)) {
                map.put(bound[1], value);
            }
        }
    }
}