package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatapackPackageInspectorTest {

    private DatapackPackageInspector inspector;
    private OfflineImportProperties properties;

    @BeforeEach
    void setUp() {
        properties = new OfflineImportProperties();
        inspector = new DatapackPackageInspector(properties);
    }

    @Test
    void decryptsValidDataAndReturnsOneFormSchema() throws Exception {
        byte[] encrypted = encrypt(payload("DATA", 2, "\"id\":1,\"name\":\"alpha\""), "secret".toCharArray());

        DatapackPackageInspector.Inspection result = inspector.inspect(encrypted, "secret".toCharArray());

        assertEquals("DATA", result.packageType());
        assertEquals(2, result.recordCount());
        assertEquals("FORM_A", result.forms().get(0).formCode());
        assertEquals(2, result.forms().get(0).recordCount());
        assertEquals(Arrays.asList("id", "name"), result.forms().get(0).fields());
        assertEquals(64, result.fingerprint().length());
    }

    @Test
    void rejectsWrongPasswordAndAuthenticatedCiphertextTampering() throws Exception {
        byte[] encrypted = encrypt(payload("DATA", 2, "\"id\":1,\"name\":\"alpha\""), "secret".toCharArray());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspect(encrypted, "wrong".toCharArray()));

        encrypted[encrypted.length - 1] ^= 0x01;
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspect(encrypted, "secret".toCharArray()));
    }

    @Test
    void rejectsUnsupportedTypesCountMismatchAndMalformedLength() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(
                encrypt(payload("FEEDBACK", 2, "\"id\":1,\"name\":\"alpha\""), "secret".toCharArray()),
                "secret".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(
                encrypt(payload("DATA", 1, "\"id\":1,\"name\":\"alpha\""), "secret".toCharArray()),
                "secret".toCharArray()));

        byte[] malformed = encrypt(payload("DATA", 2, "\"id\":1,\"name\":\"alpha\""), "secret".toCharArray());
        ByteBuffer.wrap(malformed, 33, 4).putInt(12);
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspect(malformed, "secret".toCharArray()));
    }

    @Test
    void enforcesConfiguredByteAndRecordLimitsAndClampsRuntimeBudget() throws Exception {
        properties.setDatapackMaxFileBytes(64);
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspect(new byte[65], "secret".toCharArray()));

        properties.setDatapackMaxFileBytes(8L * 1024 * 1024);
        properties.setDatapackMaxRecords(1);
        byte[] twoRecords = encrypt(payload("DATA", 2, "\"id\":1,\"name\":\"alpha\""), "secret".toCharArray());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspect(twoRecords, "secret".toCharArray()));

        properties.setDatapackMaxFileBytes(100L * 1024 * 1024);
        assertTrue(properties.getDatapackMaxFileBytes() <= 32L * 1024 * 1024);
        assertTrue(properties.getDatapackMaxFileBytes() <= Runtime.getRuntime().maxMemory() / 256L);
        properties.setDatapackMaxRecords(500_000);
        assertEquals(50_000, properties.getDatapackMaxRecords());
    }

    @Test
    void appliesSeparateCsvArchiveLimitAndBoundsItsRuntimeBudget() throws Exception {
        properties.setDatapackMaxFileBytes(64);
        properties.setDatapackCsvMaxFileBytes(256L * 1024);
        assertTrue(properties.getDatapackMaxFileBytes() <= 64);

        String largeCsv = "id,name\r\n" + "x".repeat(128 * 1024) + ",sample\r\n";
        byte[] encrypted = encrypt(
                zip(csvManifest(List.of("orders.csv"), 1), List.of("orders.csv"), List.of(largeCsv)),
                "secret".toCharArray());
        DatapackPackageInspector.CsvArchiveInspection inspection =
                inspector.inspectCsvArchive(encrypted, "secret".toCharArray());
        assertEquals(List.of("orders.csv"), inspection.fileNames());

        properties.setDatapackCsvMaxFileBytes(128L * 1024 * 1024);
        assertTrue(properties.getDatapackCsvMaxFileBytes() <= 64L * 1024 * 1024);
        assertTrue(properties.getDatapackCsvMaxFileBytes() <= Runtime.getRuntime().maxMemory() / 64L);
        assertTrue(properties.getDatapackMaxFileBytes() <= 64);
    }

    @Test
    void listsAllManifestCsvFilesAndExtractsOnlyTheSelectedZeroRowCsv() throws Exception {
        List<String> names = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            names.add("table-" + index + ".csv");
            rows.add(index == 7 ? "id,name\r\n" : "id,name\r\n" + index + ",sample\r\n");
        }
        byte[] encrypted = encrypt(zip(csvManifest(names, 0), names, rows), "secret".toCharArray());

        DatapackPackageInspector.CsvArchiveInspection inspection =
                inspector.inspectCsvArchive(encrypted, "secret".toCharArray());

        assertEquals(names, inspection.fileNames());
        assertEquals(64, inspection.fingerprint().length());
        DatapackPackageInspector.CsvSelection selected = inspector.extractCsv(
                encrypted, "secret".toCharArray(), inspection.fingerprint(), names.get(7));
        assertArrayEquals("id,name\r\n".getBytes(StandardCharsets.UTF_8), selected.content());
        assertEquals(Map.of("id", "long", "name", "string"), selected.schema());
        assertEquals("NULL_7fe4", selected.nullValue());
    }

    @Test
    void mapsDecimalColumnsToSeaTunnelPrecisionAndScale() throws Exception {
        List<String> names = List.of("decimal.csv");
        byte[] encrypted = encrypt(zip(
                csvManifest(names, 0, true, 12, 2), names, List.of("id,name,amount\r\n")),
                "secret".toCharArray());
        DatapackPackageInspector.CsvArchiveInspection inspection =
                inspector.inspectCsvArchive(encrypted, "secret".toCharArray());

        DatapackPackageInspector.CsvSelection selected = inspector.extractCsv(
                encrypted, "secret".toCharArray(), inspection.fingerprint(), names.get(0));
        assertEquals("decimal(12,2)", selected.schema().get("amount"));

        byte[] maximumPrecision = encrypt(zip(
                csvManifest(names, 0, true, 65, 30), names, List.of("id,name,amount\r\n")),
                "secret".toCharArray());
        DatapackPackageInspector.CsvArchiveInspection maximumInspection =
                inspector.inspectCsvArchive(maximumPrecision, "secret".toCharArray());
        DatapackPackageInspector.CsvSelection maximumSelected = inspector.extractCsv(
                maximumPrecision, "secret".toCharArray(), maximumInspection.fingerprint(), names.get(0));
        assertEquals("decimal(65,30)", maximumSelected.schema().get("amount"));
    }

    @Test
    void rejectsMissingOrUnsupportedDecimalPrecisionAndScale() throws Exception {
        List<String> names = List.of("decimal.csv");
        String csv = "id,name,amount\r\n";
        byte[] unsupportedPrecision = encrypt(zip(
                csvManifest(names, 0, true, 66, 2), names, List.of(csv)), "secret".toCharArray());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspectCsvArchive(unsupportedPrecision, "secret".toCharArray()));

        byte[] invalidScale = encrypt(zip(
                csvManifest(names, 0, true, 65, 31), names, List.of(csv)), "secret".toCharArray());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspectCsvArchive(invalidScale, "secret".toCharArray()));

        byte[] missingPrecision = encrypt(zip(
                csvManifest(names, 0, true, null, 2), names, List.of(csv)), "secret".toCharArray());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspectCsvArchive(missingPrecision, "secret".toCharArray()));
    }

    @Test
    void rejectsChangedFingerprintUnlistedZipFilesAndUnsafeManifestPaths() throws Exception {
        List<String> names = List.of("orders.csv");
        List<String> rows = List.of("id\r\n");
        byte[] valid = encrypt(zip(csvManifest(names, 0), names, rows), "secret".toCharArray());
        assertThrows(IllegalArgumentException.class, () -> inspector.extractCsv(
                valid, "secret".toCharArray(), "0".repeat(64), names.get(0)));

        byte[] extraFile = encrypt(zipWithExtraFile(csvManifest(names, 0), names, rows), "secret".toCharArray());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspectCsvArchive(extraFile, "secret".toCharArray()));

        List<String> traversal = List.of("../orders.csv");
        byte[] unsafe = encrypt(zip(csvManifest(traversal, 0), traversal, rows), "secret".toCharArray());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspectCsvArchive(unsafe, "secret".toCharArray()));

        List<String> duplicate = List.of("orders.csv", "orders.csv");
        byte[] duplicateManifest = encrypt(zip(csvManifest(duplicate, 0), List.of("orders.csv"), rows), "secret".toCharArray());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspectCsvArchive(duplicateManifest, "secret".toCharArray()));
    }

    private byte[] payload(String packageType, int declaredCount, String firstValues) {
        String secondRecord = firstValues.replace("id\":1", "id\":2").replace("alpha", "beta");
        String json = "{\"packageId\":\"pkg-1\",\"packageType\":\"" + packageType
                + "\",\"protocolVersion\":\"1\",\"sourceVersion\":1,"
                + "\"recordCounts\":{\"FORM_A\":" + declaredCount + "},\"records\":["
                + "{\"formCode\":\"FORM_A\",\"values\":{" + firstValues + "}},"
                + "{\"formCode\":\"FORM_A\",\"values\":{" + secondRecord + "}}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] encrypt(byte[] plaintext, char[] password) throws Exception {
        byte[] salt = new byte[16];
        Arrays.fill(salt, (byte) 7);
        byte[] iv = new byte[12];
        Arrays.fill(iv, (byte) 3);
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, 120000, 128);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).getEncoded();
        keySpec.clearPassword();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        ByteBuffer packageBytes = ByteBuffer.allocate(5 + salt.length + iv.length + 4 + ciphertext.length);
        packageBytes.put("DRPK1".getBytes(StandardCharsets.US_ASCII));
        packageBytes.put(salt);
        packageBytes.put(iv);
        packageBytes.putInt(ciphertext.length);
        packageBytes.put(ciphertext);
        Arrays.fill(key, (byte) 0);
        Arrays.fill(password, '\0');
        return packageBytes.array();
    }

    private byte[] csvManifest(List<String> fileNames, long rowCount) {
        return csvManifest(fileNames, rowCount, false, null, null);
    }

    private byte[] csvManifest(
            List<String> fileNames, long rowCount, boolean includeDecimal, Integer decimalPrecision, Integer decimalScale) {
        String decimalColumn = includeDecimal
                ? ", {\"ordinalPosition\":3,\"name\":\"amount\",\"type\":\"DECIMAL\",\"size\":"
                        + (decimalPrecision == null ? "null" : decimalPrecision)
                        + ",\"decimalDigits\":" + (decimalScale == null ? "null" : decimalScale)
                        + ",\"nullable\":true}"
                : "";
        String tables = fileNames.stream()
                .map(name -> "{\"fileName\":\"" + name + "\",\"rowCount\":" + rowCount
                        + ",\"columns\":[{\"ordinalPosition\":1,\"name\":\"id\",\"type\":\"BIGINT\","
                        + "\"size\":8,\"decimalDigits\":0,\"nullable\":false},"
                        + "{\"ordinalPosition\":2,\"name\":\"name\",\"type\":\"VARCHAR\","
                        + "\"size\":128,\"decimalDigits\":0,\"nullable\":true}"
                        + decimalColumn + "]}")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return ("{\"packageId\":\"pkg-csv\",\"packageType\":\"TOTAL\",\"protocolVersion\":1,"
                + "\"sourceSiteCode\":\"source-1\",\"targetSiteCode\":\"target-1\",\"sourceVersion\":1,"
                + "\"generatedAt\":\"2026-11-13T08:00:00Z\",\"operatorName\":\"operator\","
                + "\"encryptionFormat\":\"DRPK1\",\"archiveFormat\":\"ZIP\","
                + "\"csvDialect\":\"RFC4180\",\"csvEncoding\":\"UTF-8\",\"csvBom\":false,"
                + "\"delimiter\":\",\",\"quoteCharacter\":\"\\\"\",\"quoteEscape\":\"\\\"\\\"\","
                + "\"nullValue\":\"NULL_7fe4\",\"lineEnding\":\"CRLF\","
                + "\"dateFormat\":\"yyyy-MM-dd\",\"dateTimeFormat\":\"yyyy-MM-dd HH:mm:ss.SSSSSSSSS\","
                + "\"decimalFormat\":\"plain\",\"createdMetadataFallback\":false,\"tables\":[" + tables + "]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] zip(byte[] manifest, List<String> fileNames, List<String> csvContents) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeZipEntry(zip, "manifest.json", manifest);
            for (int index = 0; index < fileNames.size(); index++) {
                writeZipEntry(zip, fileNames.get(index), csvContents.get(index).getBytes(StandardCharsets.UTF_8));
            }
        }
        return output.toByteArray();
    }

    private byte[] zipWithExtraFile(byte[] manifest, List<String> fileNames, List<String> csvContents)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeZipEntry(zip, "manifest.json", manifest);
            for (int index = 0; index < fileNames.size(); index++) {
                writeZipEntry(zip, fileNames.get(index), csvContents.get(index).getBytes(StandardCharsets.UTF_8));
            }
            writeZipEntry(zip, "unlisted.txt", new byte[] {1});
        }
        return output.toByteArray();
    }

    private void writeZipEntry(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
