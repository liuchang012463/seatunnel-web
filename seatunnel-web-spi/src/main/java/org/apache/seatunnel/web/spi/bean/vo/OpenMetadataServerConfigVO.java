package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;

@Data
public class OpenMetadataServerConfigVO {

    private String baseUrl;

    private String defaultDomainFqn;

    private List<String> botDomainFqns;

    private List<String> availableDomainFqns;

    private boolean botDomainLookupAvailable;

    private boolean domainListAvailable;

    private boolean tokenConfigured;

    private Integer connectTimeoutMs;

    private Integer readTimeoutMs;

    private String expectedServerVersion;

    private String expectedIngestionPatch;

    private Long configVersion;

    private String connStatus;

    private String lastError;

    private boolean configured;

    /** Optional embedded health snapshot for the ops overview page. */
    private MetadataIntegrationHealthVO health;
}
