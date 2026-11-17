package org.apache.seatunnel.web.spi.bean.vo;

/** Safe JDBC metadata identity: a Doris database is a source database or schema. */
public record LakeLogicalNamespaceVO(String database, String schema, String namespace) {
}
