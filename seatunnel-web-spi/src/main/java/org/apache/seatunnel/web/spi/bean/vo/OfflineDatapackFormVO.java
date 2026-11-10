package org.apache.seatunnel.web.spi.bean.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** A source form and its field-code schema found in an encrypted data package. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfflineDatapackFormVO {

    private String formCode;

    private int recordCount;

    private List<String> fields;
}
