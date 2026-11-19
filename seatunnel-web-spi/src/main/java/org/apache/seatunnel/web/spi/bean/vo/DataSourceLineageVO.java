package org.apache.seatunnel.web.spi.bean.vo;

import java.util.List;

/** Direct asset edges and the registered data sources that own their endpoints. */
public record DataSourceLineageVO(List<Source> sources, List<Asset> assets, List<Link> edges, String readAt) {

    public record Source(String id, String name, String dbType) {}

    public record Asset(String id, String dataSourceId, String type, String database, String schema,
            String name, String fullyQualifiedName) {}

    public record Link(String sourceId, String targetId, Task task) {}

    public enum TaskKind { BATCH, STREAMING }

    public record Task(String id, TaskKind kind, String name, boolean deleted) {}

    public record FieldLink(String sourceId, String targetId, String fromColumn, String toColumn) {}
}
