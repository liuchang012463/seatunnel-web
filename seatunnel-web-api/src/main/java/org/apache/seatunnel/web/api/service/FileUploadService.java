package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.vo.FileUploadAssetVO;
import org.apache.seatunnel.web.spi.bean.vo.FileUploadSessionVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface FileUploadService {

    FileUploadSessionVO ensureSession(Long jobDefinitionId);

    FileUploadSessionVO getSession(String sessionId);

    List<FileUploadAssetVO> upload(
            String sessionId, MultipartFile[] files, String[] relativePaths);

    boolean deleteAsset(String sessionId, Long assetId);

    /** Validates and enriches a WEB_UPLOAD source before it is built or saved. */
    void validateWorkflow(Long jobDefinitionId, Map<String, Object> workflow);

    void attach(Long jobDefinitionId);

    void deleteByJobDefinitionId(Long jobDefinitionId);
}
