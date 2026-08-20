package org.example.naeilbank.domain.notification;

import org.example.naeilbank.global.config.properties.WebPushEncryptionProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class WebPushCipher {
    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebPushCipher(WebPushEncryptionProperties properties) {
        byte[] rawKey = Base64.getDecoder().decode(properties.encryptionKey());
        if (rawKey.length != 32) {
            throw new IllegalArgumentException("Web Push encryption key must contain 32 bytes");
        }
        this.key = new SecretKeySpec(rawKey, "AES");
    }

    String encrypt(String purpose, String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, purpose, iv);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return VERSION + "." + encoder.encodeToString(iv) + "." + encoder.encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt Web Push subscription data", e);
        }
    }

    String decrypt(String purpose, String encoded) {
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("Unsupported Web Push ciphertext format");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[1]);
            if (iv.length != IV_BYTES) {
                throw new IllegalStateException("Invalid Web Push ciphertext nonce");
            }
            byte[] plaintext = cipher(Cipher.DECRYPT_MODE, purpose, iv).doFinal(decoder.decode(parts[2]));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to decrypt Web Push subscription data", e);
        }
    }

    private Cipher cipher(int mode, String purpose, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
