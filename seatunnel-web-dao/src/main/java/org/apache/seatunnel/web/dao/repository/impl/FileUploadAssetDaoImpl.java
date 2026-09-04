package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.FileUploadAsset;
import org.apache.seatunnel.web.dao.mapper.FileUploadAssetMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.FileUploadAssetDao;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

@Repository
public class FileUploadAssetDaoImpl
        extends BaseDao<FileUploadAsset, FileUploadAssetMapper>
        implements FileUploadAssetDao {

    private final FileUploadAssetMapper mapper;

    public FileUploadAssetDaoImpl(@NonNull FileUploadAssetMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public List<FileUploadAsset> queryBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        return mapper.selectList(new LambdaQueryWrapper<FileUploadAsset>()
                .eq(FileUploadAsset::getSessionId, sessionId)
                .orderByAsc(FileUploadAsset::getRelativePath));
    }

    @Override
    public FileUploadAsset queryBySessionIdAndRelativePath(String sessionId, String relativePath) {
        if (sessionId == null || sessionId.isBlank()
                || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<FileUploadAsset>()
                .eq(FileUploadAsset::getSessionId, sessionId)
                .eq(FileUploadAsset::getRelativePath, relativePath));
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        mapper.delete(new LambdaQueryWrapper<FileUploadAsset>()
                .eq(FileUploadAsset::getSessionId, sessionId));
    }
}
