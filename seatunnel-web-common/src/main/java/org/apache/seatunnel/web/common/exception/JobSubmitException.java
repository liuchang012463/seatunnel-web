package org.apache.seatunnel.web.common.exception;

import org.apache.seatunnel.web.common.enums.JobSubmitStage;

public class JobSubmitException extends RuntimeException {

    private final JobSubmitStage stage;

    /**
     * True when the Engine may have accepted the job although no job id came back. Callers must not
     * record such a failure as final: a retry could duplicate the Engine job.
     */
    private final boolean outcomeUnknown;

    public JobSubmitException(JobSubmitStage stage, String message, Throwable cause) {
        this(stage, message, cause, false);
    }

    public JobSubmitException(JobSubmitStage stage,
                              String message,
                              Throwable cause,
                              boolean outcomeUnknown) {
        super(message, cause);
        this.stage = stage;
        this.outcomeUnknown = outcomeUnknown;
    }

    public JobSubmitStage getStage() {
        return stage;
    }

    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
