package com.hnp.filemanagement.shared.config;

import com.hnp.filemanagement.shared.config.FileManagementProperties.ShareLinks.PasswordPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The settings tree (roadmap 2.1, {@code docs/issues.md} issue 27): that every default is what
 * the documentation says, that a value binds where it is written, and that a value out of range
 * fails the binding rather than the hundredth request.
 *
 * <p>Bound by hand rather than through a context, so the test is a unit test: no Spring, no
 * Docker, and one place to read what the application can be configured with.
 */
class FileManagementPropertiesTest {

    @Test
    @DisplayName("nothing but the storage root is required, and every default is the documented one")
    void defaults() {
        FileManagementProperties properties = bind(Map.of("filemanagement.base-dir", "D:/files/"));

        assertThat(properties.baseDir()).isEqualTo("D:/files/");
        assertThat(properties.defaults().pageSize()).isEqualTo(50);
        assertThat(properties.folderAccess().enabled()).isFalse();
        assertThat(properties.folders().maxDepth()).isEqualTo(6);
        assertThat(properties.folders().maxDeleteFiles()).isEqualTo(1000);
        assertThat(properties.profiles().defaultQuotaMb()).isZero();
        assertThat(properties.profiles().defaultQuotaBytes()).as("0 megabytes is no quota at all").isNull();
        assertThat(properties.storage().sweepEnabled()).as("a sweep nobody asked for still has to run").isTrue();
        assertThat(properties.storage().sweepEveryMinutes()).isEqualTo(15);
        assertThat(properties.storage().unfinishedAfterMinutes()).isEqualTo(60);
        assertThat(properties.storage().sweepBatchSize()).isEqualTo(200);
        assertThat(properties.shareLinks().maxMinutes()).isEqualTo(1440);
        assertThat(properties.shareLinks().defaultMinutes()).isEqualTo(60);
        assertThat(properties.shareLinks().password()).isEqualTo(PasswordPolicy.OPTIONAL);
        assertThat(properties.shareLinks().passwordRequired()).isFalse();
        assertThat(properties.shareLinks().maxFailedAttempts()).isEqualTo(5);
        assertThat(properties.shareLinks().lockMinutes()).isEqualTo(15);
        assertThat(properties.bootstrap().adminPassword()).as("no default: an installation that does not set one gets no administrator").isEmpty();
        assertThat(properties.auth().ldap().activedirectory().enabled()).isFalse();
        assertThat(properties.auth().ldap().activedirectory().truststoreType()).isEqualTo("PKCS12");
        assertThat(properties.auth().ldap().activedirectory().verifyCertificate()).isTrue();
        assertThat(properties.auth().ldap().activedirectory().verifyHostname()).isTrue();
        assertThat(properties.auth().ldap().activedirectory().connectTimeoutMs()).isEqualTo(5000);
        assertThat(properties.auth().ldap().activedirectory().readTimeoutMs()).isEqualTo(10000);
        assertThat(properties.downloads().enabled()).as("downloads are recorded unless switched off").isTrue();
        assertThat(properties.downloads().retentionDays()).isEqualTo(365);
    }

    @Test
    @DisplayName("the record of downloads is switched and kept by filemanagement.downloads.*; a negative retention is refused")
    void downloads() {
        FileManagementProperties properties = bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.downloads.enabled", "false",
                "filemanagement.downloads.retention-days", "90"));
        assertThat(properties.downloads().enabled()).isFalse();
        assertThat(properties.downloads().retentionDays()).isEqualTo(90);
        assertThat(bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.downloads.retention-days", "0")).downloads().retentionDays())
                .as("0 keeps them for ever").isZero();

        assertThatThrownBy(() -> bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.downloads.retention-days", "-1")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(BindValidationException.class)
                .rootCause().hasMessageContaining("retentionDays");
    }

    @Test
    @DisplayName("every setting binds under the name the documentation gives it")
    void binding() {
        FileManagementProperties properties = bind(Map.ofEntries(
                Map.entry("filemanagement.base-dir", "E:/data/"),
                Map.entry("filemanagement.default.page-size", "25"),
                Map.entry("filemanagement.folder-access.enabled", "true"),
                Map.entry("filemanagement.folders.max-depth", "4"),
                Map.entry("filemanagement.folders.max-delete-files", "50"),
                Map.entry("filemanagement.profiles.default-quota-mb", "100"),
                Map.entry("filemanagement.storage.sweep-enabled", "false"),
                Map.entry("filemanagement.storage.sweep-every-minutes", "5"),
                Map.entry("filemanagement.storage.unfinished-after-minutes", "20"),
                Map.entry("filemanagement.storage.sweep-batch-size", "10"),
                Map.entry("filemanagement.share-links.max-minutes", "30"),
                Map.entry("filemanagement.share-links.default-minutes", "5"),
                Map.entry("filemanagement.share-links.password", "REQUIRED"),
                Map.entry("filemanagement.share-links.max-failed-attempts", "2"),
                Map.entry("filemanagement.share-links.lock-minutes", "1"),
                Map.entry("filemanagement.bootstrap.admin-password", "s3cret"),
                Map.entry("filemanagement.auth.ldap.activedirectory.enabled", "true"),
                Map.entry("filemanagement.auth.ldap.activedirectory.domain", "example.test"),
                Map.entry("filemanagement.auth.ldap.activedirectory.url", "ldaps://dc.example.test"),
                Map.entry("filemanagement.auth.ldap.activedirectory.verify-certificate", "false")));

        assertThat(properties.baseDir()).isEqualTo("E:/data/");
        assertThat(properties.defaults().pageSize()).isEqualTo(25);
        assertThat(properties.folderAccess().enabled()).isTrue();
        assertThat(properties.folders().maxDepth()).isEqualTo(4);
        assertThat(properties.folders().maxDeleteFiles()).isEqualTo(50);
        assertThat(properties.profiles().defaultQuotaBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(properties.storage().sweepEnabled()).isFalse();
        assertThat(properties.storage().sweepEveryMinutes()).isEqualTo(5);
        assertThat(properties.storage().unfinishedAfterMinutes()).isEqualTo(20);
        assertThat(properties.storage().sweepBatchSize()).isEqualTo(10);
        assertThat(properties.shareLinks().maxMinutes()).isEqualTo(30);
        assertThat(properties.shareLinks().defaultMinutes()).isEqualTo(5);
        assertThat(properties.shareLinks().passwordRequired()).isTrue();
        assertThat(properties.shareLinks().maxFailedAttempts()).isEqualTo(2);
        assertThat(properties.shareLinks().lockMinutes()).isEqualTo(1);
        assertThat(properties.bootstrap().adminPassword()).isEqualTo("s3cret");
        assertThat(properties.auth().ldap().activedirectory().domain()).isEqualTo("example.test");
        assertThat(properties.auth().ldap().activedirectory().url()).isEqualTo("ldaps://dc.example.test");
        assertThat(properties.auth().ldap().activedirectory().verifyCertificate()).isFalse();
    }

    @Test
    @DisplayName("the files are on the filesystem unless the backend says s3, whose settings bind under storage.s3")
    void storageBackend() {
        FileManagementProperties unset = bind(Map.of("filemanagement.base-dir", "D:/files/"));
        assertThat(unset.storage().backend()).isEqualTo(FileManagementProperties.Storage.Backend.FILESYSTEM);
        assertThat(unset.storage().s3().region()).isEqualTo("us-east-1");
        assertThat(unset.storage().s3().pathStyleAccess()).isTrue();
        assertThat(unset.storage().s3().partSizeMb()).isEqualTo(16);
        assertThat(unset.storage().s3().maxConnections()).as("as many as the server's request threads").isEqualTo(200);
        assertThat(unset.storage().s3().timeouts())
                .isEqualTo(new FileManagementProperties.Timeouts(2, 30, 5, 15, 3));
        assertThat(unset.storage().s3().missing()).containsExactly("FILEMANAGEMENT_S3_ENDPOINT",
                "FILEMANAGEMENT_S3_BUCKET", "FILEMANAGEMENT_S3_ACCESS_KEY", "FILEMANAGEMENT_S3_SECRET_KEY");

        FileManagementProperties s3 = bind(Map.of(
                "filemanagement.base-dir", "D:/files/",
                "filemanagement.storage.backend", "s3",
                "filemanagement.storage.s3.endpoint", "http://storage:8333",
                "filemanagement.storage.s3.bucket", "file-management-prod",
                "filemanagement.storage.s3.access-key", "the-key",
                "filemanagement.storage.s3.secret-key", "the-secret",
                "filemanagement.storage.s3.path-style-access", "false",
                "filemanagement.storage.s3.prefix", "app",
                "filemanagement.storage.s3.part-size-mb", "32",
                "filemanagement.storage.s3.max-connections", "300"));
        assertThat(s3.storage().backend()).isEqualTo(FileManagementProperties.Storage.Backend.S3);
        assertThat(s3.storage().s3().endpoint()).isEqualTo("http://storage:8333");
        assertThat(s3.storage().s3().bucket()).isEqualTo("file-management-prod");
        assertThat(s3.storage().s3().pathStyleAccess()).isFalse();
        assertThat(s3.storage().s3().prefix()).isEqualTo("app");
        assertThat(s3.storage().s3().partSizeMb()).isEqualTo(32);
        assertThat(s3.storage().s3().maxConnections()).isEqualTo(300);
        FileManagementProperties.Timeouts timeouts = bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.storage.s3.timeouts.attempt-seconds", "7")).storage().s3().timeouts();
        assertThat(timeouts.attemptSeconds()).isEqualTo(7);
        assertThat(timeouts.callSeconds()).as("the others keep their defaults").isEqualTo(15);
        assertThatThrownBy(() -> bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.storage.s3.timeouts.health-seconds", "0")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("healthSeconds");
        assertThat(s3.storage().s3().missing()).isEmpty();
        assertThat(s3.storage().s3().toString()).contains("the-key").doesNotContain("the-secret");

        assertThatThrownBy(() -> bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.storage.backend", "disk")))
                .as("a backend that does not exist stops the start").isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.storage.s3.part-size-mb", "4")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("partSizeMb");
    }

    @Test
    @DisplayName("a value out of range fails the binding - the start, not the hundredth request")
    void validation() {
        // The binder wraps the validation failure; what matters is that the value is refused
        // where it is written, and that the message says which setting and why.
        assertThatThrownBy(() -> bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.folders.max-depth", "0")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(BindValidationException.class)
                .rootCause().hasMessageContaining("maxDepth");
        assertThatThrownBy(() -> bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.share-links.max-minutes", "0")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(BindValidationException.class)
                .rootCause().hasMessageContaining("maxMinutes");
        assertThatThrownBy(() -> bind(Map.of("filemanagement.base-dir", "D:/files/",
                "filemanagement.default.page-size", "0")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(BindValidationException.class)
                .rootCause().hasMessageContaining("pageSize");
    }

    private static FileManagementProperties bind(Map<String, String> properties) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        try {
            return new Binder(new MapConfigurationPropertySource(properties))
                    .bind("filemanagement", org.springframework.boot.context.properties.bind.Bindable.of(FileManagementProperties.class),
                            new ValidationBindHandler(validator))
                    .orElseThrow(() -> new IllegalStateException("nothing bound"));
        } finally {
            validator.destroy();
        }
    }
}
