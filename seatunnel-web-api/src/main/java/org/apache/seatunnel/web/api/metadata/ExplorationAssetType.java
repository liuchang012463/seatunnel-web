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
            "tags",
            "service",
            "service",
            "service,messageSchema,partitions,cleanupPolicies,retentionTime,retentionSize,"
                    + "replicationFactor,maximumMessageSize,minimumInSyncReplicas,topicConfig,tags"),

    SEARCH_INDEX(
            "searchIndex",
            "索引",
            SampleKind.MESSAGES,
            false,
            "tags",
            "service",
            "service",
            "service,fields,searchIndexSettings,indexType,tags"),

    CONTAINER(
            "container",
            "容器",
            SampleKind.ROWS,
            true,
            "parent,tags",
            "service,parent",
            "service,parent",
            "service,dataModel,parent,prefix,numberOfObjects,size,fileFormats,fullPath,tags"),

    DIRECTORY(
            "directory",
            "目录",
            SampleKind.NONE,
            true,
            "parent,tags",
            "service,parent",
            "service,parent,children",
            "service,parent,children,numberOfFiles,numberOfSubDirectories,totalSize,path,tags"),

    FILE(
            "file",
            "文件",
            SampleKind.ROWS,
            false,
            "directory,tags",
            "service",
            "service",
            "service,directory,columns,size,fileType,mimeType,path,tags"),

    API_COLLECTION(
            "apiCollection",
            "接口分组",
            SampleKind.NONE,
            true,
            "endpointURL,tags",
            "service",
            "service",
            "service,apiEndpoints,endpointURL,tags"),

    API_ENDPOINT(
            "apiEndpoint",
            "接口",
            SampleKind.NONE,
            false,
            "apiCollection,requestMethod,endpointURL,tags",
            "service",
            "service",
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
    private final String parentFields;
    private final String childrenFields;
    private final String detailFields;

    ExplorationAssetType(
            String entityType,
            String label,
            SampleKind sampleKind,
            boolean supportsChildren,
            String listFields,
            String parentFields,
            String childrenFields,
            String detailFields) {
        this.entityType = entityType;
        this.label = label;
        this.sampleKind = sampleKind;
        this.supportsChildren = supportsChildren;
        this.listFields = listFields;
        this.parentFields = parentFields;
        this.childrenFields = childrenFields;
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
     * Collection projection. List requests only need identity, ownership and row
     * metadata; schemas and sample payloads are read through the detail endpoint.
     */
    public String listFields() {
        return listFields;
    }

    /**
     * Lightweight projection for breadcrumb and ancestor reads. It deliberately omits
     * schemas, samples and child collections.
     */
    public String parentFields() {
        return parentFields;
    }

    /**
     * Projection used when this family is the parent of the level being listed.
     * Directories need their child references; other families only need their identity.
     */
    public String childrenFields() {
        return childrenFields;
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
