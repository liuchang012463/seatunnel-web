package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.springframework.stereotype.Component;

/** Encrypts the OIDC client secret at rest with an installation-owned key. */
@Component
public class UnifiedAuthSecretCipher {

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        try {
            return PasswordUtils.encodePassword(plaintext);
        } catch (RuntimeException error) {
            throw new IllegalStateException("OIDC client secret could not be encrypted", error);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        try {
            return PasswordUtils.decodePassword(ciphertext);
        } catch (RuntimeException error) {
            throw new IllegalStateException("OIDC client secret could not be decrypted", error);
        }
    }
}
