package org.apache.seatunnel.web.core.time;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceValiditySqlRendererTest {
    @Test
    void resolvesTheSelectedVariableAndUsesTheSourceDialect() {
        var variables = mock(TimeVariableRenderService.class);
        var variable = new org.apache.seatunnel.web.dao.entity.TimeVariable();
        variable.setParamName("lastWeek");
        variable.setTimeFormat("yyyy-MM-dd HH:mm:ss");
        when(variables.getAllEnabledVariables()).thenReturn(java.util.List.of(variable));
        var rendered = new org.apache.seatunnel.web.spi.bean.vo.TimeVariableRenderVO();
        var item = new org.apache.seatunnel.web.spi.bean.vo.TimeVariableRenderVO.VariableItem();
        item.setName("lastWeek");
        item.setValue("2026-11-10 00:00:00");
        rendered.setVariables(java.util.List.of(item));
        when(variables.render(any())).thenReturn(rendered);
        var dialect = mock(org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder.class);
        when(dialect.renderTimeLiteral(item.getValue(), variable.getTimeFormat()))
                .thenReturn("TIMESTAMP '2026-11-10 00:00:00'");
        var node = ConfigFactory.parseString("pluginName=JDBC-ORACLE\ndataValidity { enabled=true, fieldName=created_at, timeVariable=lastWeek }");
        var result = new SourceValiditySqlRenderer(variables).apply(
                ConfigFactory.parseString("table_path=orders\nwhere_condition=\"id > 10\""), node, dialect);
        assertTrue(result.getString("query").contains("(id > 10) AND created_at >= TIMESTAMP"));
        assertFalse(result.hasPath("table_path"));
        assertFalse(result.hasPath("where_condition"));
        node = node.withValue("dataValidity.timeVariable", com.typesafe.config.ConfigValueFactory.fromAnyRef("missing"));
        var missingVariable = node;
        assertThrows(IllegalArgumentException.class, () -> new SourceValiditySqlRenderer(variables)
                .apply(ConfigFactory.parseString("table_path=orders"), missingVariable, dialect));
    }

    @Test
    void keepsOrGroupingAndAppliesTheTimeConditionBeforeLimit() {
        String sql = SourceValiditySqlRenderer.append(
                "select * from orders where state=1 or state=2 order by id limit 10;", null,
                "created_at >= '2026-11-10 00:00:00'");
        assertTrue(sql.contains("(state = 1 OR state = 2) AND created_at >="));
        assertTrue(sql.indexOf("created_at >=") < sql.indexOf("ORDER BY"));
        assertTrue(sql.endsWith("LIMIT 10"));
    }

    @Test
    void preservesTheExistingIncrementalPredicateAndRejectsMultipleStatements() {
        String sql = SourceValiditySqlRenderer.append("select * from orders", "where id > 10", "created_at >= '2026-11-10'");
        assertTrue(sql.contains("(id > 10) AND created_at >="));
        assertThrows(IllegalArgumentException.class,
                () -> SourceValiditySqlRenderer.append("select * from orders; delete from orders", null, "id > 1"));
    }

    @Test
    void existingTasksAreReturnedUnchangedWithoutReadingTimeVariables() {
        var variables = mock(TimeVariableRenderService.class);
        var config = ConfigFactory.parseString("table_path=orders");
        assertSame(config, new SourceValiditySqlRenderer(variables).apply(config, ConfigFactory.empty(), null));
        verifyNoInteractions(variables);
    }
}
