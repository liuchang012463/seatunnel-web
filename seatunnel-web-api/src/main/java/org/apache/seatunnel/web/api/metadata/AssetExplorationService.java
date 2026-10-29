package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataAsset;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataAssetDetail;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataAssetField;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataAssetSample;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetFieldVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetPageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetPropertyVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetRefVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetSampleVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetSchemaVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationAssetVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only exploration of the OpenMetadata assets of a non-relational data source.
 *
 * <p>The service exposes a single level at a time. Topics and search indexes are flat;
 * containers nest; SFTP/FTP directories contain direct child directories and files;
 * API collections contain endpoints. Every read validates the OpenMetadata service
 * ownership against the data-source binding before returning anything.</p>
 */
@Slf4j
@Service
public class AssetExplorationService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_SCAN = 5000;
    private static final int MAX_PAGES = 20;
    private static final int MAX_BREADCRUMB_DEPTH = 20;

    private final OpenMetadataConfigResolver configResolver;
    private final DataSourceDao dataSourceDao;
    private final MetadataBindingDao metadataBindingDao;
    private final MetadataConnectorRegistry connectorRegistry;
    private final OpenMetadataClient openMetadataClient;

    public AssetExplorationService(
            OpenMetadataConfigResolver configResolver,
            DataSourceDao dataSourceDao,
            MetadataBindingDao metadataBindingDao,
            MetadataConnectorRegistry connectorRegistry,
            OpenMetadataClient openMetadataClient) {
        this.configResolver = configResolver;
        this.dataSourceDao = dataSourceDao;
        this.metadataBindingDao = metadataBindingDao;
        this.connectorRegistry = connectorRegistry;
        this.openMetadataClient = openMetadataClient;
    }

    /** Lists one level of the hierarchy; an empty parent means the root level. */
    public DataExplorationAssetPageVO listAssets(
            Long dataSourceId,
            String parentId,
            String parentType,
            int pageNo,
            int pageSize,
            String search) {
        AssetContext context = context(dataSourceId);
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = pageSize <= 0
                ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String keyword = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

        ExplorationAssetType parent = null;
        if (!isBlank(parentId)) {
            parent = ExplorationAssetType.fromValue(parentType);
            if (parent == null || !isChildParent(parent)) {
                throw invalid("parentType");
            }
        }

        List<ExplorationAssetType> types = parent == null
                ? rootTypes(context) : childTypes(context, parent);
        OpenMetadataAssetDetail parentDetail = parent == null
                ? null
                : requireOwnedDetail(
                        context, parent, parentId, false, parent.childrenFields());
        List<DataExplorationAssetRefVO> breadcrumbs = parentDetail == null
                ? List.of() : breadcrumbs(context, parentDetail);

        Map<String, OpenMetadataAsset> collected = new LinkedHashMap<>();
        boolean truncated = false;
        for (ExplorationAssetType type : types) {
            int remaining = MAX_SCAN - collected.size();
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            LevelResult level = listLevel(context, type, parentDetail, keyword, remaining);
            for (OpenMetadataAsset asset : level.assets()) {
                if (asset.id() != null) {
                    collected.putIfAbsent(asset.id(), asset);
                }
            }
            truncated = truncated || level.truncated();
        }

        List<OpenMetadataAsset> ordered = new ArrayList<>(collected.values());
        long offsetLong = (long) (safePageNo - 1) * safePageSize;
        List<DataExplorationAssetVO> records = new ArrayList<>();
        if (offsetLong < ordered.size()) {
            int offset = (int) offsetLong;
            for (int index = offset; index < ordered.size() && records.size() < safePageSize; index++) {
                records.add(toAssetVo(ordered.get(index)));
            }
        }

        DataExplorationAssetPageVO result = new DataExplorationAssetPageVO();
        result.setBreadcrumbs(breadcrumbs);
        result.setParent(breadcrumbs.isEmpty() ? null : breadcrumbs.get(breadcrumbs.size() - 1));
        result.setRecords(records);
        result.setTotal(ordered.size());
        result.setPageNo(safePageNo);
        result.setPageSize(safePageSize);
        result.setTruncated(truncated);
        return result;
    }

    /** Reads one owned asset with its schema, sample payload and properties. */
    public DataExplorationAssetDetailVO getAsset(
            Long dataSourceId, String assetId, String assetType) {
        AssetContext context = context(dataSourceId);
        ExplorationAssetType type = ExplorationAssetType.fromValue(assetType);
        if (type == null || !context.adapter().resourceTypes().contains(type)) {
            throw invalid("assetType is not supported by this data source");
        }
        if (isBlank(assetId)) {
            throw invalid("assetId");
        }
        OpenMetadataAssetDetail detail = requireOwnedDetail(
                context, type, assetId,
                Boolean.TRUE.equals(context.binding().getSampleDataEnabled()));
        List<DataExplorationAssetRefVO> breadcrumbs = ancestors(context, detail.asset());
        return toDetailVo(context, type, detail, breadcrumbs);
    }

    private LevelResult listLevel(
            AssetContext context,
            ExplorationAssetType type,
            OpenMetadataAssetDetail parentDetail,
            String keyword,
            int remaining) {
        if (type == ExplorationAssetType.CONTAINER && parentDetail != null) {
            return listContainerChildren(context, parentDetail, keyword, remaining);
        }
        if (type == ExplorationAssetType.DIRECTORY && parentDetail != null) {
            return listDirectoryChildren(context, parentDetail, keyword, remaining);
        }
        if (type == ExplorationAssetType.API_ENDPOINT && parentDetail != null) {
            return listCursor(
                    context, type, parentDetail.asset().fullyQualifiedName(),
                    false, keyword, remaining);
        }
        boolean root = type == ExplorationAssetType.DIRECTORY || type == ExplorationAssetType.FILE;
        return listCursor(context, type, null, root, keyword, remaining);
    }

    private LevelResult listCursor(
            AssetContext context,
            ExplorationAssetType type,
            String parentFqn,
            boolean root,
            String keyword,
            int remaining) {
        List<OpenMetadataAsset> collected = new ArrayList<>();
        String after = null;
        int pageSize = Math.min(1000, Math.max(1, remaining));
        int scanned = 0;
        boolean truncated = false;
        for (int pageNumber = 0; pageNumber < MAX_PAGES && collected.size() < remaining
                && scanned < MAX_SCAN; pageNumber++) {
            OpenMetadataPage<OpenMetadataAsset> page = openMetadataClient.listAssetsPage(
                    type, context.serviceFqn(), parentFqn, root, pageSize, after);
            if (page == null) {
                break;
            }
            scanned += page.data().size();
            for (OpenMetadataAsset asset : page.data()) {
                // The container collection returns nested containers too. The root
                // level only shows containers without a parent; children are reached
                // through listContainerChildren.
                if (type == ExplorationAssetType.CONTAINER
                        && parentFqn == null && asset.parentId() != null) {
                    continue;
                }
                if (matches(asset, keyword)) {
                    collected.add(asset);
                }
                if (collected.size() >= remaining) {
                    break;
                }
            }
            String next = page.after();
            if (next == null || next.isBlank() || page.data().isEmpty()) {
                break;
            }
            after = next;
            truncated = scanned >= MAX_SCAN;
        }
        return new LevelResult(collected, collected.size() >= remaining || truncated);
    }

    private LevelResult listContainerChildren(
            AssetContext context,
            OpenMetadataAssetDetail parent,
            String keyword,
            int remaining) {
        List<OpenMetadataAsset> collected = new ArrayList<>();
        int offset = 0;
        int pageSize = Math.min(1000, Math.max(1, remaining));
        int scanned = 0;
        boolean truncated = false;
        for (int pageNumber = 0; pageNumber < MAX_PAGES && collected.size() < remaining
                && scanned < MAX_SCAN; pageNumber++) {
            OpenMetadataPage<OpenMetadataAsset> page = openMetadataClient.listContainerChildren(
                    parent.asset().fullyQualifiedName(), pageSize, offset);
            if (page == null || page.data().isEmpty()) {
                break;
            }
            scanned += page.data().size();
            for (OpenMetadataAsset asset : page.data()) {
                if (matches(asset, keyword)) {
                    collected.add(asset);
                }
                if (collected.size() >= remaining) {
                    break;
                }
            }
            offset += page.data().size();
            truncated = scanned >= MAX_SCAN;
            if (page.total() > 0 && offset >= page.total()) {
                break;
            }
            if (page.data().size() < pageSize) {
                break;
            }
        }
        return new LevelResult(collected, collected.size() >= remaining || truncated);
    }

    private LevelResult listDirectoryChildren(
            AssetContext context,
            OpenMetadataAssetDetail parent,
            String keyword,
            int remaining) {
        List<OpenMetadataAsset> collected = new ArrayList<>();
        for (OpenMetadataAsset child : parent.children()) {
            if (ExplorationAssetType.DIRECTORY.entityType().equals(child.entityType())
                    && matches(child, keyword)) {
                collected.add(child);
            }
            if (collected.size() >= remaining) {
                break;
            }
        }
        LevelResult files = collected.size() >= remaining
                ? new LevelResult(List.of(), true)
                : listDirectoryFiles(
                        context, parent.asset().fullyQualifiedName(), keyword,
                        remaining - collected.size());
        collected.addAll(files.assets());
        collected.sort(Comparator
                .comparing((OpenMetadataAsset asset) ->
                        !ExplorationAssetType.DIRECTORY.entityType().equals(asset.entityType()))
                .thenComparing(asset -> asset.name() == null ? "" : asset.name()));
        return new LevelResult(collected, files.truncated());
    }

    private LevelResult listDirectoryFiles(
            AssetContext context,
            String directoryFqn,
            String keyword,
            int remaining) {
        List<OpenMetadataAsset> collected = new ArrayList<>();
        String after = null;
        int pageSize = Math.min(1000, Math.max(1, remaining));
        int scanned = 0;
        boolean truncated = false;
        for (int pageNumber = 0; pageNumber < MAX_PAGES && collected.size() < remaining
                && scanned < MAX_SCAN; pageNumber++) {
            OpenMetadataPage<OpenMetadataAsset> page = openMetadataClient.listFilesInDirectory(
                    directoryFqn, pageSize, after);
            if (page == null) {
                break;
            }
            scanned += page.data().size();
            for (OpenMetadataAsset asset : page.data()) {
                if (matches(asset, keyword)) {
                    collected.add(asset);
                }
                if (collected.size() >= remaining) {
                    break;
                }
            }
            String next = page.after();
            if (next == null || next.isBlank() || page.data().isEmpty()) {
                break;
            }
            after = next;
            truncated = scanned >= MAX_SCAN;
        }
        return new LevelResult(collected, collected.size() >= remaining || truncated);
    }

    private OpenMetadataAssetDetail requireOwnedDetail(
            AssetContext context, ExplorationAssetType type, String assetId, boolean includeSample) {
        return requireOwnedDetail(context, type, assetId, includeSample, type.detailFields());
    }

    private OpenMetadataAssetDetail requireOwnedDetail(
            AssetContext context,
            ExplorationAssetType type,
            String assetId,
            boolean includeSample,
            String fields) {
        OpenMetadataAssetDetail detail = openMetadataClient.getAssetDetail(
                type, assetId, includeSample, fields);
        if (detail == null || detail.asset() == null) {
            throw invalid("asset does not exist");
        }
        String serviceFqn = detail.asset().serviceFullyQualifiedName();
        if (isBlank(serviceFqn) || !context.serviceFqn().equals(serviceFqn)) {
            // Fail closed: a missing or foreign service reference must never open a
            // cross-data-source read.
            throw invalid("asset does not belong to this data source");
        }
        return detail;
    }

    private List<DataExplorationAssetRefVO> ancestors(
            AssetContext context, OpenMetadataAsset asset) {
        if (asset == null || isBlank(asset.parentId())) {
            return List.of();
        }
        ExplorationAssetType parentType = ExplorationAssetType.fromValue(asset.parentType());
        if (parentType == null) {
            return List.of();
        }
        try {
            OpenMetadataAssetDetail parent = requireOwnedDetail(
                    context, parentType, asset.parentId(), false, parentType.parentFields());
            return breadcrumbs(context, parent);
        } catch (RuntimeException error) {
            // A deleted or unreadable ancestor must not hide the asset itself.
            log.warn("OpenMetadata asset ancestor lookup failed: assetId={}, parentId={}, errorType={}, message={}",
                    asset.id(), asset.parentId(),
                    error.getClass().getSimpleName(), error.getMessage());
            return List.of();
        }
    }

    /**
     * Walks up from an already-read parent. The caller reads the parent once with the
     * children projection, so listing a level does not fetch the same parent twice.
     */
    private List<DataExplorationAssetRefVO> breadcrumbs(
            AssetContext context, OpenMetadataAssetDetail start) {
        List<DataExplorationAssetRefVO> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        OpenMetadataAssetDetail current = start;
        for (int depth = 0; depth < MAX_BREADCRUMB_DEPTH && current != null; depth++) {
            OpenMetadataAsset asset = current.asset();
            if (asset == null || isBlank(asset.id()) || !seen.add(asset.id())) {
                break;
            }
            refs.add(0, toRef(asset));
            if (isBlank(asset.parentId())) {
                break;
            }
            ExplorationAssetType parentType = ExplorationAssetType.fromValue(asset.parentType());
            if (parentType == null) {
                break;
            }
            current = requireOwnedDetail(
                    context, parentType, asset.parentId(), false, parentType.parentFields());
        }
        return refs;
    }

    private List<ExplorationAssetType> rootTypes(AssetContext context) {
        List<ExplorationAssetType> supported = context.adapter().resourceTypes();
        if (supported.contains(ExplorationAssetType.DIRECTORY)) {
            List<ExplorationAssetType> roots = new ArrayList<>();
            roots.add(ExplorationAssetType.DIRECTORY);
            if (supported.contains(ExplorationAssetType.FILE)) {
                roots.add(ExplorationAssetType.FILE);
            }
            return roots;
        }
        return List.of(supported.get(0));
    }

    private List<ExplorationAssetType> childTypes(
            AssetContext context, ExplorationAssetType parent) {
        List<ExplorationAssetType> supported = context.adapter().resourceTypes();
        return switch (parent) {
            case CONTAINER -> supported.contains(ExplorationAssetType.CONTAINER)
                    ? List.of(ExplorationAssetType.CONTAINER) : List.of();
            // Directory levels are built from the parent's direct children and the
            // file collection in one pass; adding FILE here would fetch it twice.
            case DIRECTORY -> List.of(ExplorationAssetType.DIRECTORY);
            case API_COLLECTION -> supported.contains(ExplorationAssetType.API_ENDPOINT)
                    ? List.of(ExplorationAssetType.API_ENDPOINT) : List.of();
            default -> throw invalid("parentType");
        };
    }

    private static boolean isChildParent(ExplorationAssetType type) {
        return type == ExplorationAssetType.CONTAINER
                || type == ExplorationAssetType.DIRECTORY
                || type == ExplorationAssetType.API_COLLECTION;
    }

    private static boolean matches(OpenMetadataAsset asset, String keyword) {
        if (keyword.isEmpty()) {
            return true;
        }
        return contains(asset.name(), keyword)
                || contains(asset.displayName(), keyword)
                || contains(asset.fullyQualifiedName(), keyword)
                || contains(asset.description(), keyword)
                || contains(asset.requestMethod(), keyword)
                || contains(asset.endpointURL(), keyword);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private DataExplorationAssetVO toAssetVo(OpenMetadataAsset asset) {
        ExplorationAssetType type = ExplorationAssetType.fromValue(asset.entityType());
        DataExplorationAssetVO vo = new DataExplorationAssetVO();
        vo.setId(asset.id());
        vo.setName(asset.name());
        vo.setDisplayName(asset.displayName());
        vo.setFullyQualifiedName(asset.fullyQualifiedName());
        vo.setAssetType(type == null ? asset.entityType() : type.name());
        vo.setAssetLabel(type == null ? asset.entityType() : type.label());
        vo.setDescription(asset.description());
        boolean hasChildren = type != null && type.supportsChildren();
        if (type == ExplorationAssetType.API_COLLECTION
                && asset.childCount() != null && asset.childCount() == 0) {
            hasChildren = false;
        }
        vo.setHasChildren(hasChildren);
        vo.setChildCount(asset.childCount());
        vo.setFieldCount(asset.fieldCount());
        vo.setTags(new ArrayList<>(asset.tags()));
        vo.setSizeInByte(asset.sizeInByte());
        vo.setUpdatedAt(asset.updatedAt());
        vo.setRequestMethod(asset.requestMethod());
        vo.setEndpointURL(asset.endpointURL());
        return vo;
    }

    private DataExplorationAssetRefVO toRef(OpenMetadataAsset asset) {
        ExplorationAssetType type = ExplorationAssetType.fromValue(asset.entityType());
        DataExplorationAssetRefVO ref = new DataExplorationAssetRefVO();
        ref.setId(asset.id());
        ref.setName(isBlank(asset.displayName()) ? asset.name() : asset.displayName());
        ref.setAssetType(type == null ? asset.entityType() : type.name());
        ref.setFullyQualifiedName(asset.fullyQualifiedName());
        ref.setDescription(asset.description());
        return ref;
    }

    private DataExplorationAssetDetailVO toDetailVo(
            AssetContext context,
            ExplorationAssetType type,
            OpenMetadataAssetDetail detail,
            List<DataExplorationAssetRefVO> breadcrumbs) {
        DataExplorationAssetDetailVO result = new DataExplorationAssetDetailVO();
        result.setAsset(toAssetVo(detail.asset()));
        result.setBreadcrumbs(breadcrumbs);
        result.setSchema(toSchemaVo(type, detail));
        result.setSample(toSampleVo(context, type, detail));
        result.setProperties(toProperties(type, detail.properties()));
        result.setTags(new ArrayList<>(detail.asset().tags()));
        result.setDescription(detail.asset().description());
        result.setUpdatedAt(detail.asset().updatedAt());
        return result;
    }

    private DataExplorationAssetSchemaVO toSchemaVo(
            ExplorationAssetType type, OpenMetadataAssetDetail detail) {
        DataExplorationAssetSchemaVO schema = new DataExplorationAssetSchemaVO();
        schema.setKind(schemaKind(type));
        schema.setFields(toFieldVos(detail.fields()));
        schema.setRequestFields(toFieldVos(detail.requestFields()));
        schema.setResponseFields(toFieldVos(detail.responseFields()));
        schema.setStatus(schemaStatus(type, detail));
        return schema;
    }

    private static String schemaKind(ExplorationAssetType type) {
        return switch (type) {
            case TOPIC -> "MESSAGE";
            case SEARCH_INDEX, CONTAINER, FILE -> "COLUMNS";
            case API_ENDPOINT -> "REQUEST_RESPONSE";
            default -> "NONE";
        };
    }

    private static String schemaStatus(
            ExplorationAssetType type, OpenMetadataAssetDetail detail) {
        if (type == ExplorationAssetType.DIRECTORY
                || type == ExplorationAssetType.API_COLLECTION) {
            return "NOT_SUPPORTED";
        }
        if (type == ExplorationAssetType.CONTAINER
                && !Boolean.TRUE.equals(detail.properties().get("dataModelAvailable"))) {
            return "NEEDS_DATA_MODEL";
        }
        if (type == ExplorationAssetType.FILE && !isTabularFile(detail.properties())) {
            return "NOT_APPLICABLE";
        }
        if (type == ExplorationAssetType.API_ENDPOINT) {
            return detail.requestFields().isEmpty() && detail.responseFields().isEmpty()
                    ? "NO_SCHEMA" : "AVAILABLE";
        }
        return detail.fields().isEmpty() ? "NO_SCHEMA" : "AVAILABLE";
    }

    private DataExplorationAssetSampleVO toSampleVo(
            AssetContext context, ExplorationAssetType type, OpenMetadataAssetDetail detail) {
        DataExplorationAssetSampleVO sample = new DataExplorationAssetSampleVO();
        sample.setKind(type.sampleKind().name());
        String status = sampleStatus(context, type, detail);
        sample.setStatus(status);
        if (!"AVAILABLE".equals(status) || detail.sample() == null) {
            return sample;
        }
        OpenMetadataAssetSample payload = detail.sample();
        sample.setColumns(new ArrayList<>(payload.columns()));
        sample.setRows(new ArrayList<>(payload.rows()));
        sample.setMessages(new ArrayList<>(payload.messages()));
        sample.setMessageCount(payload.messages().size());
        return sample;
    }

    private static String sampleStatus(
            AssetContext context, ExplorationAssetType type, OpenMetadataAssetDetail detail) {
        if (type.sampleKind() == ExplorationAssetType.SampleKind.NONE) {
            return "NOT_SUPPORTED";
        }
        if (type == ExplorationAssetType.CONTAINER
                && !Boolean.TRUE.equals(detail.properties().get("dataModelAvailable"))) {
            return "NEEDS_DATA_MODEL";
        }
        if (type == ExplorationAssetType.FILE && !isTabularFile(detail.properties())) {
            return "NOT_APPLICABLE";
        }
        if (!Boolean.TRUE.equals(context.binding().getSampleDataEnabled())) {
            return "DISABLED";
        }
        if (detail.samplePermissionDenied()) {
            return "PERMISSION_DENIED";
        }
        if (detail.sampleError()) {
            return "ERROR";
        }
        return detail.sample() != null && !detail.sample().isEmpty()
                ? "AVAILABLE" : "NOT_COLLECTED";
    }

    private static boolean isTabularFile(Map<String, Object> properties) {
        Object fileType = properties.get("fileType");
        if (fileType != null) {
            String value = String.valueOf(fileType);
            if ("CSV".equalsIgnoreCase(value) || "TSV".equalsIgnoreCase(value)) {
                return true;
            }
        }
        Object mimeType = properties.get("mimeType");
        if (mimeType != null) {
            String value = String.valueOf(mimeType);
            return "text/csv".equalsIgnoreCase(value)
                    || "text/tab-separated-values".equalsIgnoreCase(value);
        }
        return false;
    }

    private static List<DataExplorationAssetFieldVO> toFieldVos(
            List<OpenMetadataAssetField> fields) {
        List<DataExplorationAssetFieldVO> result = new ArrayList<>();
        for (OpenMetadataAssetField field : fields) {
            if (field == null) {
                continue;
            }
            DataExplorationAssetFieldVO vo = new DataExplorationAssetFieldVO();
            vo.setName(field.name());
            vo.setDataType(field.dataType());
            vo.setDescription(field.description());
            vo.setTags(new ArrayList<>(field.tags()));
            vo.setChildren(toFieldVos(field.children()));
            result.add(vo);
        }
        return result;
    }

    private static List<DataExplorationAssetPropertyVO> toProperties(
            ExplorationAssetType type, Map<String, Object> properties) {
        List<DataExplorationAssetPropertyVO> result = new ArrayList<>();
        switch (type) {
            case TOPIC -> {
                add(result, "partitions", "分区数", properties.get("partitions"), "NUMBER");
                add(result, "replicationFactor", "副本数",
                        properties.get("replicationFactor"), "NUMBER");
                add(result, "retentionTime", "保留时间",
                        properties.get("retentionTime"), "DURATION");
                add(result, "retentionSize", "保留大小",
                        properties.get("retentionSize"), "SIZE");
                add(result, "maximumMessageSize", "最大消息大小",
                        properties.get("maximumMessageSize"), "SIZE");
                add(result, "minimumInSyncReplicas", "最小同步副本",
                        properties.get("minimumInSyncReplicas"), "NUMBER");
                add(result, "cleanupPolicies", "清理策略",
                        properties.get("cleanupPolicies"), "LIST");
            }
            case SEARCH_INDEX -> {
                add(result, "indexType", "索引类型", properties.get("indexType"), "TEXT");
                add(result, "fieldCount", "字段数", properties.get("fieldCount"), "NUMBER");
                add(result, "searchIndexSettings", "索引设置",
                        properties.get("searchIndexSettings"), "CODE");
            }
            case CONTAINER -> {
                add(result, "prefix", "前缀", properties.get("prefix"), "CODE");
                add(result, "numberOfObjects", "对象数",
                        properties.get("numberOfObjects"), "NUMBER");
                add(result, "size", "大小", properties.get("size"), "SIZE");
                add(result, "fileFormats", "文件格式",
                        properties.get("fileFormats"), "LIST");
                add(result, "fullPath", "完整路径", properties.get("fullPath"), "CODE");
                add(result, "parent", "父容器", properties.get("parent"), "TEXT");
            }
            case DIRECTORY -> {
                add(result, "path", "路径", properties.get("path"), "CODE");
                add(result, "numberOfFiles", "文件数",
                        properties.get("numberOfFiles"), "NUMBER");
                add(result, "numberOfSubDirectories", "子目录数",
                        properties.get("numberOfSubDirectories"), "NUMBER");
                add(result, "totalSize", "总大小", properties.get("totalSize"), "SIZE");
                add(result, "parent", "父目录", properties.get("parent"), "TEXT");
            }
            case FILE -> {
                add(result, "fileType", "文件类型", properties.get("fileType"), "TEXT");
                add(result, "mimeType", "MIME 类型", properties.get("mimeType"), "TEXT");
                add(result, "size", "大小", properties.get("size"), "SIZE");
                add(result, "path", "路径", properties.get("path"), "CODE");
                Object directory = properties.get("directory");
                add(result, "directory", "所属目录",
                        directory == null || String.valueOf(directory).isBlank()
                                ? "根目录" : directory, "TEXT");
            }
            case API_COLLECTION -> {
                add(result, "endpointURL", "接口地址",
                        properties.get("endpointURL"), "CODE");
            }
            case API_ENDPOINT -> {
                add(result, "requestMethod", "请求方法",
                        properties.get("requestMethod"), "TEXT");
                add(result, "endpointURL", "接口地址",
                        properties.get("endpointURL"), "CODE");
                add(result, "apiCollection", "所属分组",
                        properties.get("apiCollection"), "TEXT");
            }
            default -> {
                // No display properties for this family.
            }
        }
        return result;
    }

    private static void add(
            List<DataExplorationAssetPropertyVO> properties,
            String key,
            String label,
            Object value,
            String displayType) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        DataExplorationAssetPropertyVO property = new DataExplorationAssetPropertyVO();
        property.setKey(key);
        property.setLabel(label);
        property.setValue(value);
        property.setDisplayType(displayType);
        properties.add(property);
    }

    private AssetContext context(Long dataSourceId) {
        if (!configResolver.isEnabled()) {
            throw invalid("OpenMetadata integration is disabled");
        }
        if (dataSourceId == null || dataSourceId <= 0) {
            throw invalid("dataSourceId");
        }
        DataSource source = dataSourceDao.queryById(dataSourceId);
        if (source == null || source.getStatus() == DataSourceLifecycleStatus.REVOKED) {
            throw invalid("data source is unavailable");
        }
        MetadataConnectorAdapter adapter = connectorRegistry.find(source.getDbType())
                .orElseThrow(() -> new MetadataIntegrationException(
                        MetadataErrorCode.CONNECTOR_NOT_SUPPORTED,
                        "OpenMetadata connector is not enabled for this data source type"));
        if (adapter.resourceTypes().isEmpty()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.CONNECTOR_NOT_SUPPORTED,
                    "OpenMetadata non-relational exploration is not supported for this data source type");
        }
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null
                || binding.getDesiredState() != MetadataDesiredState.ACTIVE
                || binding.getSyncStatus() != MetadataSyncStatus.READY) {
            throw invalid("metadata synchronization is not ready");
        }
        String serviceFqn = binding.getOmServiceFqn();
        if (isBlank(serviceFqn)) {
            serviceFqn = MetadataStableName.serviceFqn(dataSourceId);
        }
        openMetadataClient.assertFixedVersion();
        return new AssetContext(serviceFqn, adapter, binding);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ServiceException invalid(String reason) {
        return new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, reason);
    }

    private record AssetContext(
            String serviceFqn,
            MetadataConnectorAdapter adapter,
            MetadataSourceBinding binding) {
    }

    private record LevelResult(List<OpenMetadataAsset> assets, boolean truncated) {
    }
}
