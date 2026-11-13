package org.apache.seatunnel.web.spi.bean.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Safe inspection result containing the encrypted package fingerprint and CSV filenames. */
@Data
@AllArgsConstructor
public class OfflineDatapackCsvInspectVO {

    private String packageFingerprint;

    private List<String> fileNames;
}
