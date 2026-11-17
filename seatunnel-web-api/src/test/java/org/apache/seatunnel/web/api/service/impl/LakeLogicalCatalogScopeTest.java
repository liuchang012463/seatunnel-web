package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpec;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.spi.bean.dto.LakeQueryTableIdentityDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LakeLogicalCatalogScopeTest {
    @Test
    void legacyBareTableNamesCannotAuthorizeTheSameTableInAnotherDatabase() {
        var spec = spec(List.of("sales"), List.of("orders"));
        assertDoesNotThrow(() -> check(spec, "sales"));
        assertThrows(LakeServiceException.class, () -> check(spec, "private_db"));
    }

    @Test
    void qualifiedSelectionsRemainIsolatedAcrossNamespaces() {
        var spec = spec(List.of("sales", "archive"), List.of("sales.orders"));
        assertDoesNotThrow(() -> check(spec, "sales"));
        assertThrows(LakeServiceException.class, () -> check(spec, "archive"));
    }

    private void check(LakeCatalogDesiredSpec spec, String database) {
        ReflectionTestUtils.invokeMethod(LakeLogicalCatalogQueryServiceImpl.class, "requireScope", spec,
                new LakeQueryTableIdentityDTO("ext", database, "orders"));
    }

    private LakeCatalogDesiredSpec spec(List<String> databases, List<String> tables) {
        return new LakeCatalogDesiredSpec("ext", 1L, "v", LakeJdbcAdapterType.MYSQL, LakeCatalogScope.TABLE,
                "jdbc:mysql://host/sales", "driver.jar", "com.mysql.cj.jdbc.Driver", "a".repeat(64),
                "registry", null, databases, tables, Map.of());
    }
}
