package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.FileUploadAsset;

import java.util.List;

public interface FileUploadAssetDao extends IDao<FileUploadAsset> {

    List<FileUploadAsset> queryBySessionId(String sessionId);

    FileUploadAsset queryBySessionIdAndRelativePath(String sessionId, String relativePath);

    void deleteBySessionId(String sessionId);
}
