package org.apache.seatunnel.web.api.fileresource;

import jakarta.annotation.Resource;
import org.apache.seatunnel.web.dao.repository.JobDefinitionContentDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionContentDao;
import org.springframework.stereotype.Component;

/**
 * Prevents a reusable resource from being deleted while a task still refers
 * to it.  The content search is intentionally a compatibility fallback for
 * the first rollout; future versions may replace it with a normalized
 * task-resource reference table without changing the resource service hook.
 */
@Component
public class DefinitionContentFileResourceReferenceChecker implements FileResourceReferenceChecker {

    @Resource
    private JobDefinitionContentDao jobDefinitionContentDao;

    @Resource
    private StreamingJobDefinitionContentDao streamingJobDefinitionContentDao;

    @Override
    public boolean isReferenced(Long resourceId) {
        if (resourceId == null || resourceId <= 0) {
            return false;
        }
        String value = String.valueOf(resourceId);
        return jobDefinitionContentDao.existsByFileResourceId(value)
                || streamingJobDefinitionContentDao.existsByFileResourceId(value);
    }
}
