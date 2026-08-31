package org.apache.seatunnel.web.spi.bean.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Structured Doris distribution input; arbitrary distribution SQL is not accepted. */
@Data
public class LakeManagedTableDistributionDTO {

    private String type;

    private List<String> columns = new ArrayList<>();

    private String buckets;
}
