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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
        if (encrypted == null || encrypted.length < HEADER_LENGTH + TAG_LENGTH_BYTES
                || encrypted.length > properties.getDatapackMaxFileBytes()) {
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
                || ciphertextLength > properties.getDatapackMaxFileBytes()) {
            throw invalid("数据包声明长度无效");
        }
        if (password == null || password.length == 0) {
            throw invalid("请输入数据包密码");
        }

        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
            plaintext = cipher.doFinal(encrypted, HEADER_LENGTH, ciphertextLength);
            if (plaintext.length > properties.getDatapackMaxFileBytes()) {
                throw invalid("数据包明文超过大小上限");
            }
            return validatePayload(objectMapper.readTree(plaintext), fingerprint(encrypted));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            // Authentication and parser errors share a generic message to avoid exposing untrusted input.
            throw invalid("密码错误或数据包已损坏");
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
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

    public record Inspection(
            String fingerprint,
            String packageId,
            String packageType,
            int recordCount,
            List<FormSummary> forms,
            JsonNode records) {
    }
}
