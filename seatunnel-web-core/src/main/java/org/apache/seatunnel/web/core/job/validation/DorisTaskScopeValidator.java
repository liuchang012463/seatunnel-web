package org.apache.seatunnel.web.core.job.validation;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.HierarchicalJdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Server-side visibility validation for Doris task namespaces and tables. */
@Component
public class DorisTaskScopeValidator {

    @Resource
    private DataSourceDao dataSourceDao;

    public void validate(
            String dataSourceId,
            String requestedDatabase,
            Collection<String> tables,
            boolean requireExistingTables) {
        if (StringUtils.isBlank(dataSourceId)) {
            throw new IllegalArgumentException("Doris dataSourceId can not be blank");
        }

        Long id;
        try {
            id = Long.valueOf(dataSourceId.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Doris dataSourceId is invalid", exception);
        }

        if (dataSourceDao == null) {
            throw new IllegalStateException("Doris data source validator is not configured");
        }

        DataSource dataSource = dataSourceDao.queryById(id);
        if (dataSource == null || dataSource.getDbType() != DbType.DORIS) {
            throw new IllegalArgumentException("Doris data source does not exist");
        }

        DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(DbType.DORIS);
        BaseConnectionParam param = DataSourceUtils.buildJdbcConnectionParams(
                DbType.DORIS, dataSource.getConnectionParams());
        JdbcCatalog catalog = processor.getMetadataService(param);
        if (!(catalog instanceof HierarchicalJdbcCatalog hierarchicalCatalog)) {
            throw new IllegalArgumentException("Doris catalog does not support database scope");
        }

        String database = StringUtils.defaultIfBlank(requestedDatabase, param.getDatabase());
        if (StringUtils.isBlank(database)) {
            throw new IllegalArgumentException(
                    "Doris task must specify a database; legacy datasource default is empty");
        }

        String visibleDatabase = findValue(
                hierarchicalCatalog.listDatabaseOptions(), database);
        if (visibleDatabase == null) {
            throw new IllegalArgumentException("Doris database is not visible: " + database);
        }

        if (!requireExistingTables || tables == null || tables.isEmpty()) {
            return;
        }

        Set<String> visibleTables = new HashSet<>();
        for (OptionVO option : hierarchicalCatalog.listTableOptions(visibleDatabase)) {
            String value = optionValue(option);
            if (value != null) {
                visibleTables.add(value.toLowerCase(Locale.ROOT));
            }
        }

        for (String rawTable : tables) {
            String table = normalizeTable(rawTable, visibleDatabase);
            if (!visibleTables.contains(table.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Doris table is not visible: " + visibleDatabase + "." + table);
            }
        }
    }

    private String normalizeTable(String rawTable, String database) {
        if (StringUtils.isBlank(rawTable)) {
            throw new IllegalArgumentException("Doris table can not be blank");
        }
        String[] parts = rawTable.trim().split("\\.", -1);
        if (parts.length == 1) {
            return parts[0].trim();
        }
        if (parts.length == 2 && parts[0].trim().equalsIgnoreCase(database)) {
            return parts[1].trim();
        }
        throw new IllegalArgumentException(
                "Doris task table must belong to selected database: " + rawTable);
    }

    private String findValue(List<OptionVO> options, String expected) {
        if (options == null) {
            return null;
        }
        for (OptionVO option : options) {
            String value = optionValue(option);
            if (value != null && value.equalsIgnoreCase(expected.trim())) {
                return value;
            }
        }
        return null;
    }

    private String optionValue(OptionVO option) {
        if (option == null || option.getValue() == null) {
            return null;
        }
        return StringUtils.trimToNull(String.valueOf(option.getValue()));
    }
}
