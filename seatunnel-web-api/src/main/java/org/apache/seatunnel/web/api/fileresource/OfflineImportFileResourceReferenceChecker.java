package org.apache.seatunnel.web.api.fileresource;

import jakarta.annotation.Resource;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskDao;
import org.springframework.stereotype.Component;

/** Prevents deleting an upload that a pending or historical import task still references. */
@Component
public class OfflineImportFileResourceReferenceChecker implements FileResourceReferenceChecker {

    @Resource
    private OfflineImportTaskDao offlineImportTaskDao;

    @Override
    public boolean isReferenced(Long resourceId) {
        return offlineImportTaskDao.existsByFileResourceId(resourceId);
    }
}
