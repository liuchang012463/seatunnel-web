package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmpArchivePreflightTest {

    @Test
    void damengOnlyMatchesAnchoredVendorSchemaSummary() {
        assertTrue(DmpArchivePreflight.damengSchemaEntry(
                "模式CODEX_DMP_SRC_274219 含有 1 个表", "codex_dmp_src_274219"));
        assertTrue(DmpArchivePreflight.damengSchemaEntry(
                "Schema CODEX_DMP_SRC contains 1 table", "CODEX_DMP_SRC"));
        assertFalse(DmpArchivePreflight.damengSchemaEntry(
                "错误：模式 CODEX_DMP_SRC_274219 含有 1 个表", "CODEX_DMP_SRC_274219"));
        assertFalse(DmpArchivePreflight.damengSchemaEntry(
                "TABLE CODEX_DMP_SRC", "CODEX_DMP_SRC"));
        assertFalse(DmpArchivePreflight.damengSchemaEntry(
                "模式CODEX_DMP_SOURCE_274219 含有 1 个表", "CODEX_DMP_SRC_274219"));
        assertFalse(DmpArchivePreflight.damengSchemaEntry(
                "COMMENT: 模式 CODEX_DMP_SRC_274219 含有 1 个表", "CODEX_DMP_SRC_274219"));
    }

    @Test
    void selectedSourceDefaultsToTargetAndSecretsIncludeEncodedForms() {
        assertEquals("target", DmpArchivePreflight.selectedSourceSchema(" ", "target"));
        assertEquals("source", DmpArchivePreflight.selectedSourceSchema(" source ", "target"));
        assertTrue(DmpArchivePreflight.secretVariants("Pwd @#").contains("Pwd+%40%23"));
        assertTrue(DmpArchivePreflight.secretVariants("Pwd @#").contains("Pwd%20%40%23"));
        assertTrue(DmpArchivePreflight.secretVariants("a\"b").contains("a\"\"b"));
        assertEquals(List.of(), DmpArchivePreflight.secretVariants(""));
    }
}
