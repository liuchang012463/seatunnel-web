package org.apache.seatunnel.web.api.lake.operation;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;

import java.util.Date;

/**
 * Secret-safe operation journal projection used by lake detail pages.
 *
 * <p>Leases, request hashes and raw request details deliberately never cross
 * this boundary.  The journal already stores redacted summaries; the service
 * redacts once more when projecting historical rows so old rows remain safe
 * after a code upgrade.</p>
 */
@Data
public class LakeResourceOperationVO {

    private Long id;

    private String resourceType;

    private Long resourceId;

    private Integer generation;

    private LakeOperationType operationType;

    private LakeOperationStatus status;

    private Date startedAt;

    private Date finishedAt;

    private String errorCode;

    private String errorSummary;

    private Integer operatorId;
}
