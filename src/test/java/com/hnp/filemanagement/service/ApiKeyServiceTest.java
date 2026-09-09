package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.ApiKeyCreatedDTO;
import com.hnp.filemanagement.dto.ApiKeyDTO;
import com.hnp.filemanagement.dto.FolderAccess;
import com.hnp.filemanagement.entity.ApiKey;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.ApiKeyRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The API key lifecycle (roadmap 9.2).
 *
 * <p>Folder-access enforcement is switched on, because a key's whole point is that it reaches part
 * of the tree and not the rest — and with the flag off every access answer is "everything", which
 * would let a broken scope pass unnoticed.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class ApiKeyServiceTest extends MySqlSupport {

    @Autowired
    private ApiKeyService underTest;
    @Autowired
    private FolderAccessService folderAccessService;

    @Autowired
    private ApiKeyRepository apiKeyRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;

    private int principalId;
    private int rootFolderId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();
        rootFolderId = folderRepository.findRoots().getFirst().getId();
    }

    // ---------------------------------------------------------------- creating

    @Test
    @DisplayName("creating a key returns the credential, and the credential is not what is stored")
    void theSecretIsReturnedOnceAndStoredOnlyAsAHash() {
        ApiKeyCreatedDTO created = underTest.create(request("nightly import", null), principalId);

        assertThat(created.credential()).startsWith("fmk_" + created.keyId() + "_");

        ApiKey stored = apiKeyRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getSecretHash())
                .as("a 64-character hex digest, and nothing that appears in the credential")
                .hasSize(64);
        assertThat(created.credential()).doesNotContain(stored.getSecretHash());
    }

    @Test
    @DisplayName("two keys made from the same request are different credentials")
    void everyKeyIsItsOwnSecret() {
        ApiKeyCreatedDTO first = underTest.create(request("one", null), principalId);
        ApiKeyCreatedDTO second = underTest.create(request("two", null), principalId);

        assertThat(first.keyId()).isNotEqualTo(second.keyId());
        assertThat(first.credential()).isNotEqualTo(second.credential());
    }

    @Test
    @DisplayName("an expiry in the past is refused rather than stored and immediately useless")
    void anExpiryThatHasAlreadyPassedIsRefused() {
        assertThatThrownBy(() -> underTest.create(request("stale", LocalDate.now().minusDays(1)), principalId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.create(request("today", LocalDate.now()), principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("no expiry is a key that does not expire, which has to be expressible")
    void expiryIsOptional() {
        int id = underTest.create(request("forever", null), principalId).id();

        assertThat(apiKeyRepository.findById(id).orElseThrow().getExpiresAt()).isNull();
        assertThat(underTest.getById(id).isUsable()).isTrue();
    }

    /** "Expires on the 5th" has to mean the 5th still works, or a key dies a day early. */
    @Test
    @DisplayName("the expiry date is inclusive")
    void theExpiryDayItselfIsStillUsable() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        int id = underTest.create(request("until tomorrow", tomorrow), principalId).id();

        ApiKey stored = apiKeyRepository.findById(id).orElseThrow();
        assertThat(stored.isUsableAt(tomorrow.atTime(23, 59))).isTrue();
        assertThat(stored.isUsableAt(tomorrow.plusDays(1).atStartOfDay())).isFalse();
    }

    // ---------------------------------------------------------------- authenticating

    @Test
    @DisplayName("the credential authenticates, and anything else does not")
    void authenticationAcceptsOnlyTheRealCredential() {
        ApiKeyCreatedDTO created = underTest.create(request("importer", null), principalId);

        assertThat(underTest.authenticate(created.credential()))
                .map(ApiKey::getId).contains(created.id());

        assertThat(underTest.authenticate(null)).isEmpty();
        assertThat(underTest.authenticate("")).isEmpty();
        assertThat(underTest.authenticate("not-a-key")).isEmpty();
        assertThat(underTest.authenticate("fmk_" + created.keyId())).as("no secret").isEmpty();
        assertThat(underTest.authenticate("fmk_" + created.keyId() + "_wrong")).as("wrong secret").isEmpty();
        assertThat(underTest.authenticate("fmk_nosuchkey_" + created.credential())).as("unknown id").isEmpty();
    }

    /**
     * A credential is split on the first underscore after {@code fmk_}, so the key id must not be
     * able to contain one. It used to: both halves were base64url, whose alphabet includes the
     * underscore, and roughly one key in four came out unusable — intermittently, and only in
     * production, because nothing in the code says which random bytes it will get.
     */
    @Test
    @DisplayName("every generated credential can be taken apart again")
    void everyGeneratedCredentialParsesBack() {
        List<ApiKeyCreatedDTO> keys = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> underTest.create(request("batch" + i, null), principalId))
                .toList();

        assertThat(keys).allSatisfy(key -> {
            assertThat(key.keyId()).as("the key id must not contain the separator")
                    .matches("[0-9a-f]+");
            assertThat(underTest.authenticate(key.credential()))
                    .as("credential %s", key.credential())
                    .isNotEmpty();
        });
    }

    @Test
    @DisplayName("a revoked key stops authenticating and cannot be switched back on")
    void revokingIsFinal() {
        ApiKeyCreatedDTO created = underTest.create(request("leaked", null), principalId);

        underTest.revoke(created.id(), principalId);

        assertThat(underTest.authenticate(created.credential())).isEmpty();
        assertThatThrownBy(() -> underTest.changeEnabled(created.id(), true, principalId))
                .isInstanceOf(InvalidDataException.class);
        assertThat(apiKeyRepository.findById(created.id()))
                .as("the row stays, so the audit trail still resolves")
                .isPresent();
    }

    @Test
    @DisplayName("switching a key off is reversible; the credential is unchanged either way")
    void disablingIsReversible() {
        ApiKeyCreatedDTO created = underTest.create(request("paused", null), principalId);

        underTest.changeEnabled(created.id(), false, principalId);
        assertThat(underTest.authenticate(created.credential())).isEmpty();

        underTest.changeEnabled(created.id(), true, principalId);
        assertThat(underTest.authenticate(created.credential())).isNotEmpty();
    }

    @Test
    @DisplayName("an expired key stops authenticating without anybody doing anything")
    void anExpiredKeyStopsWorking() {
        ApiKeyCreatedDTO created = underTest.create(request("short lived", LocalDate.now().plusDays(1)), principalId);

        ApiKey stored = apiKeyRepository.findById(created.id()).orElseThrow();
        stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        assertThat(underTest.authenticate(created.credential())).isEmpty();
    }

    @Test
    @DisplayName("using a key records that it was used")
    void usingAKeyStampsIt() {
        ApiKeyCreatedDTO created = underTest.create(request("watched", null), principalId);
        assertThat(apiKeyRepository.findById(created.id()).orElseThrow().getLastUsedAt()).isNull();

        underTest.authenticate(created.credential());

        assertThat(apiKeyRepository.findById(created.id()).orElseThrow().getLastUsedAt()).isNotNull();
    }

    // ---------------------------------------------------------------- scopes

    @Test
    @DisplayName("a key reaches the folders granted to it, with the verb it was granted")
    void theKeyCarriesItsOwnFolderAccess() {
        String path = folderRepository.findById(rootFolderId).orElseThrow().getPath();
        int id = underTest.create(request("scoped", null, rootFolderId + ":WRITE"), principalId).id();

        FolderAccess access = folderAccessService.accessForApiKey(id);

        assertThat(access.unrestricted()).isFalse();
        assertThat(access.canRead(path)).isTrue();
        assertThat(access.canWrite(path)).isTrue();
    }

    /**
     * The distinction the whole design rests on: a key is scoped to what it was granted, not to what
     * the person who created it happens to reach.
     */
    @Test
    @DisplayName("a key with no scopes reaches nothing, however powerful its creator is")
    void aKeyWithoutScopesReachesNothing() {
        int id = underTest.create(request("empty", null), principalId).id();

        FolderAccess access = folderAccessService.accessForApiKey(id);

        assertThat(access.isEmpty()).isTrue();
        assertThat(access.canRead("/1/")).isFalse();
    }

    @Test
    @DisplayName("editing a key changes its scopes without changing its credential")
    void scopesCanBeChangedWithoutReissuing() {
        ApiKeyCreatedDTO created = underTest.create(request("growing", null), principalId);
        assertThat(folderAccessService.accessForApiKey(created.id()).isEmpty()).isTrue();

        underTest.update(created.id(), request("growing", null, rootFolderId + ":READ"), principalId);

        assertThat(folderAccessService.accessForApiKey(created.id()).isEmpty()).isFalse();
        assertThat(underTest.authenticate(created.credential()))
                .as("the same credential still works")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a scope naming a folder that does not exist is refused")
    void anUnknownFolderIsRefused() {
        assertThatThrownBy(() -> underTest.create(request("bad", null, "999999:READ"), principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("the list never carries a secret, because none is stored")
    void theListCannotLeakASecret() {
        underTest.create(request("listed", null), principalId);

        assertThat(underTest.getAll()).isNotEmpty().allSatisfy(dto -> {
            assertThat(dto.getKeyId()).isNotBlank();
            assertThat(dto.getTitle()).isNotBlank();
        });
    }

    // ---------------------------------------------------------------- helpers

    private ApiKeyDTO request(String title, LocalDate expiresAt, String... grants) {
        ApiKeyDTO dto = new ApiKeyDTO();
        dto.setTitle(title + TestData.nextSequence());
        dto.setDescription("used by " + title);
        dto.setExpiresAt(expiresAt);
        dto.setFolderGrants(List.of(grants));
        return dto;
    }
}
