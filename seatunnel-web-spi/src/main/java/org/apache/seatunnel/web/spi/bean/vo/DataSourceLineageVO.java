package org.apache.seatunnel.web.spi.bean.vo;

import java.util.List;

/** Read-only topology whose node IDs are local data source IDs. */
public record DataSourceLineageVO(List<Node> nodes, List<Link> edges, String readAt) {

    public record Node(String id, String name, String dbType, boolean tableCatalogAvailable) {}

    public record Link(String sourceId, String targetId, Integer assetCount) {}

    public record TableLineage(List<Asset> upstream, List<Asset> downstream) {}

    public record Asset(String id, String name, String fullyQualifiedName, String dataSourceName) {}
}
