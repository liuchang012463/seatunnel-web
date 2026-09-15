package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.FileResource;

import java.util.List;

public interface FileResourceDao extends IDao<FileResource> {

    List<FileResource> queryActiveByOwnerId(Integer ownerId);

    FileResource queryByOwnerAndLogicalPath(Integer ownerId, String logicalPath);

    FileResource queryByOwnerAndObjectKey(Integer ownerId, String objectKey);
}
