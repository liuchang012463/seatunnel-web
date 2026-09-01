package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Non-secret input for a logical Doris JDBC catalog binding.
 *
 * <p>Source credentials, JDBC URL and driver URL are intentionally absent
 * from the writable request.  The server resolves credentials at execution
 * time and obtains driver facts from its verified registry.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LakeExternalCatalogCreateDTO {

    @Positive
    private Long lakeDataSourceId;

    @NotNull
    @Positive
    private Long sourceDataSourceId;

    @NotBlank
    @Size(max = 128)
    private String targetCatalogName;

    @NotBlank
    private String adapter;

    @NotNull
    private LakeCatalogScope scope;

    @Size(max = 128)
    private List<String> databaseInclude = List.of();

    @Size(max = 512)
    private List<String> tableInclude = List.of();

    private Map<String, String> options = new LinkedHashMap<>();

    /** Server-populated fields are accepted only by trusted service code. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String desiredSpecJson;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String desiredSpecHash;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String credentialRevision;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String driverChecksum;
}
