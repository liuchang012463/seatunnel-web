package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.service.FileUploadService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.FileUploadAssetVO;
import org.apache.seatunnel.web.spi.bean.vo.FileUploadSessionVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/file-upload")
@Tag(name = "FILE_UPLOAD_TAG")
public class FileUploadController {

    @Resource
    private FileUploadService fileUploadService;

    @PostMapping("/sessions/{jobDefinitionId}")
    @Operation(summary = "ensureFileUploadSession", description = "为文件引接任务准备 Web 上传会话")
    public Result<FileUploadSessionVO> ensureSession(
            @PathVariable("jobDefinitionId") Long jobDefinitionId) {
        return Result.buildSuc(fileUploadService.ensureSession(jobDefinitionId));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "getFileUploadSession", description = "查询 Web 文件上传会话")
    public Result<FileUploadSessionVO> getSession(@PathVariable("sessionId") String sessionId) {
        return Result.buildSuc(fileUploadService.getSession(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/assets")
    @Operation(summary = "uploadFileUploadAssets", description = "上传文件或文件夹资产到平台 MinIO")
    public Result<List<FileUploadAssetVO>> upload(
            @PathVariable("sessionId") String sessionId,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "relativePaths", required = false) String[] relativePaths) {
        return Result.buildSuc(fileUploadService.upload(sessionId, files, relativePaths));
    }

    @DeleteMapping("/sessions/{sessionId}/assets/{assetId}")
    @Operation(summary = "deleteFileUploadAsset", description = "删除 Web 文件上传资产")
    public Result<Boolean> deleteAsset(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("assetId") Long assetId) {
        return Result.buildSuc(fileUploadService.deleteAsset(sessionId, assetId));
    }
}
