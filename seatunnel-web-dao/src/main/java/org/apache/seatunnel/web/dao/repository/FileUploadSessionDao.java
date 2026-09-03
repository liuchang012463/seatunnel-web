package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.FileUploadSession;

public interface FileUploadSessionDao extends IDao<FileUploadSession> {

    FileUploadSession queryByJobDefinitionId(Long jobDefinitionId);
}
