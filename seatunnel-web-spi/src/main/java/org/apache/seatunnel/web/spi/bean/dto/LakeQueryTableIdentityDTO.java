package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Structured catalog/database/table identity. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LakeQueryTableIdentityDTO(String catalog, String database, String table) {
}
