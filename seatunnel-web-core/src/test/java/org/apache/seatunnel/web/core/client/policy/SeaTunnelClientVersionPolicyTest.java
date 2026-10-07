package org.apache.seatunnel.web.core.client.policy;

import org.apache.seatunnel.web.core.exceptions.ServiceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeaTunnelClientVersionPolicyTest {

    private SeaTunnelClientVersionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SeaTunnelClientVersionPolicy();
    }

    @Test
    void checkAcceptsSupportedVersions() {
        assertDoesNotThrow(() -> policy.check("2.3.13"));
        assertDoesNotThrow(() -> policy.check("3.0.0"));
    }

    @Test
    void checkIgnoresSurroundingWhitespace() {
        assertDoesNotThrow(() -> policy.check(" 3.0.0 "));
    }

    @Test
    void checkRejectsUnsupportedVersion() {
        ServiceException exception =
                assertThrows(ServiceException.class, () -> policy.check("2.3.12"));
        assertTrue(exception.getMessage().contains("2.3.12"));
    }

    @Test
    void checkRejectsBlankVersion() {
        ServiceException exception =
                assertThrows(ServiceException.class, () -> policy.check(" "));
        assertTrue(exception.getMessage().contains("未获取到版本信息"));
    }
}
