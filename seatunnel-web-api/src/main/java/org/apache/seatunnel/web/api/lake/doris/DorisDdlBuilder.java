package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.DorisSqlLiteral;
import org.apache.seatunnel.web.api.lake.contract.DorisTypeBase;
import org.apache.seatunnel.web.api.lake.contract.TargetColumn;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractValidator;
import org.apache.seatunnel.web.api.lake.contract.TargetDistribution;
import org.apache.seatunnel.web.api.lake.contract.TargetPartition;
import org.apache.seatunnel.web.api.lake.contract.TargetType;

import java.util.List;
import java.util.Map;

/** Builds deterministic, identifier-safe CREATE TABLE statements for Doris. */
public final class DorisDdlBuilder {

    public String build(String databaseName, String tableName, TargetContract contract) {
        return build(databaseName, tableName, contract, Map.of());
    }

    public String build(String databaseName, String tableName, TargetContract contract,
                        Map<String, String> tableProperties) {
        TargetContract normalised = TargetContractValidator.validateAndNormalize(contract);
        String database = DorisIdentifier.validate(databaseName);
        String table = DorisIdentifier.validate(tableName);
        Map<String, String> properties = DorisTablePropertyWhitelist.validateAndCopy(tableProperties);

        StringBuilder ddl = new StringBuilder(512);
        ddl.append("CREATE TABLE ").append(DorisIdentifier.quote(database)).append('.')
                .append(DorisIdentifier.quote(table)).append(" (\n");
        List<TargetColumn> columns = normalised.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            TargetColumn column = columns.get(i);
            if (i > 0) {
                ddl.append(",\n");
            }
            ddl.append("  ").append(DorisIdentifier.quote(column.getTargetName())).append(' ')
                    .append(renderType(column.getTargetType())).append(' ')
                    .append(Boolean.TRUE.equals(column.getNullable()) ? "NULL" : "NOT NULL");
        }
        ddl.append("\n) ENGINE=OLAP\n");
        ddl.append(normalised.getTableModel().getCode()).append(" KEY(");
        appendIdentifiers(ddl, normalised.getKeyColumns());
        ddl.append(")\n");

        TargetPartition partition = normalised.getPartition();
        if (Boolean.TRUE.equals(partition.getEnabled())) {
            ddl.append("AUTO PARTITION BY RANGE (date_trunc(")
                    .append(DorisIdentifier.quote(partition.getColumn())).append(", '")
                    .append(partition.getGranularity().toLowerCase(java.util.Locale.ROOT))
                    .append("'))\n()\n");
        }

        TargetDistribution distribution = normalised.getDistribution();
        ddl.append("DISTRIBUTED BY ");
        if (TargetDistribution.RANDOM.equals(distribution.getType())) {
            ddl.append("RANDOM");
        } else {
            ddl.append("HASH(");
            appendIdentifiers(ddl, distribution.getColumns());
            ddl.append(')');
        }
        ddl.append(" BUCKETS ").append(distribution.getBuckets()).append('\n');

        if (!properties.isEmpty()) {
            ddl.append("PROPERTIES (\n");
            int index = 0;
            for (Map.Entry<String, String> property : properties.entrySet()) {
                if (index++ > 0) {
                    ddl.append(",\n");
                }
                ddl.append("  ").append(quoteProperty(property.getKey())).append(" = ")
                        .append(quoteProperty(property.getValue()));
            }
            ddl.append("\n)\n");
        }
        ddl.append(';');
        return ddl.toString();
    }

    public String buildCreateTable(String databaseName, String tableName, TargetContract contract) {
        return build(databaseName, tableName, contract);
    }

    public String buildCreateTable(String databaseName, String tableName, TargetContract contract,
                                   Map<String, String> tableProperties) {
        return build(databaseName, tableName, contract, tableProperties);
    }

    private static void appendIdentifiers(StringBuilder result, List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append(DorisIdentifier.quote(names.get(i)));
        }
    }

    private static String renderType(TargetType type) {
        TargetType canonical = type.canonicalCopy();
        DorisTypeBase base = canonical.getBase();
        return switch (base) {
            case VARCHAR, CHAR -> base.name() + "(" + canonical.getLength() + ")";
            case DECIMAL -> "DECIMAL(" + canonical.getPrecision() + "," + canonical.getScale() + ")";
            case DATETIME -> canonical.getScale() == null || canonical.getScale() == 0
                    ? "DATETIME" : "DATETIME(" + canonical.getScale() + ")";
            case BOOLEAN, TINYINT, SMALLINT, INT, BIGINT, LARGEINT, FLOAT, DOUBLE,
                    DATE, STRING, JSON -> base.name();
            case TEXT -> "STRING";
            case ARRAY, MAP, STRUCT -> throw new IllegalArgumentException(
                    "Complex target types require nested type definitions");
        };
    }

    private static String quoteProperty(String value) {
        return DorisSqlLiteral.quoteDorisProperty(value);
    }
}
