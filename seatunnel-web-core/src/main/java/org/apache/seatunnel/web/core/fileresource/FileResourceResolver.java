package org.apache.seatunnel.web.core.fileresource;

/**
 * Resolves a persisted file resource into an execution-time object-storage
 * location.  Task builders depend on this contract rather than on a concrete
 * storage provider or on the web upload implementation.
 */
public interface FileResourceResolver {

    /**
     * Resolve an active resource visible to the current request context.
     *
     * @param resourceId persisted file resource identifier
     * @return execution-time object-storage reference
     * @throws IllegalArgumentException when the resource is missing or cannot
     *                                  be used by a task
     */
    FileResourceReference resolve(Long resourceId);
}
