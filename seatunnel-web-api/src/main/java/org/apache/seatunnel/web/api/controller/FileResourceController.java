package org.apache.seatunnel.web.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.seatunnel.web.api.service.FileResourceService;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceDirectoryDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartCompleteRequestDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartPartsRequestDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartUploadRequestDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourcePreviewDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceUploadRecordQueryDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceVO;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceMultipartPartUrlVO;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceMultipartUploadVO;
import org.apache.seatunnel.web.spi.bean.vo.FileUploadRecordVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** HTTP API for the reusable file resource catalog. */
@RestController
@RequestMapping("/api/v1/file-resources")
@Tag(name = "FILE_RESOURCE_TAG")
public class FileResourceController {

    @Resource
    private FileResourceService fileResourceService;

    @GetMapping
    @Operation(summary = "listFileResources", description = "浏览文件资源目录的一级内容")
    public Result<List<FileResourceVO>> list(
            @RequestParam(value = "path", required = false) String path) {
        return Result.buildSuc(fileResourceService.list(path));
    }

    @GetMapping("/{id}")
    @Operation(summary = "getFileResource", description = "获取文件资源元数据")
    public Result<FileResourceVO> get(@PathVariable("id") Long id) {
        return Result.buildSuc(fileResourceService.get(id));
    }

    @PostMapping("/directories")
    @Operation(summary = "createFileResourceDirectory", description = "新建文件资源目录")
    public Result<FileResourceVO> createDirectory(@RequestBody FileResourceDirectoryDTO request) {
        return Result.buildSuc(fileResourceService.createDirectory(request));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "uploadFileResources", description = "上传文件或文件夹到文件资源库")
    public Result<List<FileResourceVO>> upload(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "relativePaths", required = false) String[] relativePaths) {
        return Result.buildSuc(fileResourceService.upload(path, files, relativePaths));
    }

    @PostMapping("/multipart-uploads")
    @Operation(summary = "initiateFileResourceMultipartUpload", description = "为大文件创建 MinIO 分片上传会话")
    public Result<FileResourceMultipartUploadVO> initiateMultipartUpload(
            @RequestBody FileResourceMultipartUploadRequestDTO request) {
        return Result.buildSuc(fileResourceService.initiateMultipartUpload(request));
    }

    @PostMapping("/multipart-uploads/{uploadRecordId}/parts")
    @Operation(summary = "presignFileResourceMultipartParts", description = "生成限定分片的短时上传地址")
    public Result<List<FileResourceMultipartPartUrlVO>> presignMultipartUploadParts(
            @PathVariable("uploadRecordId") Long uploadRecordId,
            @RequestBody FileResourceMultipartPartsRequestDTO request) {
        return Result.buildSuc(fileResourceService.presignMultipartUploadParts(uploadRecordId, request));
    }

    @PostMapping("/multipart-uploads/{uploadRecordId}/complete")
    @Operation(summary = "completeFileResourceMultipartUpload", description = "完成 MinIO 分片上传并登记文件资源")
    public Result<FileResourceVO> completeMultipartUpload(
            @PathVariable("uploadRecordId") Long uploadRecordId,
            @RequestBody FileResourceMultipartCompleteRequestDTO request) {
        return Result.buildSuc(fileResourceService.completeMultipartUpload(uploadRecordId, request));
    }

    @DeleteMapping("/multipart-uploads/{uploadRecordId}")
    @Operation(summary = "abortFileResourceMultipartUpload", description = "取消并清理未完成的 MinIO 分片上传")
    public Result<Boolean> abortMultipartUpload(@PathVariable("uploadRecordId") Long uploadRecordId) {
        fileResourceService.abortMultipartUpload(uploadRecordId);
        return Result.buildSuc(true);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "deleteFileResource", description = "删除文件或文件夹资源")
    public Result<Boolean> delete(@PathVariable("id") Long id) {
        fileResourceService.delete(id);
        return Result.buildSuc(true);
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "downloadFileResource", description = "下载文件资源")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable("id") Long id) {
        FileResourceVO resource = fileResourceService.get(id);
        if (!"FILE".equalsIgnoreCase(resource.getResourceType())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "只能下载文件资源");
        }
        StreamingResponseBody body = output -> fileResourceService.download(id, output);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType(resource.getContentType()));
        if (resource.getSize() != null && resource.getSize() >= 0) {
            headers.setContentLength(resource.getSize());
        }
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(resource.getName(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping("/{id}/preview-pdf")
    @Operation(summary = "previewFileResourceAsPdf", description = "将 Word 文档转换为 PDF 预览")
    public ResponseEntity<StreamingResponseBody> previewPdf(@PathVariable("id") Long id) {
        FileResourceVO resource = fileResourceService.get(id);
        if (!"FILE".equalsIgnoreCase(resource.getResourceType())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "只能预览文件资源");
        }
        StreamingResponseBody body = output -> fileResourceService.previewAsPdf(id, output);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        String previewName = resource.getName() == null ? "preview.pdf" : resource.getName().replaceAll("(?i)\\.docx?$", "") + ".pdf";
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(previewName, StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PostMapping("/{id}/preview")
    @Operation(summary = "previewFileResource", description = "预览文件资源的有限数据")
    public Result<Map<String, Object>> preview(
            @PathVariable("id") Long id,
            @RequestBody(required = false) FileResourcePreviewDTO request) {
        return Result.buildSuc(fileResourceService.preview(id, request));
    }

    @GetMapping("/upload-records")
    @Operation(summary = "pageFileResourceUploadRecords", description = "查询文件资源上传记录")
    public PaginationResult<FileUploadRecordVO> uploadRecords(
            @ModelAttribute FileResourceUploadRecordQueryDTO query) {
        return fileResourceService.pageUploadRecords(query);
    }

    private MediaType contentType(String contentType) {
        try {
            return contentType == null || contentType.isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
