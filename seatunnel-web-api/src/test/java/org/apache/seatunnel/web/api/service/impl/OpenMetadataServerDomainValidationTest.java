package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenMetadataServerDomainValidationTest {

    @Test
    void successfulEmptyBotLookupAllowsTrueNoDomainInstallations() {
        assertDoesNotThrow(() -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                null, List.of(), true, List.of(), true, false));
    }

    @Test
    void failedBotLookupIsNotTreatedAsAnEmptyDomainList() {
        assertThrows(MetadataIntegrationException.class,
                () -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                        null, List.of(), false, List.of(), false, false));
    }

    @Test
    void multipleBotDomainsRequireOneOfTheAssignedDomains() {
        List<String> assigned = List.of("Engineering", "Finance");
        assertThrows(MetadataIntegrationException.class,
                () -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                        null, assigned, true, assigned, true, false));
        assertDoesNotThrow(() -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                "Finance", assigned, true, assigned, false, true));
        assertThrows(MetadataIntegrationException.class,
                () -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                        "Sales", assigned, true, List.of("Sales"), true, true));
    }

    @Test
    void explicitFallbackRequiresSuccessfulDomainValidation() {
        assertThrows(MetadataIntegrationException.class,
                () -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                        "Engineering", List.of(), true, List.of(), false, true));
        assertThrows(MetadataIntegrationException.class,
                () -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                        "Missing", List.of(), true, List.of("Engineering"), true, false));
        assertDoesNotThrow(() -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                "Engineering", List.of(), true, List.of("Engineering"), true, true));
    }
}
