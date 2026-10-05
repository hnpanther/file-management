package com.hnp.filemanagement.identity.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keeps an S3 key's secret recoverable, and only that (roadmap 9.10.2). Signature V4 is an HMAC the
 * server recomputes, so it needs the secret itself - where a V1 key's is kept only as a hash. The
 * secret is encrypted with AES-256-GCM under a master key that never reaches the database:
 * {@code filemanagement.s3-api.secret-encryption-key}, base64 of 32 random bytes, from
 * {@code FILEMANAGEMENT_S3_SECRET_ENCRYPTION_KEY}. Without it no S3 key can be made or used - said,
 * never silently.
 *
 * <p>Stored as {@code v1:} and the base64 of a fresh 12-byte nonce followed by the ciphertext and
 * its tag; the key's id is the associated data, so a ciphertext copied onto another key's row does
 * not decrypt.
 */
@Component
public class S3SecretCipher {

    private static final String FORMAT = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public S3SecretCipher(@Value("${filemanagement.s3-api.secret-encryption-key:}") String base64Key) {
        SecretKeySpec parsed = null;
        if (base64Key != null && !base64Key.isBlank()) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(base64Key.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("filemanagement.s3-api.secret-encryption-key is not base64");
            }
            if (bytes.length != 32) {
                throw new IllegalStateException("filemanagement.s3-api.secret-encryption-key must be 32 bytes (base64 of"
                        + " 32 random bytes: openssl rand -base64 32); it is " + bytes.length);
            }
            parsed = new SecretKeySpec(bytes, "AES");
        }
        this.key = parsed;
    }

    /** Whether S3 keys can be made and used here: the master key is set. */
    public boolean configured() {
        return key != null;
    }

    public String encrypt(String secret, String keyId) {
        requireConfigured();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return FORMAT + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the S3 secret could not be encrypted", e);
        }
    }

    public String decrypt(String stored, String keyId) {
        requireConfigured();
        if (stored == null || !stored.startsWith(FORMAT)) {
            throw new IllegalStateException("not an encrypted S3 secret");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(FORMAT.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A different master key, or a tampered row: not a secret this installation made.
            throw new IllegalStateException("the S3 secret of key " + keyId + " does not decrypt with this master key");
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException("S3 keys need filemanagement.s3-api.secret-encryption-key"
                    + " (FILEMANAGEMENT_S3_SECRET_ENCRYPTION_KEY)");
        }
    }
}
