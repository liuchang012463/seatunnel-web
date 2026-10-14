package org.apache.seatunnel.web.engine.client.transfrom.impl;

import com.typesafe.config.Config;
import org.apache.seatunnel.web.engine.client.transfrom.domain.FieldMapperConfig;
import org.apache.seatunnel.web.engine.client.transfrom.domain.FieldMapperMapping;
import org.apache.seatunnel.web.engine.client.transfrom.domain.FieldMapperTransformOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldMapperTransformSwitcherTest {

    private final FieldMapperTransformSwitcher switcher = new FieldMapperTransformSwitcher();

    @Test
    void keepsTheFieldMapperWhenEveryTargetFieldHasOneSource() {
        Config config = switcher.transform(options(
                mapping("channel_type", "province"),
                mapping("name", "user_name")));

        assertTrue(config.hasPath("FieldMapper.field_mapper"));
        assertFalse(config.hasPath("Sql"));
        assertEquals("province", config.getString("FieldMapper.field_mapper.channel_type"));
        assertEquals("source-1", config.getString("FieldMapper.plugin_input"));
        assertEquals("sink-1", config.getString("FieldMapper.plugin_output"));
    }

    @Test
    void emitsSqlWithFallbackWhenSeveralSourcesFeedOneTargetField() {
        Config config = switcher.transform(options(
                mapping("channel_type", "province"),
                mapping("description", "province"),
                mapping("name", "user_name")));

        assertTrue(config.hasPath("Sql"));
        assertFalse(config.hasPath("FieldMapper"));
        assertEquals(
                "SELECT COALESCE(`channel_type`, `description`) AS `province`, `name` AS `user_name`"
                        + " FROM `source-1`",
                config.getString("Sql.query"));
        assertEquals("source-1", config.getString("Sql.plugin_input"));
        assertEquals("sink-1", config.getString("Sql.plugin_output"));
    }

    @Test
    void emitsSqlWithTheConfiguredExpression() {
        FieldMapperMapping combined = mapping("channel_type", "province");
        combined.setExpression("CONCAT(`channel_type`, '-', `description`)");

        Config config = switcher.transform(options(combined));

        assertTrue(config.hasPath("Sql"));
        assertEquals(
                "SELECT CONCAT(`channel_type`, '-', `description`) AS `province` FROM `source-1`",
                config.getString("Sql.query"));
    }

    private static FieldMapperTransformOptions options(FieldMapperMapping... mappings) {
        FieldMapperConfig mapperConfig = new FieldMapperConfig();
        mapperConfig.setPluginInput("source-1");
        mapperConfig.setPluginOutput("sink-1");
        mapperConfig.setMappings(List.of(mappings));

        FieldMapperTransformOptions options = new FieldMapperTransformOptions();
        options.setComponentType("FIELDMAPPER");
        options.setConfig(mapperConfig);
        return options;
    }

    private static FieldMapperMapping mapping(String sourceField, String targetField) {
        FieldMapperMapping mapping = new FieldMapperMapping();
        mapping.setSourceField(sourceField);
        mapping.setTargetField(targetField);
        mapping.setEnabled(true);
        return mapping;
    }
}
