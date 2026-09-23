package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.seatunnel.web.api.fileresource.FileResourceMqNotifier;
import org.apache.seatunnel.web.api.fileresource.FileResourcePathUtils;
import org.apache.seatunnel.web.api.fileresource.FileResourceReferenceChecker;
import org.apache.seatunnel.web.api.fileresource.storage.FileResourceStorageProvider;
import org.apache.seatunnel.web.api.fileresource.storage.StorageObjectMetadata;
import org.apache.seatunnel.web.api.service.FileResourceService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.fileresource.FileResourceReference;
import org.apache.seatunnel.web.core.fileresource.FileResourceResolver;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.apache.seatunnel.web.dao.entity.FileUploadRecord;
import org.apache.seatunnel.web.dao.repository.FileResourceDao;
import org.apache.seatunnel.web.dao.repository.FileUploadRecordDao;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceDirectoryDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourcePreviewDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceUploadRecordQueryDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceVO;
import org.apache.seatunnel.web.spi.bean.vo.FileUploadRecordVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Default file resource application service.
 *
 * <p>Only object keys are persisted as storage locations.  Multipart files are
 * streamed to the provider and their web-server temporary paths never enter a
 * task or resource record.</p>
 */
@Slf4j
@Service
public class FileResourceServiceImpl implements FileResourceService, FileResourceResolver {

    private static final String FILE = "FILE";
    private static final String DIRECTORY = "DIRECTORY";
    private static final String READY = "READY";
    private static final String DELETED = "DELETED";
    private static final String UPLOADING = "UPLOADING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final int MAX_FILES_PER_REQUEST = 1000;
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final int DEFAULT_PREVIEW_ROWS = 20;
    private static final int MAX_PREVIEW_ROWS = 100;
    private static final int MAX_PREVIEW_COLUMNS = 200;
    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;

    @Resource
    private FileResourceDao fileResourceDao;

    @Resource
    private FileUploadRecordDao fileUploadRecordDao;

    @Resource
    private FileUploadRecordPersistenceService fileUploadRecordPersistenceService;

    @Resource
    private CurrentUserProvider currentUserProvider;

    @Resource
    private FileResourceStorageProvider storageProvider;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ObjectProvider<FileResourceReferenceChecker> referenceCheckers;

    @Resource
    private FileResourceMqNotifier fileResourceMqNotifier;

    @Override
    public List<FileResourceVO> list(String path) {
        Integer ownerId = currentUserId();
        String normalizedPath = FileResourcePathUtils.normalizePath(path);
        return fileResourceDao.queryActiveByOwnerId(ownerId).stream()
                .filter(resource -> isDirectChild(resource.getLogicalPath(), normalizedPath))
                .map(this::toVO)
                .sorted(Comparator.comparing(
                                (FileResourceVO resource) ->
                                        DIRECTORY.equalsIgnoreCase(resource.getResourceType()) ? 0 : 1)
                        .thenComparing(FileResourceVO::getName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(FileResourceVO::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public FileResourceVO get(Long id) {
        return toVO(requireActiveResource(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileResourceVO createDirectory(FileResourceDirectoryDTO request) {
        if (request == null || StringUtils.isBlank(request.getPath())) {
            throw invalid("path");
        }
        Integer ownerId = currentUserId();
        String path = FileResourcePathUtils.normalizePath(request.getPath());
        if ("/".equals(path)) {
            throw invalid("根目录不能重复创建");
        }

        FileResource existing = fileResourceDao.queryByOwnerAndLogicalPath(ownerId, path);
        if (isActive(existing)) {
            if (DIRECTORY.equalsIgnoreCase(existing.getResourceType())) {
                return toVO(existing);
            }
            throw invalid("目录路径已被文件占用");
        }

        storageProvider.ensureBucket();
        ensureParentDirectories(ownerId, path, new ArrayList<>());
        String objectKey = storageProvider.objectKey(path);
        storageProvider.createDirectory(objectKey);
        try {
            return toVO(saveResource(
                    existing,
                    ownerId,
                    path,
                    objectKey,
                    DIRECTORY,
                    0L,
                    "application/x-directory",
                    null));
        } catch (DuplicateKeyException e) {
            throw invalid("目录已存在");
        }
    }

    @Override
    public List<FileResourceVO> upload(
            String path, MultipartFile[] files, String[] relativePaths) {
        Integer ownerId = currentUserId();
        String targetPath = FileResourcePathUtils.normalizePath(path);
        List<UploadItem> items = normalizeUploadItems(targetPath, files, relativePaths);
        validateUploadItems(ownerId, items);

        FileUploadRecord record = new FileUploadRecord();
        record.initInsert();
        record.setOwnerId(ownerId);
        record.setProviderType(storageProvider.providerType());
        record.setBucket(storageProvider.bucket());
        record.setTargetPath(targetPath);
        record.setTotalFiles(items.size());
        record.setTotalSize(totalSize(items));
        record.setStatus(UPLOADING);
        fileUploadRecordPersistenceService.insert(record);

        List<String> newObjectKeys = new ArrayList<>();
        List<ResourceMutation> mutations = new ArrayList<>();
        try {
            storageProvider.ensureBucket();
            for (UploadItem item : items) {
                ensureParentDirectories(ownerId, item.logicalPath(), mutations);
                String objectKey = storageProvider.objectKey(item.logicalPath());
                FileResource existing = fileResourceDao.queryByOwnerAndLogicalPath(
                        ownerId, item.logicalPath());
                String previousStatus = existing == null ? null : existing.getStatus();
                if (!isActive(existing)) {
                    newObjectKeys.add(objectKey);
                }
                String etag;
                try (var input = item.file().getInputStream()) {
                    etag = storageProvider.upload(
                            objectKey,
                            input,
                            Math.max(item.file().getSize(), 0L),
                            contentType(item.file().getContentType()));
                }
                FileResource resource = saveResource(
                        existing,
                        ownerId,
                        item.logicalPath(),
                        objectKey,
                        FILE,
                        Math.max(item.file().getSize(), 0L),
                        contentType(item.file().getContentType()),
                        etag);
                if (existing == null || DELETED.equalsIgnoreCase(previousStatus)) {
                    mutations.add(new ResourceMutation(resource.getId(),
                            previousStatus));
                }
                fileResourceMqNotifier.notifyUploaded(resource, record.getId());
            }
            markUploadRecord(record, SUCCESS, null);
            return items.stream()
                    .map(item -> fileResourceDao.queryByOwnerAndLogicalPath(ownerId, item.logicalPath()))
                    .filter(resource -> resource != null && isActive(resource))
                    .map(this::toVO)
                    .toList();
        } catch (Exception e) {
            cleanupFailedUpload(newObjectKeys, mutations);
            String message = StringUtils.defaultIfBlank(e.getMessage(), "对象存储不可用");
            markFailedUploadRecord(record, message);
            if (e instanceof ServiceException serviceException) {
                throw serviceException;
            }
            log.error("Upload file resources failed, recordId={}, targetPath={}",
                    record.getId(), targetPath, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, "文件上传失败: " + message);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        FileResource resource = requireActiveResource(id);
        List<FileResource> targets = fileResourceDao.queryActiveByOwnerId(resource.getOwnerId()).stream()
                .filter(candidate -> DIRECTORY.equalsIgnoreCase(resource.getResourceType())
                        ? FileResourcePathUtils.isSameOrDescendant(
                                candidate.getLogicalPath(), resource.getLogicalPath())
                        : candidate.getId().equals(resource.getId()))
                .toList();
        if (targets.stream().anyMatch(candidate -> isReferenced(candidate.getId()))) {
            throw invalid("文件资源仍被任务引用，不能删除");
        }

        if (DIRECTORY.equalsIgnoreCase(resource.getResourceType())) {
            storageProvider.deletePrefix(resource.getObjectKey());
        } else {
            storageProvider.delete(resource.getObjectKey());
        }
        Date now = new Date();
        for (FileResource target : targets) {
            target.setStatus(DELETED);
            target.setUpdateTime(now);
            fileResourceDao.updateById(target);
        }
    }

    @Override
    public void download(Long id, OutputStream output) throws IOException {
        if (output == null) {
            throw invalid("output");
        }
        FileResource resource = requireActiveResource(id);
        if (!FILE.equalsIgnoreCase(resource.getResourceType())) {
            throw invalid("只能下载文件资源");
        }
        try {
            storageProvider.download(resource.getObjectKey(), output);
        } catch (ServiceException e) {
            throw e;
        } catch (IOException e) {
            log.error("Download file resource failed, resourceId={}", id, e);
            throw e;
        } catch (Exception e) {
            log.error("Download file resource failed, resourceId={}", id, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, "文件下载失败: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> preview(Long id, FileResourcePreviewDTO request) {
        FileResource resource = requireActiveResource(id);
        if (!FILE.equalsIgnoreCase(resource.getResourceType())) {
            throw invalid("只能预览文件资源");
        }

        FileResourcePreviewDTO options = request == null
                ? new FileResourcePreviewDTO() : request;
        String format = resolvePreviewFormat(resource, options.getFileFormatType());
        int limit = options.getLimit() == null
                ? DEFAULT_PREVIEW_ROWS
                : Math.min(Math.max(options.getLimit(), 1), MAX_PREVIEW_ROWS);
        StorageObjectMetadata metadata;
        try {
            metadata = storageProvider.head(resource.getObjectKey());
            if (metadata == null || metadata.size() == null) {
                throw new IllegalStateException("无法读取文件大小");
            }
            if (metadata.size() > MAX_PREVIEW_BYTES) {
                throw invalid("预览文件不能超过 " + (MAX_PREVIEW_BYTES / 1024 / 1024) + " MB");
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.max(0, Math.min(metadata.size().intValue(), MAX_PREVIEW_BYTES)));
            storageProvider.download(resource.getObjectKey(), output);
            byte[] bytes = output.toByteArray();
            PreviewData data = switch (format) {
                case "csv" -> previewDelimited(
                        new String(bytes, resolveCharset(options.getEncoding())),
                        decodeDelimiter(options.getFieldDelimiter(), ','), limit);
                case "text" -> previewText(
                        new String(bytes, resolveCharset(options.getEncoding())), limit);
                case "json" -> previewJson(bytes, resolveCharset(options.getEncoding()), limit);
                case "excel" -> previewExcel(bytes, options.getSheetName(), limit);
                default -> throw invalid("不支持的预览格式: " + format);
            };

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("format", format);
            result.put("columns", data.columns());
            result.put("rows", data.rows());
            result.put("truncated", data.truncated());
            return result;
        } catch (ServiceException e) {
            throw e;
        } catch (IOException e) {
            log.error("Preview file resource failed, resourceId={}", id, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR, "文件预览失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Preview file resource failed, resourceId={}", id, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR,
                    "文件预览失败: " + StringUtils.defaultIfBlank(e.getMessage(), "文件格式不正确"));
        }
    }

    @Override
    public PaginationResult<FileUploadRecordVO> pageUploadRecords(
            FileResourceUploadRecordQueryDTO query) {
        FileResourceUploadRecordQueryDTO normalized = query == null
                ? new FileResourceUploadRecordQueryDTO() : query;
        IPage<FileUploadRecord> page = fileUploadRecordDao.queryPage(currentUserId(), normalized);
        return PaginationResult.buildSuc(
                page.getRecords().stream().map(this::toVO).toList(), page);
    }

    /**
     * Resolves only active files visible to the authenticated owner.  The
     * storage HEAD check catches metadata rows whose backing object disappeared.
     */
    @Override
    public FileResourceReference resolve(Long resourceId) {
        FileResource resource = requireActiveResource(resourceId);
        if (!FILE.equalsIgnoreCase(resource.getResourceType())) {
            throw invalid("资源不是文件");
        }
        try {
            StorageObjectMetadata metadata = storageProvider.head(resource.getObjectKey());
            if (metadata == null) {
                throw new IllegalStateException("对象不存在");
            }
            return storageProvider.executionReference(resource.getObjectKey());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Resolve file resource failed, resourceId={}", resourceId, e);
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR,
                    "文件资源对象不存在或不可访问: " + StringUtils.defaultIfBlank(e.getMessage(), "unknown"));
        }
    }

    private List<UploadItem> normalizeUploadItems(
            String targetPath, MultipartFile[] files, String[] relativePaths) {
        if (files == null || files.length == 0) {
            throw invalid("files");
        }
        if (files.length > MAX_FILES_PER_REQUEST) {
            throw invalid("单次上传文件数量不能超过 " + MAX_FILES_PER_REQUEST);
        }
        List<UploadItem> items = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < files.length; index++) {
            MultipartFile file = files[index];
            if (file == null) {
                continue;
            }
            String requestedPath = relativePaths != null && index < relativePaths.length
                    ? relativePaths[index] : file.getOriginalFilename();
            String relativePath = FileResourcePathUtils.normalizeRelativePath(requestedPath);
            String logicalPath = FileResourcePathUtils.join(targetPath, relativePath);
            if (!paths.add(logicalPath)) {
                throw invalid("上传文件路径重复: " + logicalPath);
            }
            items.add(new UploadItem(file, logicalPath));
        }
        if (items.isEmpty()) {
            throw invalid("至少选择一个文件");
        }
        return items;
    }

    private void validateUploadItems(Integer ownerId, List<UploadItem> items) {
        Set<String> filePaths = items.stream().map(UploadItem::logicalPath).collect(java.util.stream.Collectors.toSet());
        for (UploadItem item : items) {
            FileResource existing = fileResourceDao.queryByOwnerAndLogicalPath(ownerId, item.logicalPath());
            if (isActive(existing)) {
                throw invalid("文件资源已存在: " + item.logicalPath());
            }
            String parent = FileResourcePathUtils.parent(item.logicalPath());
            while (!"/".equals(parent)) {
                if (filePaths.contains(parent)) {
                    throw invalid("文件路径与目录冲突: " + parent);
                }
                FileResource parentResource = fileResourceDao.queryByOwnerAndLogicalPath(ownerId, parent);
                if (isActive(parentResource) && FILE.equalsIgnoreCase(parentResource.getResourceType())) {
                    throw invalid("文件路径与已有文件冲突: " + parent);
                }
                parent = FileResourcePathUtils.parent(parent);
            }
        }
    }

    private long totalSize(List<UploadItem> items) {
        long total = 0L;
        try {
            for (UploadItem item : items) {
                total = Math.addExact(total, Math.max(item.file().getSize(), 0L));
            }
            return total;
        } catch (ArithmeticException e) {
            throw invalid("上传文件总大小过大");
        }
    }

    private void ensureParentDirectories(
            Integer ownerId, String path, List<ResourceMutation> mutations) {
        String parent = FileResourcePathUtils.parent(path);
        List<String> parents = new ArrayList<>();
        while (!"/".equals(parent)) {
            parents.add(parent);
            parent = FileResourcePathUtils.parent(parent);
        }
        java.util.Collections.reverse(parents);
        for (String directory : parents) {
            FileResource existing = fileResourceDao.queryByOwnerAndLogicalPath(ownerId, directory);
            if (isActive(existing)) {
                if (FILE.equalsIgnoreCase(existing.getResourceType())) {
                    throw invalid("目录路径已被文件占用: " + directory);
                }
                continue;
            }
            String previousStatus = existing == null ? null : existing.getStatus();
            String objectKey = storageProvider.objectKey(directory);
            try {
                storageProvider.createDirectory(objectKey);
                FileResource saved = saveResource(
                        existing,
                        ownerId,
                        directory,
                        objectKey,
                        DIRECTORY,
                        0L,
                        "application/x-directory",
                        null);
                mutations.add(new ResourceMutation(saved.getId(),
                        previousStatus));
            } catch (DuplicateKeyException duplicate) {
                // Directory uploads can issue one request per file.  Another
                // request may have created this shared parent after our
                // initial lookup; treat that committed directory as success.
                FileResource concurrent = fileResourceDao.queryByOwnerAndLogicalPath(
                        ownerId, directory);
                if (!isActive(concurrent)
                        || !DIRECTORY.equalsIgnoreCase(concurrent.getResourceType())) {
                    throw duplicate;
                }
            }
        }
    }

    private FileResource saveResource(
            FileResource existing,
            Integer ownerId,
            String logicalPath,
            String objectKey,
            String resourceType,
            Long size,
            String contentType,
            String etag) {
        FileResource resource = existing == null ? new FileResource() : existing;
        if (existing == null) {
            resource.initInsert();
            resource.setOwnerId(ownerId);
        }
        resource.setProviderType(storageProvider.providerType());
        resource.setBucket(storageProvider.bucket());
        resource.setObjectKey(objectKey);
        resource.setLogicalPath(logicalPath);
        resource.setLogicalPathHash(hash(logicalPath));
        resource.setName(FileResourcePathUtils.name(logicalPath));
        resource.setResourceType(resourceType);
        resource.setSize(size == null ? 0L : size);
        resource.setContentType(contentType);
        resource.setEtag(etag);
        resource.setStatus(READY);
        resource.setUpdateTime(new Date());
        if (existing == null) {
            fileResourceDao.insert(resource);
        } else {
            fileResourceDao.updateById(resource);
        }
        return resource;
    }

    private FileResource requireActiveResource(Long id) {
        if (id == null || id <= 0) {
            throw invalid("id");
        }
        FileResource resource = fileResourceDao.queryById(id);
        if (resource == null || !currentUserId().equals(resource.getOwnerId())
                || !READY.equalsIgnoreCase(resource.getStatus())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "文件资源不存在");
        }
        return resource;
    }

    private boolean isActive(FileResource resource) {
        return resource != null && READY.equalsIgnoreCase(resource.getStatus());
    }

    private boolean isReferenced(Long resourceId) {
        return referenceCheckers != null
                && referenceCheckers.orderedStream().anyMatch(checker -> checker.isReferenced(resourceId));
    }

    private boolean isDirectChild(String logicalPath, String parentPath) {
        if (logicalPath == null || parentPath == null) {
            return false;
        }
        String normalized = FileResourcePathUtils.normalizePath(logicalPath);
        if (normalized.equals(parentPath)) {
            return false;
        }
        String remainder = "/".equals(parentPath)
                ? normalized.substring(1)
                : normalized.startsWith(parentPath + "/")
                ? normalized.substring(parentPath.length() + 1) : "";
        return !remainder.isBlank() && !remainder.contains("/");
    }

    private void cleanupFailedUpload(
            List<String> newObjectKeys, List<ResourceMutation> mutations) {
        for (String objectKey : newObjectKeys) {
            try {
                storageProvider.delete(objectKey);
            } catch (Exception cleanupError) {
                log.warn("Failed to clean up file resource object, objectKey={}", objectKey, cleanupError);
            }
        }
        for (int index = mutations.size() - 1; index >= 0; index--) {
            ResourceMutation mutation = mutations.get(index);
            FileResource resource = fileResourceDao.queryById(mutation.resourceId());
            if (resource == null) {
                continue;
            }
            resource.setStatus(StringUtils.defaultIfBlank(mutation.previousStatus(), DELETED));
            resource.setUpdateTime(new Date());
            try {
                fileResourceDao.updateById(resource);
            } catch (Exception cleanupError) {
                log.warn("Failed to roll back file resource metadata, resourceId={}",
                        mutation.resourceId(), cleanupError);
            }
        }
    }

    private void markUploadRecord(FileUploadRecord record, String status, String errorMessage) {
        record.setStatus(status);
        record.setErrorMessage(truncate(errorMessage));
        record.setFinishTime(new Date());
        record.setUpdateTime(new Date());
        try {
            fileUploadRecordDao.updateById(record);
        } catch (Exception e) {
            log.warn("Failed to update file resource upload record, recordId={}", record.getId(), e);
        }
    }

    private void markFailedUploadRecord(FileUploadRecord record, String errorMessage) {
        try {
            fileUploadRecordPersistenceService.markFailed(record, truncate(errorMessage));
        } catch (Exception e) {
            log.warn("Failed to persist failed file resource upload record, recordId={}",
                    record.getId(), e);
        }
    }

    private String contentType(String contentType) {
        return StringUtils.defaultIfBlank(contentType, DEFAULT_CONTENT_TYPE);
    }

    private String resolvePreviewFormat(FileResource resource, String requestedFormat) {
        String value = StringUtils.trimToEmpty(requestedFormat).toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            String name = StringUtils.defaultString(resource.getName()).toLowerCase(Locale.ROOT);
            if (name.endsWith(".csv")) {
                value = "csv";
            } else if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
                value = "excel";
            } else if (name.endsWith(".json")) {
                value = "json";
            } else if (name.endsWith(".txt") || name.endsWith(".text")) {
                value = "text";
            }
        }
        if (!Set.of("csv", "excel", "json", "text").contains(value)) {
            throw invalid("预览格式必须是 csv、excel、json 或 text");
        }
        return value;
    }

    private Charset resolveCharset(String encoding) {
        String value = StringUtils.defaultIfBlank(encoding, StandardCharsets.UTF_8.name());
        try {
            return Charset.forName(value.trim());
        } catch (Exception e) {
            throw invalid("文件编码不支持: " + value);
        }
    }

    private char decodeDelimiter(String delimiter, char fallback) {
        String value = StringUtils.defaultIfBlank(delimiter, String.valueOf(fallback));
        if ("\\t".equals(value)) {
            return '\t';
        }
        if ("\\001".equals(value)) {
            return 1;
        }
        return value.charAt(0);
    }

    private PreviewData previewDelimited(String content, char delimiter, int limit) {
        List<List<String>> records = parseDelimitedRecords(content, delimiter, limit + 2);
        if (records.isEmpty()) {
            return new PreviewData(List.of(), List.of(), false);
        }

        List<String> columns = uniqueColumns(records.get(0));
        List<List<String>> rows = records.subList(1, records.size());
        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = rows.subList(0, limit);
        }
        return new PreviewData(columns, mapRows(columns, rows), truncated);
    }

    private PreviewData previewText(String content, int limit) {
        String normalized = content.startsWith("\uFEFF") ? content.substring(1) : content;
        String[] rawLines = normalized.split("\\R", -1);
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            if (!line.isEmpty() || lines.size() < rawLines.length - 1) {
                lines.add(line);
            }
        }
        boolean truncated = lines.size() > limit;
        if (truncated) {
            lines = new ArrayList<>(lines.subList(0, limit));
        }
        List<Map<String, Object>> rows = lines.stream()
                .map(line -> Map.<String, Object>of("value", line))
                .toList();
        return new PreviewData(List.of("value"), rows, truncated);
    }

    private PreviewData previewJson(byte[] bytes, Charset charset, int limit) throws IOException {
        String content = new String(bytes, charset).trim();
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1).trim();
        }
        ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        List<JsonNode> values = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(content);
            if (root != null && root.isArray()) {
                root.elements().forEachRemaining(values::add);
            } else if (root != null) {
                values.add(root);
            }
        } catch (JsonProcessingException e) {
            // JSON files used for ingestion are commonly newline-delimited.
            // Fall back to one JSON value per non-empty line without accepting
            // arbitrary executable or object-storage-side transformations.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(bytes), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        values.add(mapper.readTree(line));
                    }
                }
            } catch (JsonProcessingException ndjsonError) {
                throw new IllegalArgumentException("JSON 文件格式不正确", ndjsonError);
            }
        }

        boolean truncated = values.size() > limit;
        List<JsonNode> selected = truncated ? values.subList(0, limit) : values;
        LinkedHashMap<String, String> columnTypes = new LinkedHashMap<>();
        for (JsonNode value : selected) {
            if (value != null && value.isObject()) {
                value.fieldNames().forEachRemaining(name -> {
                    if (columnTypes.size() < MAX_PREVIEW_COLUMNS) {
                        columnTypes.putIfAbsent(name, name);
                    }
                });
            } else if (columnTypes.isEmpty()) {
                columnTypes.put("value", "value");
            }
        }
        List<String> columns = new ArrayList<>(columnTypes.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode value : selected) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            if (value != null && value.isObject()) {
                for (String column : columns) {
                    JsonNode item = value.get(column);
                    row.put(column, jsonValue(item));
                }
            } else {
                row.put("value", jsonValue(value));
            }
            rows.add(row);
        }
        return new PreviewData(columns, rows, truncated);
    }

    private PreviewData previewExcel(byte[] bytes, String sheetName, int limit) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = StringUtils.isBlank(sheetName)
                    ? workbook.getSheetAt(0) : workbook.getSheet(sheetName.trim());
            if (sheet == null) {
                throw new IllegalArgumentException("Excel 工作表不存在: " + sheetName);
            }
            DataFormatter formatter = new DataFormatter();
            List<List<String>> records = new ArrayList<>();
            int rowLimit = limit + 2;
            for (Row row : sheet) {
                if (records.size() >= rowLimit) {
                    break;
                }
                int lastCell = Math.min(Math.max(row.getLastCellNum(), 0), MAX_PREVIEW_COLUMNS);
                List<String> values = new ArrayList<>();
                boolean nonBlank = false;
                for (int index = 0; index < lastCell; index++) {
                    Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cell == null ? "" : formatter.formatCellValue(cell);
                    nonBlank |= !value.isBlank();
                    values.add(value);
                }
                if (nonBlank) {
                    records.add(values);
                }
            }
            if (records.isEmpty()) {
                return new PreviewData(List.of(), List.of(), false);
            }
            List<String> columns = uniqueColumns(records.get(0));
            List<List<String>> rows = records.subList(1, records.size());
            boolean truncated = rows.size() > limit;
            if (truncated) {
                rows = rows.subList(0, limit);
            }
            return new PreviewData(columns, mapRows(columns, rows), truncated);
        }
    }

    private List<List<String>> parseDelimitedRecords(String content, char delimiter, int maxRecords) {
        String normalized = content.startsWith("\uFEFF") ? content.substring(1) : content;
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean hasValue = false;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < normalized.length() && normalized.charAt(index + 1) == '"') {
                        cell.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(current);
                }
                hasValue = true;
            } else if (current == '"' && cell.isEmpty()) {
                quoted = true;
                hasValue = true;
            } else if (current == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
                hasValue = true;
            } else if (current == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                if (hasValue || row.size() > 1) {
                    records.add(row);
                    if (records.size() >= maxRecords) {
                        return records;
                    }
                }
                row = new ArrayList<>();
                hasValue = false;
            } else if (current != '\r') {
                cell.append(current);
                hasValue = true;
            }
        }
        if (hasValue || !row.isEmpty() || !cell.isEmpty()) {
            row.add(cell.toString());
            records.add(row);
        }
        return records;
    }

    private List<String> uniqueColumns(List<String> rawColumns) {
        LinkedHashMap<String, Integer> seen = new LinkedHashMap<>();
        List<String> columns = new ArrayList<>();
        int count = Math.min(rawColumns.size(), MAX_PREVIEW_COLUMNS);
        for (int index = 0; index < count; index++) {
            String base = StringUtils.defaultIfBlank(rawColumns.get(index), "field_" + (index + 1));
            int occurrence = seen.merge(base, 1, Integer::sum);
            columns.add(occurrence == 1 ? base : base + "_" + occurrence);
        }
        return columns;
    }

    private List<Map<String, Object>> mapRows(List<String> columns, List<List<String>> rawRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<String> rawRow : rawRows) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < columns.size(); index++) {
                row.put(columns.get(index), index < rawRow.size() ? rawRow.get(index) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private Object jsonValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private FileResourceVO toVO(FileResource resource) {
        FileResourceVO vo = new FileResourceVO();
        vo.setId(resource.getId());
        vo.setOwnerId(resource.getOwnerId());
        vo.setProviderType(resource.getProviderType());
        vo.setBucket(resource.getBucket());
        vo.setObjectKey(resource.getObjectKey());
        vo.setLogicalPath(resource.getLogicalPath());
        vo.setName(resource.getName());
        vo.setResourceType(resource.getResourceType());
        vo.setSize(resource.getSize());
        vo.setContentType(resource.getContentType());
        vo.setEtag(resource.getEtag());
        vo.setStatus(resource.getStatus());
        vo.setCreateTime(resource.getCreateTime());
        vo.setUpdateTime(resource.getUpdateTime());
        return vo;
    }

    private FileUploadRecordVO toVO(FileUploadRecord record) {
        FileUploadRecordVO vo = new FileUploadRecordVO();
        vo.setId(record.getId());
        vo.setOwnerId(record.getOwnerId());
        vo.setProviderType(record.getProviderType());
        vo.setBucket(record.getBucket());
        vo.setTargetPath(record.getTargetPath());
        vo.setTotalFiles(record.getTotalFiles());
        vo.setTotalSize(record.getTotalSize());
        vo.setStatus(record.getStatus());
        vo.setErrorMessage(record.getErrorMessage());
        vo.setCreateTime(record.getCreateTime());
        vo.setUpdateTime(record.getUpdateTime());
        vo.setFinishTime(record.getFinishTime());
        return vo;
    }

    private Integer currentUserId() {
        return currentUserProvider.getCurrentUserId();
    }

    private ServiceException invalid(String field) {
        return new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, field);
    }

    private record UploadItem(MultipartFile file, String logicalPath) {
    }

    private record ResourceMutation(Long resourceId, String previousStatus) {
    }

    private record PreviewData(
            List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
    }
}
