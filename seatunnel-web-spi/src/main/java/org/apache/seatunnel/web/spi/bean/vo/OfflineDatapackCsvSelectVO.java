package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** Materialized, owner-scoped resource and source schema for one selected datapack CSV. */
@Data
public class OfflineDatapackCsvSelectVO {

    private String fileName;

    private FileResourceVO fileResource;

    private Map<String, String> schema;

    private List<String> columnNames;

    private String nullValue;
}
