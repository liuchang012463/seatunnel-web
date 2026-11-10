package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;

/** Safe package summary returned after GCM authentication and payload validation. */
@Data
public class OfflineDatapackInspectVO {

    private String packageFingerprint;

    private String packageId;

    private String packageType;

    private int recordCount;

    private List<OfflineDatapackFormVO> forms;
}
