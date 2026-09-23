package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/** Public, owner-scoped handle and chunk sizing for one active multipart upload. */
@Data
public class FileResourceMultipartUploadVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long uploadRecordId;

    private Long partSizeBytes;

    private Integer totalParts;
}
