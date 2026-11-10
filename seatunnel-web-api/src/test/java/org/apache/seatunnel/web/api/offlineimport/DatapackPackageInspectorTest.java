package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
}
