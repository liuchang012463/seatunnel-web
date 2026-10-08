package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;

/**
 * Schema and sample payload of one non-relational OpenMetadata asset. Sample rows and
 * messages stay empty until the ingestion pipeline has collected them.
 */
@Data
public class DataSourceResourceDetailVO {
    private DataSourceResourceVO resource;
    private List<DataSourceResourceFieldVO> fields;
    private boolean sampleDataAvailable;
    private List<String> sampleColumns;
    private List<List<String>> sampleRows;
    private List<String> messages;
}
