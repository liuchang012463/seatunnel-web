package org.apache.seatunnel.web.dao.repository.impl;

import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.apache.seatunnel.web.dao.mapper.FileResourceMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.FileResourceDao;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class FileResourceDaoImpl extends BaseDao<FileResource, FileResourceMapper>
        implements FileResourceDao {

    private final FileResourceMapper mapper;

    public FileResourceDaoImpl(@NonNull FileResourceMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public List<FileResource> queryActiveByOwnerId(Integer ownerId) {
        if (ownerId == null) {
            return List.of();
        }
        return mapper.selectActiveByOwnerId(ownerId);
    }

    @Override
    public FileResource queryByOwnerAndLogicalPath(Integer ownerId, String logicalPath) {
        if (ownerId == null || logicalPath == null || logicalPath.isBlank()) {
            return null;
        }
        return mapper.selectByOwnerAndLogicalPath(ownerId, logicalPath);
    }

    @Override
    public FileResource queryByOwnerAndObjectKey(Integer ownerId, String objectKey) {
        if (ownerId == null || objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return mapper.selectByOwnerAndObjectKey(ownerId, objectKey);
    }
}
