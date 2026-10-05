package com.hnp.filemanagement.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An S3 key's secret, kept encrypted under the installation's master key (roadmap 9.10.2). */
class S3SecretCipherTest {

    private static final String MASTER = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    @DisplayName("round trip; a fresh nonce each time; the secret itself never in what is stored")
    void roundTrip() {
        S3SecretCipher cipher = new S3SecretCipher(MASTER);
        String a = cipher.encrypt("my-secret-value", "FMKEY1");
        String b = cipher.encrypt("my-secret-value", "FMKEY1");

        assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("my-secret-value");
        assertThat(cipher.decrypt(a, "FMKEY1")).isEqualTo("my-secret-value");
        assertThat(cipher.decrypt(b, "FMKEY1")).isEqualTo("my-secret-value");
    }

    @Test
    @DisplayName("refused: another key's row, another master key, a changed ciphertext, an unknown format")
    void refusals() {
        S3SecretCipher cipher = new S3SecretCipher(MASTER);
        String stored = cipher.encrypt("my-secret-value", "FMKEY1");

        assertThatThrownBy(() -> cipher.decrypt(stored, "FMKEY2")).isInstanceOf(RuntimeException.class);
        byte[] other = new byte[32];
        other[0] = 1;
        assertThatThrownBy(() -> new S3SecretCipher(Base64.getEncoder().encodeToString(other)).decrypt(stored, "FMKEY1"))
                .isInstanceOf(RuntimeException.class);
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(raw), "FMKEY1"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cipher.decrypt("plain", "FMKEY1")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("without a master key nothing is made; a wrong one stops the start, saying why")
    void configuration() {
        S3SecretCipher none = new S3SecretCipher("");
        assertThat(none.configured()).isFalse();
        assertThatThrownBy(() -> none.encrypt("x", "k")).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new S3SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new S3SecretCipher("not base64 !!")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not base64 !!");
    }
}
