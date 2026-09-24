package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.dao.entity.FileUploadRecord;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceUploadRecordQueryDTO;

public interface FileUploadRecordDao extends IDao<FileUploadRecord> {

    IPage<FileUploadRecord> queryPage(
            Integer ownerId, FileResourceUploadRecordQueryDTO queryDTO);

    /** Row-lock the upload record so concurrent complete/abort cannot race. */
    FileUploadRecord queryByIdForUpdate(Long id);
}
