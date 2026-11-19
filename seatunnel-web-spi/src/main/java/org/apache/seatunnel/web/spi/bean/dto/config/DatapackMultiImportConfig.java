package org.apache.seatunnel.web.spi.bean.dto.config;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** A single inspected package and its CSV-to-table mappings. */
@Data
public class DatapackMultiImportConfig {
    private Long packageResourceId;
    private String packageName;
    private String packageFingerprint;
    private List<CsvMapping> mappings;

    @Data
    public static class CsvMapping {
        private String fileName;
        private Long fileResourceId;
        private Map<String, String> fields;
        private String nullValue;
        private String targetTable;
        private Map<String, String> fieldMapping;
    }
}
