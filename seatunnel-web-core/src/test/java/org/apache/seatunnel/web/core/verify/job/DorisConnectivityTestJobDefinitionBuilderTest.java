package org.apache.seatunnel.web.core.verify.job;

import org.apache.seatunnel.plugin.datasource.api.jdbc.HierarchicalJdbcCatalog;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DorisConnectivityTestJobDefinitionBuilderTest {

    @Test
    void usesConfiguredDatabaseWhenPresent() {
        HierarchicalJdbcCatalog catalog = mock(HierarchicalJdbcCatalog.class);
        when(catalog.listTables()).thenReturn(List.of("orders", "events"));

        var probe = DorisConnectivityTestJobDefinitionBuilder.resolveProbeTarget(catalog, "ods");

        assertEquals("ods", probe.database());
        assertEquals("orders", probe.table());
        verify(catalog).listTables();
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void picksFirstBusinessTableWhenDatabaseIsUnbound() {
        HierarchicalJdbcCatalog catalog = mock(HierarchicalJdbcCatalog.class);
        when(catalog.listDatabaseOptions()).thenReturn(List.of(
                option("information_schema"),
                option("mysql"),
                option("_internal_schema"),
                option("ods"),
                option("empty_db")));
        when(catalog.listTableOptions("ods")).thenReturn(List.of(option("test"), option("test_sync")));

        var probe = DorisConnectivityTestJobDefinitionBuilder.resolveProbeTarget(catalog, "");

        assertEquals("ods", probe.database());
        assertEquals("test", probe.table());
    }

    @Test
    void skipsEmptyBusinessDatabasesUntilATableIsFound() {
        HierarchicalJdbcCatalog catalog = mock(HierarchicalJdbcCatalog.class);
        when(catalog.listDatabaseOptions()).thenReturn(List.of(
                option("empty_db"),
                option("ods")));
        when(catalog.listTableOptions("empty_db")).thenReturn(List.of());
        when(catalog.listTableOptions("ods")).thenReturn(List.of(option("events")));

        var probe = DorisConnectivityTestJobDefinitionBuilder.resolveProbeTarget(catalog, "  ");

        assertEquals("ods", probe.database());
        assertEquals("events", probe.table());
    }

    @Test
    void failsClearlyWhenUnboundClusterHasNoBusinessTables() {
        HierarchicalJdbcCatalog catalog = mock(HierarchicalJdbcCatalog.class);
        when(catalog.listDatabaseOptions()).thenReturn(List.of(
                option("information_schema"),
                option("mysql")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DorisConnectivityTestJobDefinitionBuilder.resolveProbeTarget(catalog, null));

        assertEquals("Doris 集群中没有找到可用于连通性测试的业务表（连接未绑定默认库）",
                error.getMessage());
    }

    private static OptionVO option(String value) {
        OptionVO option = new OptionVO();
        option.setValue(value);
        option.setLabel(value);
        return option;
    }
}
