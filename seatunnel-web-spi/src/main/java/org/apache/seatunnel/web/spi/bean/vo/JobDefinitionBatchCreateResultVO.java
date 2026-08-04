package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Result of copying job definitions for batch creation. */
@Data
public class JobDefinitionBatchCreateResultVO {

    private int templateCount;

    private int copiesPerTemplate;

    private int createdCount;

    private List<JobDefinitionSaveResultVO> createdJobs = new ArrayList<>();

    public void addCreatedJob(JobDefinitionSaveResultVO job) {
        createdJobs.add(job);
        createdCount++;
    }
}
