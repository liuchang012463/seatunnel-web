package org.apache.seatunnel.web.api.fileresource;

/**
 * Extension point used to protect resources that are referenced by tasks.
 *
 * <p>The task-reference table is introduced by the task-management change.
 * Keeping this contract in the resource service lets that change plug in a
 * real checker without coupling the resource implementation to task tables.</p>
 */
public interface FileResourceReferenceChecker {

    boolean isReferenced(Long resourceId);
}
