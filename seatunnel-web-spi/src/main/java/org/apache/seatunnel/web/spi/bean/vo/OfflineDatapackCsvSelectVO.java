package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.Map;

/** Materialized, owner-scoped resource and source schema for one selected datapack CSV. */
@Data
public class OfflineDatapackCsvSelectVO {

    private FileResourceVO fileResource;

    private Map<String, String> schema;

    private String nullValue;
}
