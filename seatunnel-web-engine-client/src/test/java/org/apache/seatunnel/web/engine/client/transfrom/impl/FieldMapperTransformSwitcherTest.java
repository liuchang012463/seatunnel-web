package org.apache.seatunnel.web.engine.client.transfrom.impl;

import com.typesafe.config.Config;
import org.apache.seatunnel.web.engine.client.transfrom.domain.FieldMapperConfig;
import org.apache.seatunnel.web.engine.client.transfrom.domain.FieldMapperMapping;
import org.apache.seatunnel.web.engine.client.transfrom.domain.FieldMapperTransformOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void emitsSqlWhenOneSourceFeedsMultipleTargetFields() {
        Config config = switcher.transform(options(
                mapping("user_id", "account_id"),
                mapping("user_id", "member_id")));

        assertTrue(config.hasPath("Sql"));
        assertFalse(config.hasPath("FieldMapper"));
        assertEquals(
                "SELECT `user_id` AS `account_id`, `user_id` AS `member_id` FROM `source-1`",
                config.getString("Sql.query"));
        assertEquals("source-1", config.getString("Sql.plugin_input"));
        assertEquals("sink-1", config.getString("Sql.plugin_output"));
    }

    @Test
    void keepsTheFallbackWhenTheSourcesShareOneDeclaredType() {
        FieldMapperMapping first = mapping("channel_type", "province");
        first.setTargetType("STRING");
        FieldMapperMapping second = mapping("description", "province");
        second.setTargetType("STRING");

        Config config = switcher.transform(options(first, second));

        assertEquals(
                "SELECT COALESCE(`channel_type`, `description`) AS `province` FROM `source-1`",
                config.getString("Sql.query"));
    }

    @Test
    void rejectsFallbackAcrossDifferentlyTypedSources() {
        FieldMapperMapping first = mapping("channel_type", "province");
        first.setTargetType("INT");
        FieldMapperMapping second = mapping("description", "province");
        second.setTargetType("STRING");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> switcher.transform(options(first, second)));
        assertEquals(
                "Multiple source fields of different types feed the target field 'province':"
                        + " [INT, STRING]. Align their types or map them through an expression.",
                ex.getMessage());
    }

    @Test
    void allowsDifferentlyTypedSourcesWhenAnExpressionIsConfigured() {
        FieldMapperMapping first = mapping("channel_type", "province");
        first.setTargetType("INT");
        first.setExpression("CAST(`channel_type` AS STRING)");
        FieldMapperMapping second = mapping("description", "province");
        second.setTargetType("STRING");

        Config config = switcher.transform(options(first, second));

        assertEquals(
                "SELECT CAST(`channel_type` AS STRING) AS `province` FROM `source-1`",
                config.getString("Sql.query"));
    }

    @Test
    void throwsExceptionWhenAllMappingsAreDisabled() {
        FieldMapperMapping m1 = mapping("user_id", "account_id");
        m1.setEnabled(false);
        FieldMapperMapping m2 = mapping("name", "user_name");
        m2.setEnabled(false);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> switcher.transform(options(m1, m2)));
        assertEquals("at least one enabled mapping is required", ex.getMessage());
    }

    @Test
    void rejectsMultiStatementExpressions() {
        FieldMapperMapping combined = mapping("channel_type", "province");
        combined.setExpression("`channel_type`; DROP TABLE users");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> switcher.transform(options(combined)));
        assertTrue(ex.getMessage().contains("single column expression"));
    }

    @Test
    void rejectsCommentMarkersOutsideQuotedLiterals() {
        FieldMapperMapping combined = mapping("channel_type", "province");
        combined.setExpression("`channel_type` -- rest of query");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> switcher.transform(options(combined)));
        assertTrue(ex.getMessage().contains("SQL comments"));
    }

    @Test
    void allowsCommentMarkersInsideQuotedLiterals() {
        FieldMapperMapping combined = mapping("channel_type", "province");
        combined.setExpression("REPLACE(`channel_type`, '--', '')");

        Config config = switcher.transform(options(combined));

        assertEquals(
                "SELECT REPLACE(`channel_type`, '--', '') AS `province` FROM `source-1`",
                config.getString("Sql.query"));
    }

    @Test
    void rejectsUnterminatedQuotedExpressions() {
        FieldMapperMapping combined = mapping("channel_type", "province");
        combined.setExpression("CONCAT(`channel_type`, 'unterminated)");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> switcher.transform(options(combined)));
        assertTrue(ex.getMessage().contains("unterminated quoted literal"));
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
