package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.identity.domain.S3SecretCipher;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * A subscription's secrets (roadmap 13.18): the signing secret, made here - shown once, then stored encrypted - and
 * the receiver's own credential, typed by a person. Both under the S3 keys' master key
 * ({@code filemanagement.s3-api.secret-encryption-key}, {@link S3SecretCipher}), each bound to its subscription and
 * its use: a ciphertext copied to another row, or from one column to the other, does not decrypt.
 */
@Component
public class WebhookSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final S3SecretCipher cipher;

    public WebhookSecrets(S3SecretCipher cipher) {
        this.cipher = cipher;
    }

    /** Whether secrets can be kept: the master key is set. */
    public boolean configured() {
        return cipher.configured();
    }

    /** A new signing secret: {@code whsec_} and 32 random bytes, base64url. */
    public static String newSigningSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String sealSigning(int webhookId, String secret) {
        return cipher.encrypt(secret, signingId(webhookId));
    }

    public String openSigning(int webhookId, String stored) {
        return cipher.decrypt(stored, signingId(webhookId));
    }

    public String sealAuth(int webhookId, String secret) {
        return cipher.encrypt(secret, authId(webhookId));
    }

    public String openAuth(int webhookId, String stored) {
        return cipher.decrypt(stored, authId(webhookId));
    }

    /** The subscription as the sender needs it, its secrets opened - the previous one only while it is honoured. */
    public WebhookSender.Target targetOf(Webhook webhook, Instant now) {
        int id = webhook.getId();
        String previous = webhook.getPreviousSigningSecret() != null && webhook.getPreviousSecretUntil() != null
                && webhook.getPreviousSecretUntil().isAfter(now)
                ? openSigning(id, webhook.getPreviousSigningSecret()) : null;
        return new WebhookSender.Target(id, webhook.getUrl(), openSigning(id, webhook.getSigningSecret()), previous,
                webhook.getAuthKind(), webhook.getAuthName(),
                webhook.getAuthSecret() == null ? null : openAuth(id, webhook.getAuthSecret()));
    }

    private static String signingId(int webhookId) {
        return "webhook:" + webhookId + ":signing";
    }

    private static String authId(int webhookId) {
        return "webhook:" + webhookId + ":auth";
    }
}
