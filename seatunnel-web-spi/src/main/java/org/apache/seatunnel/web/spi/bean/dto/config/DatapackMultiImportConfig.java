package org.apache.seatunnel.web.spi.bean.dto.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** One inspected package and its selected files. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DatapackMultiImportConfig {
    private Long packageResourceId;
    private String packageName;
    private String packageFingerprint;
    private List<String> fileNames;
    private List<CsvFile> files;
    /** Older definitions retain their saved destinations until edited and saved again. */
    private List<CsvMapping> mappings;

    @Data
    public static class CsvFile {
        private String fileName;
        private Long fileResourceId;
        private List<String> columnNames;
        private String nullValue;
    }

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
