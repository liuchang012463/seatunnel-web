package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.FileResourceDirectoryDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartCompleteRequestDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartPartsRequestDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartUploadRequestDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourcePreviewDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceUploadRecordQueryDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceVO;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceMultipartPartUrlVO;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceMultipartUploadVO;
import org.apache.seatunnel.web.spi.bean.vo.FileUploadRecordVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/** Application facade for reusable file resources. */
public interface FileResourceService {

    List<FileResourceVO> list(String path);

    FileResourceVO get(Long id);

    FileResourceVO createDirectory(FileResourceDirectoryDTO request);

    List<FileResourceVO> upload(String path, MultipartFile[] files, String[] relativePaths);

    FileResourceMultipartUploadVO initiateMultipartUpload(FileResourceMultipartUploadRequestDTO request);

    List<FileResourceMultipartPartUrlVO> presignMultipartUploadParts(
            Long uploadRecordId, FileResourceMultipartPartsRequestDTO request);

    FileResourceVO completeMultipartUpload(
            Long uploadRecordId, FileResourceMultipartCompleteRequestDTO request);

    void abortMultipartUpload(Long uploadRecordId);

    void delete(Long id);

    void download(Long id, OutputStream output) throws IOException;

    /**
     * Convert a Word document resource into a PDF stream for browser preview.
     *
     * @param id file resource id
     * @param output PDF output stream
     */
    void previewAsPdf(Long id, OutputStream output) throws IOException;

    Map<String, Object> preview(Long id, FileResourcePreviewDTO request);

    PaginationResult<FileUploadRecordVO> pageUploadRecords(FileResourceUploadRecordQueryDTO query);
}
