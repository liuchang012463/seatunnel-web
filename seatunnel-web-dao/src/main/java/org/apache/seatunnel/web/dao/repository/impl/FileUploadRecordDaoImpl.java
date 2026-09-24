package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.dao.entity.FileUploadRecord;
import org.apache.seatunnel.web.dao.mapper.FileUploadRecordMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.FileUploadRecordDao;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceUploadRecordQueryDTO;
import org.springframework.stereotype.Repository;

@Repository
public class FileUploadRecordDaoImpl extends BaseDao<FileUploadRecord, FileUploadRecordMapper>
        implements FileUploadRecordDao {

    private final FileUploadRecordMapper mapper;

    public FileUploadRecordDaoImpl(@NonNull FileUploadRecordMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public IPage<FileUploadRecord> queryPage(
            Integer ownerId, FileResourceUploadRecordQueryDTO queryDTO) {
        if (ownerId == null) {
            return new Page<>(1, 10);
        }
        FileResourceUploadRecordQueryDTO dto = queryDTO == null
                ? new FileResourceUploadRecordQueryDTO() : queryDTO;
        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0
                ? 10 : Math.min(dto.getPageSize(), 1000);
        return mapper.selectPageByOwnerId(
                new Page<>(pageNo, pageSize),
                ownerId,
                StringUtils.trimToNull(dto.getStatus()),
                StringUtils.trimToNull(dto.getTargetPath()));
    }

    @Override
    public FileUploadRecord queryByIdForUpdate(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        return mapper.selectByIdForUpdate(id);
    }
}
