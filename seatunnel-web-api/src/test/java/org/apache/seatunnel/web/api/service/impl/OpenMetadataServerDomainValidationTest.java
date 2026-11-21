package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenMetadataServerDomainValidationTest {

    @Test
    void emptyScopeMeansAllDomainsWithoutRequiringBotDomainLookup() {
        assertDoesNotThrow(() -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                null, false));
    }

    @Test
    void explicitDomainMustExistButDoesNotDependOnBotMembership() {
        assertDoesNotThrow(() -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                "Finance", true));
        assertThrows(MetadataIntegrationException.class,
                () -> OpenMetadataServerServiceImpl.validateDefaultDomainSelection(
                        "Missing", false));
    }
}
