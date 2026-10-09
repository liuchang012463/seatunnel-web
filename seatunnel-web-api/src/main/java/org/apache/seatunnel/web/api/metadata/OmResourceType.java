package org.apache.seatunnel.web.api.metadata;

/**
 * OpenMetadata data-asset families that are not Table. SeaTunnel data sources such as
 * Kafka, object storage, file transfer and HTTP expose their assets through these
 * collections instead of Database/Schema/Table.
 */
public enum OmResourceType {

    TOPIC("topic", "主题", "messageSchema", "messageSchema,tags"),
    CONTAINER("container", "容器", "dataModel", "dataModel,tags"),
    DIRECTORY("directory", "目录", null, "tags"),
    FILE("file", "文件", "columns", "columns,tags"),
    API_COLLECTION("apiCollection", "接口分组", null, "tags"),
    API_ENDPOINT("apiEndpoint", "接口", null, "requestSchema,responseSchema,tags"),
    SEARCH_INDEX("searchIndex", "索引", "fields", "fields,tags");

    private final String entityType;
    private final String label;
    private final String listFields;
    private final String detailFields;

    OmResourceType(String entityType, String label, String listFields, String detailFields) {
        this.entityType = entityType;
        this.label = label;
        this.listFields = listFields;
        this.detailFields = detailFields;
    }

    /** OpenMetadata entity name used in the REST contract and in list responses. */
    public String entityType() {
        return entityType;
    }

    /** Short product label used by the SeaTunnel Web UI. */
    public String label() {
        return label;
    }

    /**
     * Schema-only projection for collection listings. Sample payloads are deliberately
     * excluded so a listing of hundreds of assets stays small.
     */
    public String listFields() {
        return listFields;
    }

    /** Detail projection including the sample payload. */
    public String detailFields() {
        return detailFields + ",sampleData";
    }

    /** Detail projection used when the caller may not read sample data. */
    public String detailFieldsWithoutSampleData() {
        return detailFields;
    }

    public static OmResourceType fromEntityType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (OmResourceType type : values()) {
            if (type.entityType.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
