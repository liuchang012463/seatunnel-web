package org.apache.seatunnel.plugin.datasource.api.jdbc;

import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.List;

/**
 * JDBC catalog that exposes a database-first namespace.
 *
 * <p>Some engines expose a schema below a database while others, such as
 * Doris, use databases as the only namespace.  Keeping this capability
 * optional preserves the existing flat catalog contract for other sources.</p>
 */
public interface HierarchicalJdbcCatalog extends JdbcCatalog {

    /** Lists every database visible to the configured account. */
    List<OptionVO> listDatabaseOptions();

    /** Lists tables in one database. */
    List<OptionVO> listTableOptions(String databaseName);
}
