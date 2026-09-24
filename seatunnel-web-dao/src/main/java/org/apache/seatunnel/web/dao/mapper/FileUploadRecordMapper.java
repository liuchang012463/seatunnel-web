package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.seatunnel.web.dao.entity.FileUploadRecord;

@Mapper
public interface FileUploadRecordMapper extends BaseMapper<FileUploadRecord> {

    IPage<FileUploadRecord> selectPageByOwnerId(
            IPage<FileUploadRecord> page,
            @Param("ownerId") Integer ownerId,
            @Param("status") String status,
            @Param("targetPath") String targetPath);

    /** Locks one upload-record row for the duration of the current transaction. */
    FileUploadRecord selectByIdForUpdate(@Param("id") Long id);
}
