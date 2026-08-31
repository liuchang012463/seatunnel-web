package org.apache.seatunnel.web.api.lake.operation;

/** Stable resource type codes shared by the operation journal and gateways. */
public final class LakeResourceTypes {

    public static final String SOURCE_OBJECT_REF = "SOURCE_OBJECT_REF";
    public static final String ODS_DATABASE_BINDING = "ODS_DATABASE_BINDING";
    public static final String ODS_TABLE_MAPPING = "ODS_TABLE_MAPPING";
    public static final String EXTERNAL_CATALOG_BINDING = "EXTERNAL_CATALOG_BINDING";

    private LakeResourceTypes() {
    }

    public static String normalize(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("Lake resource type must not be blank");
        }
        String value = resourceType.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "SOURCE_OBJECT", "SOURCE" -> SOURCE_OBJECT_REF;
            case "ODS_DATABASE", "DATABASE" -> ODS_DATABASE_BINDING;
            case "ODS_TABLE", "TABLE", "TABLE_MAPPING" -> ODS_TABLE_MAPPING;
            case "EXTERNAL_CATALOG", "CATALOG" -> EXTERNAL_CATALOG_BINDING;
            case SOURCE_OBJECT_REF, ODS_DATABASE_BINDING, ODS_TABLE_MAPPING,
                    EXTERNAL_CATALOG_BINDING -> value;
            default -> throw new IllegalArgumentException("Unsupported lake resource type");
        };
    }
}
