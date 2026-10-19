package org.apache.seatunnel.web.api.metadata;

import java.util.Locale;

/**
 * OpenMetadata 2.0.4 asset families that are explored outside Database/Schema/Table.
 *
 * <p>The enum is also the API contract: {@link #name()} is sent by the UI and
 * {@link #entityType()} is the OpenMetadata entity name. {@link #fromValue(String)}
 * accepts both spellings so callers never have to know which one they received.</p>
 */
public enum ExplorationAssetType {

    TOPIC(
            "topic",
            "主题",
            SampleKind.MESSAGES,
            false,
            "messageSchema,tags",
            "service,messageSchema,partitions,cleanupPolicies,retentionTime,retentionSize,"
                    + "replicationFactor,maximumMessageSize,minimumInSyncReplicas,topicConfig,tags"),

    SEARCH_INDEX(
            "searchIndex",
            "索引",
            SampleKind.MESSAGES,
            false,
            "fields,tags",
            "service,fields,searchIndexSettings,indexType,tags"),

    CONTAINER(
            "container",
            "容器",
            SampleKind.ROWS,
            true,
            "dataModel,parent,tags",
            "service,dataModel,parent,prefix,numberOfObjects,size,fileFormats,fullPath,tags"),

    DIRECTORY(
            "directory",
            "目录",
            SampleKind.NONE,
            true,
            "parent,tags",
            "service,parent,children,numberOfFiles,numberOfSubDirectories,totalSize,path,tags"),

    FILE(
            "file",
            "文件",
            SampleKind.ROWS,
            false,
            "directory,columns,tags",
            "service,directory,columns,size,fileType,mimeType,path,tags"),

    API_COLLECTION(
            "apiCollection",
            "接口分组",
            SampleKind.NONE,
            true,
            "apiEndpoints,tags",
            "service,apiEndpoints,endpointURL,tags"),

    API_ENDPOINT(
            "apiEndpoint",
            "接口",
            SampleKind.NONE,
            false,
            "apiCollection,requestSchema,responseSchema,tags",
            "service,apiCollection,requestMethod,endpointURL,requestSchema,responseSchema,tags");

    /** Shape of the sample payload OpenMetadata stores for this family. */
    public enum SampleKind {
        MESSAGES,
        ROWS,
        NONE
    }

    private final String entityType;
    private final String label;
    private final SampleKind sampleKind;
    private final boolean supportsChildren;
    private final String listFields;
    private final String detailFields;

    ExplorationAssetType(
            String entityType,
            String label,
            SampleKind sampleKind,
            boolean supportsChildren,
            String listFields,
            String detailFields) {
        this.entityType = entityType;
        this.label = label;
        this.sampleKind = sampleKind;
        this.supportsChildren = supportsChildren;
        this.listFields = listFields;
        this.detailFields = detailFields;
    }

    /** OpenMetadata entity name used in REST collections. */
    public String entityType() {
        return entityType;
    }

    /** Short product label used by the SeaTunnel Web UI. */
    public String label() {
        return label;
    }

    public SampleKind sampleKind() {
        return sampleKind;
    }

    /** Whether this family can contain other assets. */
    public boolean supportsChildren() {
        return supportsChildren;
    }

    /**
     * Schema-only projection for collection listings. Sample payloads are read through
     * the dedicated sampleData endpoint, never through the list.
     */
    public String listFields() {
        return listFields;
    }

    /**
     * Detail projection including the owning service, which callers must compare with
     * the data source binding before returning anything.
     */
    public String detailFields() {
        return detailFields;
    }

    /**
     * Resolves both the API spelling ({@code SEARCH_INDEX}) and the OpenMetadata
     * spelling ({@code searchIndex}), ignoring case, underscores and dashes.
     */
    public static ExplorationAssetType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = normalize(value);
        for (ExplorationAssetType type : values()) {
            if (normalize(type.name()).equals(normalized)
                    || normalize(type.entityType).equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
