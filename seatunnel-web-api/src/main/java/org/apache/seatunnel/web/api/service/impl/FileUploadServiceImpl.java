package org.apache.seatunnel.web.api.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.s3.client.S3ObjectStorageClient;
import org.apache.seatunnel.plugin.datasource.s3.param.MinioConnectionParam;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.FileUploadService;
import org.apache.seatunnel.web.common.utils.CodeGenerateUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.fileupload.BuiltInMinioProperties;
import org.apache.seatunnel.web.dao.entity.FileUploadAsset;
import org.apache.seatunnel.web.dao.entity.FileUploadSession;
import org.apache.seatunnel.web.dao.repository.FileUploadAssetDao;
import org.apache.seatunnel.web.dao.repository.FileUploadSessionDao;
import org.apache.seatunnel.web.spi.bean.vo.FileUploadAssetVO;
import org.apache.seatunnel.web.spi.bean.vo.FileUploadSessionVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns browser-uploaded objects independently from registered user datasources.
 * The upload session is control-plane metadata; the actual bytes live in the
 * platform MinIO bucket and are consumed by the built-in S3File source.
 */
@Slf4j
@Service
public class FileUploadServiceImpl implements FileUploadService {

    private static final String SOURCE_NODE_TYPE = "source";
    private static final String WEB_UPLOAD = "WEB_UPLOAD";
    private static final String READY = "READY";
    private static final String DRAFT = "DRAFT";
    private static final String ATTACHED = "ATTACHED";
    private static final String DELETED = "DELETED";
    private static final int MAX_ASSETS_PER_REQUEST = 1000;
    private static final int MAX_RELATIVE_PATH_LENGTH = 1024;

    @Resource
    private FileUploadSessionDao fileUploadSessionDao;

    @Resource
    private FileUploadAssetDao fileUploadAssetDao;

    @Resource
    private CurrentUserProvider currentUserProvider;

    @Resource
    private BuiltInMinioProperties builtInMinioProperties;

    private final S3ObjectStorageClient objectStorageClient = new S3ObjectStorageClient();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileUploadSessionVO ensureSession(Long jobDefinitionId) {
        requireJobDefinitionId(jobDefinitionId);
        Integer ownerId = currentUserProvider.getCurrentUserId();
        FileUploadSession session = fileUploadSessionDao.queryByJobDefinitionId(jobDefinitionId);

        if (session == null) {
            session = new FileUploadSession();
            session.setId(newSessionId());
            session.setJobDefinitionId(jobDefinitionId);
            session.setOwnerId(ownerId);
            session.setObjectPrefix(
                    builtInMinioProperties.objectKeyPrefix(jobDefinitionId, session.getId()));
            session.setStatus(DRAFT);
            session.setCreateTime(new Date());
            session.setUpdateTime(new Date());
            session.setExpiresAt(expireAt());
            fileUploadSessionDao.insert(session);
        } else {
            requireOwner(session, ownerId);
            if (DELETED.equalsIgnoreCase(session.getStatus())) {
                session.setStatus(DRAFT);
            }
            session.setExpiresAt(expireAt());
            session.setUpdateTime(new Date());
            fileUploadSessionDao.updateById(session);
        }

        return toVO(session);
    }

    @Override
    public FileUploadSessionVO getSession(String sessionId) {
        FileUploadSession session = getAuthorizedSession(sessionId);
        return toVO(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<FileUploadAssetVO> upload(
            String sessionId, MultipartFile[] files, String[] relativePaths) {
        FileUploadSession session = getAuthorizedSession(sessionId);
        validateUploadSession(session);
        if (files == null || files.length == 0) {
            throw invalid("files");
        }
        if (files.length > MAX_ASSETS_PER_REQUEST) {
            throw invalid("上传文件数量不能超过 " + MAX_ASSETS_PER_REQUEST);
        }

        MinioConnectionParam connectionParam = writeConnectionParam();
        objectStorageClient.ensureBucket(connectionParam);
        List<FileUploadAssetVO> uploaded = new ArrayList<>();
        for (int index = 0; index < files.length; index++) {
            MultipartFile file = files[index];
            if (file == null) {
                continue;
            }

            String requestedPath = relativePaths != null && index < relativePaths.length
                    ? relativePaths[index]
                    : null;
            String relativePath = normalizeRelativePath(
                    StringUtils.defaultIfBlank(requestedPath, file.getOriginalFilename()));
            String objectKey = session.getObjectPrefix() + "/" + relativePath;
            try (InputStream input = file.getInputStream()) {
                String etag = objectStorageClient.putObject(
                        connectionParam,
                        objectKey,
                        input,
                        file.getSize(),
                        file.getContentType());
                FileUploadAsset asset = fileUploadAssetDao
                        .queryBySessionIdAndRelativePath(session.getId(), relativePath);
                if (asset == null) {
                    asset = new FileUploadAsset();
                    asset.setId(CodeGenerateUtils.getInstance().genCode());
                    asset.setSessionId(session.getId());
                    asset.setRelativePath(relativePath);
                    asset.setCreateTime(new Date());
                }
                asset.setObjectKey(objectKey);
                asset.setOriginalName(lastPathSegment(relativePath));
                asset.setSize(file.getSize());
                asset.setEtag(etag);
                asset.setContentType(file.getContentType());
                asset.setStatus(READY);
                asset.setUpdateTime(new Date());
                if (asset.getCreateTime() == null) {
                    asset.setCreateTime(new Date());
                }
                if (asset.getId() == null) {
                    fileUploadAssetDao.insert(asset);
                } else if (fileUploadAssetDao.queryById(asset.getId()) == null) {
                    fileUploadAssetDao.insert(asset);
                } else {
                    fileUploadAssetDao.updateById(asset);
                }
                uploaded.add(toVO(asset));
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to upload browser file, sessionId={}, relativePath={}",
                        sessionId, relativePath, e);
                throw new ServiceException(Status.DATASOURCE_METADATA_ERROR,
                        "文件上传失败: " + StringUtils.defaultIfBlank(e.getMessage(), "MinIO 不可用"));
            }
        }

        if (uploaded.isEmpty()) {
            throw invalid("至少选择一个文件");
        }
        session.setExpiresAt(expireAt());
        session.setUpdateTime(new Date());
        fileUploadSessionDao.updateById(session);
        return uploaded;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteAsset(String sessionId, Long assetId) {
        FileUploadSession session = getAuthorizedSession(sessionId);
        validateUploadSession(session);
        if (assetId == null) {
            throw invalid("assetId");
        }

        FileUploadAsset asset = fileUploadAssetDao.queryById(assetId);
        if (asset == null || !session.getId().equals(asset.getSessionId())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "文件不存在");
        }

        objectStorageClient.deleteObjects(writeConnectionParam(), List.of(asset.getObjectKey()));
        asset.setStatus(DELETED);
        asset.setUpdateTime(new Date());
        fileUploadAssetDao.updateById(asset);
        return true;
    }

    @Override
    public void validateWorkflow(Long jobDefinitionId, Map<String, Object> workflow) {
        Map<String, Object> source = findNodeConfig(workflow, SOURCE_NODE_TYPE);
        if (source.isEmpty() || !WEB_UPLOAD.equalsIgnoreCase(value(source, "sourceMode"))) {
            return;
        }

        requireJobDefinitionId(jobDefinitionId);
        source.put("jobDefinitionId", String.valueOf(jobDefinitionId));
        String sessionId = value(source, "uploadSessionId");
        if (StringUtils.isBlank(sessionId)) {
            throw invalid("请先上传至少一个文件");
        }

        FileUploadSession session = getAuthorizedSession(sessionId);
        if (!jobDefinitionId.equals(session.getJobDefinitionId())) {
            throw invalid("上传会话与当前任务不匹配");
        }
        validateUploadSession(session);
        boolean hasAsset = fileUploadAssetDao.queryBySessionId(sessionId).stream()
                .anyMatch(asset -> READY.equalsIgnoreCase(asset.getStatus()));
        if (!hasAsset) {
            throw invalid("请先上传至少一个文件");
        }
        source.put("dbType", "MINIO");
        source.put("pluginName", "S3File");
        source.put("connectorType", "S3File");
        source.put("syncType", "FULL");
        source.put("binaryChunkSize", 1048576);
        source.put("binaryCompleteFileMode", false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void attach(Long jobDefinitionId) {
        FileUploadSession session = fileUploadSessionDao.queryByJobDefinitionId(jobDefinitionId);
        if (session == null) {
            return;
        }
        requireOwner(session, currentUserProvider.getCurrentUserId());
        if (!DELETED.equalsIgnoreCase(session.getStatus())) {
            session.setStatus(ATTACHED);
            session.setUpdateTime(new Date());
            fileUploadSessionDao.updateById(session);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByJobDefinitionId(Long jobDefinitionId) {
        if (jobDefinitionId == null) {
            return;
        }
        FileUploadSession session = fileUploadSessionDao.queryByJobDefinitionId(jobDefinitionId);
        if (session == null) {
            return;
        }
        try {
            objectStorageClient.deletePrefix(writeConnectionParam(), session.getObjectPrefix());
        } catch (Exception e) {
            log.warn("Failed to remove uploaded objects for deleted job, jobDefinitionId={}",
                    jobDefinitionId, e);
        }
        fileUploadAssetDao.deleteBySessionId(session.getId());
        session.setStatus(DELETED);
        session.setUpdateTime(new Date());
        fileUploadSessionDao.updateById(session);
    }

    private FileUploadSession getAuthorizedSession(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw invalid("sessionId");
        }
        FileUploadSession session = fileUploadSessionDao.queryById(sessionId);
        if (session == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "上传会话不存在");
        }
        requireOwner(session, currentUserProvider.getCurrentUserId());
        return session;
    }

    private void validateUploadSession(FileUploadSession session) {
        if (DELETED.equalsIgnoreCase(session.getStatus())) {
            throw invalid("上传会话已删除");
        }
        if (DRAFT.equalsIgnoreCase(session.getStatus())
                && session.getExpiresAt() != null
                && session.getExpiresAt().before(new Date())) {
            throw invalid("上传会话已过期，请重新进入任务配置");
        }
    }

    private void requireOwner(FileUploadSession session, Integer ownerId) {
        if (ownerId == null || !ownerId.equals(session.getOwnerId())) {
            throw new ServiceException(Status.LOGIN_SESSION_FAILED);
        }
    }

    private MinioConnectionParam writeConnectionParam() {
        String endpoint = requireStorageProperty(
                builtInMinioProperties.getEndpoint(),
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_ENDPOINT");
        String accessKey = requireStorageProperty(
                builtInMinioProperties.getAccessKey(),
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_ACCESS_KEY");
        String secretKey = requireStorageProperty(
                builtInMinioProperties.getSecretKey(),
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_SECRET_KEY");

        MinioConnectionParam param = new MinioConnectionParam();
        param.setEndpoint(endpoint);
        param.setBucket(requireStorageProperty(
                builtInMinioProperties.getBucket(),
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_BUCKET"));
        param.setBasePath("/");
        param.setAccessKey(accessKey);
        param.setSecretKey(secretKey);
        return param;
    }

    private FileUploadSessionVO toVO(FileUploadSession session) {
        FileUploadSessionVO vo = new FileUploadSessionVO();
        vo.setId(session.getId());
        vo.setJobDefinitionId(session.getJobDefinitionId());
        vo.setStatus(session.getStatus());
        vo.setAssets(fileUploadAssetDao.queryBySessionId(session.getId()).stream()
                .filter(asset -> READY.equalsIgnoreCase(asset.getStatus()))
                .map(this::toVO)
                .toList());
        return vo;
    }

    private FileUploadAssetVO toVO(FileUploadAsset asset) {
        FileUploadAssetVO vo = new FileUploadAssetVO();
        vo.setId(asset.getId());
        vo.setRelativePath(asset.getRelativePath());
        vo.setOriginalName(asset.getOriginalName());
        vo.setSize(asset.getSize());
        vo.setContentType(asset.getContentType());
        vo.setStatus(asset.getStatus());
        return vo;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findNodeConfig(Map<String, Object> workflow, String nodeType) {
        if (workflow == null) {
            return Map.of();
        }
        Object rawNodes = workflow.get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) {
            return Map.of();
        }
        for (Object rawNode : nodes) {
            if (!(rawNode instanceof Map<?, ?> node)) {
                continue;
            }
            Object rawData = node.get("data");
            if (!(rawData instanceof Map<?, ?> data)
                    || !nodeType.equalsIgnoreCase(String.valueOf(data.get("nodeType")))) {
                continue;
            }
            Object rawConfig = data.get("config");
            if (rawConfig instanceof Map<?, ?> config) {
                return (Map<String, Object>) config;
            }
            return (Map<String, Object>) data;
        }
        return Map.of();
    }

    private String value(Map<String, Object> map, String key) {
        return map == null ? "" : StringUtils.trimToEmpty(String.valueOf(map.getOrDefault(key, "")));
    }

    private String normalizeRelativePath(String path) {
        String normalized = StringUtils.trimToEmpty(path).replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank() || normalized.startsWith("/")
                || normalized.contains("\u0000") || normalized.length() > MAX_RELATIVE_PATH_LENGTH) {
            throw invalid("文件相对路径不合法");
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("文件相对路径不合法");
            }
        }
        return normalized;
    }

    private String lastPathSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private Date expireAt() {
        return new Date(System.currentTimeMillis()
                + builtInMinioProperties.getSessionTtlHours() * 60L * 60L * 1000L);
    }

    private String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void requireJobDefinitionId(Long jobDefinitionId) {
        if (jobDefinitionId == null || jobDefinitionId <= 0) {
            throw invalid("jobDefinitionId");
        }
    }

    private String requireStorageProperty(String value, String propertyName) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException(Status.DATASOURCE_METADATA_ERROR,
                    "缺少内置 MinIO 配置: " + propertyName);
        }
        return value.trim();
    }

    private ServiceException invalid(String message) {
        return new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, message);
    }
}
