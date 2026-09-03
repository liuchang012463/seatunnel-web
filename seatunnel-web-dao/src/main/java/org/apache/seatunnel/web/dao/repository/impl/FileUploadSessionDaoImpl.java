package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.FileUploadSession;
import org.apache.seatunnel.web.dao.mapper.FileUploadSessionMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.FileUploadSessionDao;
import org.springframework.stereotype.Repository;

@Repository
public class FileUploadSessionDaoImpl
        extends BaseDao<FileUploadSession, FileUploadSessionMapper>
        implements FileUploadSessionDao {

    private final FileUploadSessionMapper mapper;

    public FileUploadSessionDaoImpl(@NonNull FileUploadSessionMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public FileUploadSession queryByJobDefinitionId(Long jobDefinitionId) {
        if (jobDefinitionId == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<FileUploadSession>()
                .eq(FileUploadSession::getJobDefinitionId, jobDefinitionId));
    }
}
