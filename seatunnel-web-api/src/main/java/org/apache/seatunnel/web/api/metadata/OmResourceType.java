package org.apache.seatunnel.web.api.metadata;

/**
 * OpenMetadata data-asset families that are not Table. SeaTunnel data sources such as
 * Kafka, object storage, file transfer and HTTP expose their assets through these
 * collections instead of Database/Schema/Table.
 */
public enum OmResourceType {

    TOPIC("topic", "主题", "messageSchema", "messageSchema,tags", true),
    CONTAINER("container", "容器", "dataModel", "dataModel,tags", true),
    DIRECTORY("directory", "目录", null, "tags", false),
    FILE("file", "文件", "columns", "columns,tags", true),
    API_COLLECTION("apiCollection", "接口分组", null, "tags", false),
    API_ENDPOINT("apiEndpoint", "接口", null, "requestSchema,responseSchema,tags", false),
    SEARCH_INDEX("searchIndex", "索引", "fields", "fields,tags", true);

    private final String entityType;
    private final String label;
    private final String listFields;
    private final String detailFields;
    /** OpenMetadata 2.0.4 rejects an unknown field, so only these entities carry sampleData. */
    private final boolean sampleDataField;

    OmResourceType(String entityType, String label, String listFields, String detailFields,
                   boolean sampleDataField) {
        this.entityType = entityType;
        this.label = label;
        this.listFields = listFields;
        this.detailFields = detailFields;
        this.sampleDataField = sampleDataField;
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

    /**
     * Detail projection including the owning service and, where OpenMetadata defines it, the
     * sample payload. {@code service} is required so callers can enforce data-source ownership.
     */
    public String detailFields() {
        String fields = withService(detailFields);
        return sampleDataField ? fields + ",sampleData" : fields;
    }

    /**
     * Detail projection used when the caller may not read sample data.
     * Still includes {@code service} for ownership checks.
     */
    public String detailFieldsWithoutSampleData() {
        return withService(detailFields);
    }

    private static String withService(String fields) {
        if (fields == null || fields.isBlank()) {
            return "service";
        }
        return "service," + fields;
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
