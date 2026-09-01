package org.apache.seatunnel.web.api.lake.query;

/** Server-resolved metadata used as the only column allowlist input. */
public record LakeQueryColumnMetadata(String name, boolean selectable, boolean sensitive) {
}
