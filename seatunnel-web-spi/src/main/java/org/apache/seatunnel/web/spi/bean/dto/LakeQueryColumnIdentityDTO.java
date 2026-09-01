package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Structured column identity; the table is deliberately repeated. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LakeQueryColumnIdentityDTO(LakeQueryTableIdentityDTO table, String column) {
}
