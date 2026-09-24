package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.dao.entity.FileUploadRecord;
import org.apache.seatunnel.web.dao.repository.FileUploadRecordDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * Persists upload audit state independently from the object/metadata
 * transaction.  A failed upload must remain visible after that transaction
 * rolls back its partial resource mutations.
 */
@Service
public class FileUploadRecordPersistenceService {

    private final FileUploadRecordDao fileUploadRecordDao;

    public FileUploadRecordPersistenceService(FileUploadRecordDao fileUploadRecordDao) {
        this.fileUploadRecordDao = fileUploadRecordDao;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(FileUploadRecord record) {
        fileUploadRecordDao.insert(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long uploadRecordId, String errorMessage) {
        Date now = new Date();
        fileUploadRecordDao.markFailedIfUploading(uploadRecordId, errorMessage, now, now);
    }
}
