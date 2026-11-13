package org.apache.seatunnel.web.api.offlineimport;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Authenticates and validates DRPK1 packages without extracting files. */
@Component
@RequiredArgsConstructor
public class DatapackPackageInspector {

    private static final byte[] MAGIC = "DRPK1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BYTES = 16;
    private static final int HEADER_LENGTH = MAGIC.length + SALT_LENGTH + IV_LENGTH + Integer.BYTES;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final int MAX_TABLE_COLUMNS = 4096;
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    // The Light exporter reads DECIMAL metadata from MySQL, which supports up to 65 digits of precision.
    private static final int MAX_DECIMAL_PRECISION = 65;
    private static final int MAX_DECIMAL_SCALE = 30;

    private final OfflineImportProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_DEPTH)
                    .maxStringLength(1_000_000)
                    .maxNumberLength(128)
                    .build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Checks the complete binary frame, authenticates GCM, then validates the v1 JSON payload. */
    public Inspection inspect(byte[] encrypted, char[] password) {
        byte[] plaintext = decryptPayload(encrypted, password);
        try {
            return validatePayload(objectMapper.readTree(plaintext), fingerprint(encrypted));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            // Authentication and parser errors share a generic message to avoid exposing untrusted input.
            throw invalid("密码错误或数据包已损坏");
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Lists only manifest-declared CSV member names; inspection never writes package content to storage. */
    public CsvArchiveInspection inspectCsvArchive(byte[] encrypted, char[] password) {
        byte[] plaintext = decryptCsvPayload(encrypted, password);
        try {
            CsvArchive archive = readCsvArchive(plaintext, null);
            return new CsvArchiveInspection(
                    fingerprint(encrypted), archive.tables().stream().map(CsvTable::fileName).toList());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Revalidates the complete package and returns only the selected CSV bytes and its source schema. */
    public CsvSelection extractCsv(
            byte[] encrypted, char[] password, String expectedFingerprint, String selectedFileName) {
        String currentFingerprint = fingerprint(encrypted);
        if (expectedFingerprint == null || !MessageDigest.isEqual(
                currentFingerprint.getBytes(StandardCharsets.US_ASCII),
                expectedFingerprint.getBytes(StandardCharsets.US_ASCII))) {
            throw invalid("数据包内容已变化，请重新校验");
        }
        byte[] plaintext = decryptCsvPayload(encrypted, password);
        try {
            CsvArchive archive = readCsvArchive(plaintext, selectedFileName);
            CsvTable selected = archive.tables().stream()
                    .filter(table -> table.fileName().equals(selectedFileName))
                    .findFirst()
                    .orElseThrow(() -> invalid("所选 CSV 不属于当前数据包"));
            byte[] csv = archive.selectedCsv();
            if (csv == null) {
                throw invalid("数据包缺少所选 CSV 文件");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            selected.columns().forEach(column -> fields.put(column.name(), column.seatunnelType()));
            return new CsvSelection(selected.fileName(), csv, fields, archive.nullValue());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private byte[] decryptPayload(byte[] encrypted, char[] password) {
        return decryptPayload(encrypted, password, properties.getDatapackMaxFileBytes());
    }

    private byte[] decryptCsvPayload(byte[] encrypted, char[] password) {
        return decryptPayload(encrypted, password, properties.getDatapackCsvMaxFileBytes());
    }

    private byte[] decryptPayload(byte[] encrypted, char[] password, long maxBytes) {
        if (encrypted == null || encrypted.length < HEADER_LENGTH + TAG_LENGTH_BYTES
                || encrypted.length > maxBytes) {
            throw invalid("数据包大小无效");
        }
        ByteBuffer header = ByteBuffer.wrap(encrypted);
        byte[] magic = new byte[MAGIC.length];
        header.get(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw invalid("不是 DRPK1 数据包");
        }
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        header.get(salt);
        header.get(iv);
        int ciphertextLength = header.getInt();
        if (ciphertextLength < TAG_LENGTH_BYTES || ciphertextLength != encrypted.length - HEADER_LENGTH
                || ciphertextLength > maxBytes) {
            throw invalid("数据包声明长度无效");
        }
        if (password == null || password.length == 0) {
            throw invalid("请输入数据包密码");
        }

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
            byte[] plaintext = cipher.doFinal(encrypted, HEADER_LENGTH, ciphertextLength);
            if (plaintext.length > maxBytes) {
                Arrays.fill(plaintext, (byte) 0);
                throw invalid("数据包明文超过大小上限");
            }
            return plaintext;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            // Authentication and parser errors share a generic message to avoid exposing untrusted input.
            throw invalid("密码错误或数据包已损坏");
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public String fingerprint(byte[] encrypted) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encrypted));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法计算数据包指纹");
        }
    }

    private Inspection validatePayload(JsonNode payload, String fingerprint) {
        if (payload == null || !payload.isObject()
                || !"1".equals(text(payload.get("protocolVersion"), 16))) {
            throw invalid("不支持的数据包协议版本或清单格式");
        }
        String packageType = text(payload.get("packageType"), 32);
        if (!"DATA".equals(packageType) && !"DISTRIBUTION".equals(packageType)) {
            throw invalid("仅支持 DATA 和 DISTRIBUTION 数据包");
        }
        String packageId = text(payload.get("packageId"), 128);
        JsonNode sourceVersion = payload.get("sourceVersion");
        if (packageId == null || sourceVersion == null || !sourceVersion.canConvertToInt()
                || sourceVersion.intValue() <= 0) {
            throw invalid("数据包清单缺少有效标识");
        }
        JsonNode records = payload.get("records");
        JsonNode recordCounts = payload.get("recordCounts");
        if (records == null || !records.isArray() || records.size() > properties.getDatapackMaxRecords()
                || recordCounts == null || !recordCounts.isObject()) {
            throw invalid("数据包记录清单无效或超过记录数上限");
        }

        Map<String, Integer> expectedCounts = new LinkedHashMap<>();
        recordCounts.fields().forEachRemaining(entry -> {
            String formCode = validCode(entry.getKey());
            JsonNode count = entry.getValue();
            if (count == null || !count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 0) {
                throw invalid("数据包表单记录数无效");
            }
            expectedCounts.put(formCode, count.intValue());
        });

        Map<String, Integer> actualCounts = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> formFields = new LinkedHashMap<>();
        for (JsonNode record : records) {
            if (!record.isObject()) {
                throw invalid("数据包记录格式无效");
            }
            String formCode = validCode(text(record.get("formCode"), 128));
            JsonNode values = record.get("values");
            if (values == null || !values.isObject() || values.isEmpty()) {
                throw invalid("记录缺少表单字段值");
            }
            LinkedHashSet<String> currentFields = new LinkedHashSet<>();
            values.fields().forEachRemaining(field -> {
                String fieldCode = validCode(field.getKey());
                JsonNode value = field.getValue();
                if (value != null && !value.isNull() && !value.isValueNode()) {
                    throw invalid("不支持嵌套的表单字段值");
                }
                currentFields.add(fieldCode);
            });
            LinkedHashSet<String> previousFields = formFields.putIfAbsent(formCode, currentFields);
            if (previousFields != null && !previousFields.equals(currentFields)) {
                throw invalid("同一表单的字段结构不一致");
            }
            actualCounts.merge(formCode, 1, Integer::sum);
        }
        if (!expectedCounts.equals(actualCounts)) {
            throw invalid("数据包表单记录数与记录清单不一致");
        }

        List<FormSummary> forms = new ArrayList<>();
        actualCounts.forEach((formCode, count) -> forms.add(
                new FormSummary(formCode, count, List.copyOf(formFields.get(formCode)))));
        return new Inspection(fingerprint, packageId, packageType, records.size(), forms, records);
    }

    private CsvArchive readCsvArchive(byte[] plaintext, String selectedFileName) {
        Set<String> entryNames = new HashSet<>();
        Set<String> fileNames = new LinkedHashSet<>();
        Set<String> directoryNames = new LinkedHashSet<>();
        byte[] manifestBytes = null;
        byte[] selectedCsv = null;
        long expandedBytes = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(plaintext), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw invalid("数据包文件数量超过上限");
                }
                String name = entry.getName();
                validateZipMemberName(name, entry.isDirectory());
                if (!entryNames.add(name)) {
                    throw invalid("数据包包含重复文件项");
                }
                if (entry.isDirectory()) {
                    directoryNames.add(name);
                } else {
                    fileNames.add(name);
                }

                ByteArrayOutputStream captured = name.equals("manifest.json") || name.equals(selectedFileName)
                        ? new ByteArrayOutputStream() : null;
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    expandedBytes += read;
                    if (expandedBytes > properties.getDatapackCsvMaxFileBytes()) {
                        throw invalid("数据包解压后超过大小上限");
                    }
                    if (captured != null) {
                        long entryLimit = name.equals("manifest.json") ? MAX_MANIFEST_BYTES
                                : properties.getDatapackCsvMaxFileBytes();
                        if (entryBytes > entryLimit) {
                            throw invalid(name.equals("manifest.json")
                                    ? "数据包清单超过大小上限" : "CSV 文件超过大小上限");
                        }
                        captured.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
                if (name.equals("manifest.json")) {
                    manifestBytes = captured.toByteArray();
                } else if (name.equals(selectedFileName)) {
                    selectedCsv = captured.toByteArray();
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("数据包 ZIP 文件损坏");
        }

        if (manifestBytes == null) {
            throw invalid("数据包缺少根目录 manifest.json");
        }
        try {
            JsonNode manifest = objectMapper.readTree(manifestBytes);
            CsvArchive archive = validateCsvManifest(manifest, fileNames, directoryNames, selectedCsv);
            if (selectedFileName != null && archive.tables().stream()
                    .noneMatch(table -> table.fileName().equals(selectedFileName))) {
                throw invalid("所选 CSV 不属于当前数据包");
            }
            return archive;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("数据包清单无效或已损坏");
        } finally {
            Arrays.fill(manifestBytes, (byte) 0);
        }
    }

    private CsvArchive validateCsvManifest(
            JsonNode manifest, Set<String> zipFiles, Set<String> zipDirectories, byte[] selectedCsv) {
        if (manifest == null || !manifest.isObject()
                || !"1".equals(textual(manifest.get("protocolVersion"), 16))) {
            throw invalid("不支持的数据包协议版本或清单格式");
        }
        String packageType = text(manifest.get("packageType"), 32);
        if (!"TOTAL".equals(packageType)) {
            throw invalid("仅支持 TOTAL 类型的 CSV 数据包");
        }
        if (!"DRPK1".equals(text(manifest.get("encryptionFormat"), 16))
                || !"ZIP".equals(text(manifest.get("archiveFormat"), 16))
                || !"RFC4180".equals(text(manifest.get("csvDialect"), 32))
                || !"UTF-8".equalsIgnoreCase(text(manifest.get("csvEncoding"), 32))
                || !manifest.path("csvBom").isBoolean() || manifest.path("csvBom").booleanValue()
                || !",".equals(text(manifest.get("delimiter"), 8))
                || !"\"".equals(text(manifest.get("quoteCharacter"), 8))
                || !"\"\"".equals(text(manifest.get("quoteEscape"), 8))) {
            throw invalid("数据包 CSV 格式配置不受支持");
        }
        String nullValue = text(manifest.get("nullValue"), 256);
        // Validate required manifest formats without forwarding them as S3File options.
        String dateFormat = text(manifest.get("dateFormat"), 64);
        String dateTimeFormat = text(manifest.get("dateTimeFormat"), 128);
        if (dateFormat == null || dateTimeFormat == null) {
            throw invalid("数据包日期格式字段无效");
        }
        JsonNode rawTables = manifest.get("tables");
        if (nullValue == null || rawTables == null || !rawTables.isArray()
                || rawTables.isEmpty() || rawTables.size() > MAX_ZIP_ENTRIES) {
            throw invalid("数据包表清单无效或超过上限");
        }

        List<CsvTable> tables = new ArrayList<>();
        Set<String> declaredFiles = new LinkedHashSet<>();
        for (JsonNode rawTable : rawTables) {
            if (!rawTable.isObject()) {
                throw invalid("数据包表清单格式无效");
            }
            String fileName = text(rawTable.get("fileName"), 1024);
            validateZipMemberName(fileName, false);
            if ("manifest.json".equals(fileName) || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")
                    || !declaredFiles.add(fileName)) {
                throw invalid("数据包 CSV 文件名无效或重复");
            }
            JsonNode rowCount = rawTable.get("rowCount");
            JsonNode rawColumns = rawTable.get("columns");
            if (rowCount == null || !rowCount.isIntegralNumber() || !rowCount.canConvertToLong()
                    || rowCount.longValue() < 0 || rawColumns == null || !rawColumns.isArray()
                    || rawColumns.isEmpty() || rawColumns.size() > MAX_TABLE_COLUMNS) {
                throw invalid("数据包表记录数或字段清单无效");
            }
            List<CsvColumn> columns = new ArrayList<>();
            Set<Integer> ordinals = new HashSet<>();
            Set<String> names = new HashSet<>();
            for (JsonNode rawColumn : rawColumns) {
                if (!rawColumn.isObject()) {
                    throw invalid("数据包字段清单格式无效");
                }
                JsonNode ordinal = rawColumn.get("ordinalPosition");
                String name = text(rawColumn.get("name"), 256);
                String type = text(rawColumn.get("type"), 128);
                if (ordinal == null || !ordinal.isIntegralNumber() || !ordinal.canConvertToInt()
                        || ordinal.intValue() <= 0 || name == null || type == null
                        || !ordinals.add(ordinal.intValue()) || !names.add(name)) {
                    throw invalid("数据包字段名、类型或顺序无效");
                }
                columns.add(new CsvColumn(ordinal.intValue(), name,
                        toSeaTunnelType(type, rawColumn.get("size"), rawColumn.get("decimalDigits"))));
            }
            columns.sort(java.util.Comparator.comparingInt(CsvColumn::ordinalPosition));
            tables.add(new CsvTable(fileName, rowCount.longValue(), List.copyOf(columns)));
        }
        if (!zipFiles.contains("manifest.json")) {
            throw invalid("数据包缺少根目录 manifest.json");
        }
        Set<String> actualDataFiles = new LinkedHashSet<>(zipFiles);
        actualDataFiles.remove("manifest.json");
        if (!actualDataFiles.equals(declaredFiles)) {
            throw invalid("ZIP 文件项与数据包清单不匹配");
        }
        for (String directory : zipDirectories) {
            String prefix = directory.endsWith("/") ? directory : directory + "/";
            if (declaredFiles.stream().noneMatch(name -> name.startsWith(prefix))) {
                throw invalid("数据包包含未在清单中的目录");
            }
        }
        return new CsvArchive(List.copyOf(tables), nullValue, selectedCsv);
    }

    private static void validateZipMemberName(String name, boolean directory) {
        if (name == null || name.isBlank() || name.length() > 1024 || name.startsWith("/")
                || name.contains("\\") || name.contains(":")
                || name.chars().anyMatch(Character::isISOControl)) {
            throw invalid("数据包包含不安全的 ZIP 路径");
        }
        String value = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (value.isBlank()) {
            throw invalid("数据包包含不安全的 ZIP 路径");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("数据包包含不安全的 ZIP 路径");
            }
        }
        if (directory && !name.endsWith("/")) {
            throw invalid("数据包目录项格式无效");
        }
    }

    private static String textual(JsonNode node, int maxLength) {
        if (node == null || (!node.isTextual() && !node.isIntegralNumber())) {
            return null;
        }
        String value = node.asText();
        return value.length() > maxLength || value.chars().anyMatch(Character::isISOControl) ? null : value;
    }

    private static String toSeaTunnelType(String sourceType, JsonNode size, JsonNode decimalDigits) {
        String type = sourceType.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s*\\(.*\\)$", "");
        if (type.contains("char") || type.contains("text") || type.contains("string")
                || type.contains("clob") || type.contains("json") || type.contains("uuid")) return "string";
        if (type.contains("bool")) return "boolean";
        if (type.equals("tinyint") || type.equals("byte")) return "byte";
        if (type.equals("smallint") || type.equals("short")) return "short";
        if (type.equals("int") || type.equals("integer") || type.equals("mediumint")) return "int";
        if (type.equals("bigint") || type.equals("long")) return "long";
        if (type.contains("decimal") || type.contains("numeric")) {
            return decimalType(size, decimalDigits);
        }
        if (type.equals("float") || type.equals("real")) return "float";
        if (type.equals("double")) return "double";
        if (type.equals("date")) return "date";
        if (type.equals("time")) return "time";
        if (type.contains("timestamp") || type.equals("datetime")) return "timestamp";
        return "string";
    }

    /** Builds the explicit precision/scale syntax required by SeaTunnel 3.0.0's DecimalType parser. */
    private static String decimalType(JsonNode precisionNode, JsonNode scaleNode) {
        if (precisionNode == null || !precisionNode.isIntegralNumber() || !precisionNode.canConvertToInt()
                || scaleNode == null || !scaleNode.isIntegralNumber() || !scaleNode.canConvertToInt()) {
            throw invalid("数据包 DECIMAL 字段缺少有效精度或小数位数");
        }
        int precision = precisionNode.intValue();
        int scale = scaleNode.intValue();
        if (precision < 1 || precision > MAX_DECIMAL_PRECISION || scale < 0
                || scale > Math.min(precision, MAX_DECIMAL_SCALE)) {
            throw invalid("数据包 DECIMAL 字段精度或小数位数超出范围");
        }
        return "decimal(" + precision + "," + scale + ")";
    }

    private javax.crypto.SecretKey deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec specification = new PBEKeySpec(password, salt, properties.getDatapackPbkdf2Iterations(), 128);
        byte[] encoded = null;
        try {
            encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } finally {
            specification.clearPassword();
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    private static String text(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.textValue();
        return value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl) ? null : value;
    }

    private static String validCode(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("数据包表单或字段编码无效");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    public record FormSummary(String formCode, int recordCount, List<String> fields) {
    }

    public record CsvArchiveInspection(String fingerprint, List<String> fileNames) {
    }

    public record CsvSelection(String fileName, byte[] content, Map<String, String> schema, String nullValue) {
    }

    private record CsvArchive(List<CsvTable> tables, String nullValue, byte[] selectedCsv) {
    }

    private record CsvTable(String fileName, long rowCount, List<CsvColumn> columns) {
    }

    private record CsvColumn(int ordinalPosition, String name, String seatunnelType) {
    }

    public record Inspection(
            String fingerprint,
            String packageId,
            String packageType,
            int recordCount,
            List<FormSummary> forms,
            JsonNode records) {
    }
}
