package com.hnp.filemanagement.identity.domain;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.ApiKeyRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A key's life after it stops (roadmap 9.11): an expired key renewed, with its secret; a revoked one
 * never brought back, but replaced - a new key, a new secret, everything else carried over.
 */
@ServiceIntegrationTest
class ApiKeyLifecycleTest extends DatabaseSupport {

    @Autowired
    private ApiKeyService underTest;
    @Autowired
    private ApiKeyRepository apiKeyRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private Clock clock;

    private int principalId;
    private Folder folder;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();
        folder = FolderFixture.chain(folderRepository, tagGroupRepository, creator).tag();
    }

    // ---------------------------------------------------------------- renewing

    @Test
    @DisplayName("an expired key renewed is accepted again with the same secret; the list says expired before and not after")
    void renewingBringsAnExpiredKeyBack() {
        ApiKeyCreatedDTO created = underTest.create(request("expiring", LocalDate.now(clock).plusDays(3)), principalId);
        expire(created.id());
        assertThat(underTest.authenticate(created.credential())).as("past its date").isEmpty();
        assertThat(listed(created.id()).isExpired()).isTrue();
        assertThat(listed(created.id()).isUsable()).isFalse();

        underTest.renew(created.id(), LocalDate.now(clock).plusDays(30), principalId);
        entityManager.flush();
        entityManager.clear();

        assertThat(underTest.authenticate(created.credential())).as("the same credential").isPresent();
        assertThat(listed(created.id()).isExpired()).isFalse();
        assertThat(listed(created.id()).getExpiresAt()).isEqualTo(LocalDate.now(clock).plusDays(30));
        assertThat(history(created.id())).anyMatch(entry -> entry.startsWith("RENEW API_KEY"));
    }

    @Test
    @DisplayName("renewed to no expiry, a key stays usable; renewing does not switch a disabled key on")
    void renewingToNoExpiry() {
        ApiKeyCreatedDTO created = underTest.create(request("forever", LocalDate.now(clock).plusDays(3)), principalId);
        expire(created.id());
        underTest.changeEnabled(created.id(), false, principalId);

        underTest.renew(created.id(), null, principalId);
        entityManager.flush();
        entityManager.clear();

        assertThat(apiKeyRepository.findById(created.id()).orElseThrow().getExpiresAt()).isNull();
        assertThat(underTest.authenticate(created.credential())).as("still switched off").isEmpty();
        underTest.changeEnabled(created.id(), true, principalId);
        assertThat(underTest.authenticate(created.credential())).isPresent();
    }

    @Test
    @DisplayName("a revoked key is not renewed, and a date not in the future is refused - each with a reason a person can read")
    void renewingRefusals() {
        ApiKeyCreatedDTO revoked = underTest.create(request("burned", null), principalId);
        underTest.revoke(revoked.id(), principalId);
        assertThatThrownBy(() -> underTest.renew(revoked.id(), LocalDate.now(clock).plusDays(5), principalId))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).hasValue("apiKey.renew.revoked"));
        assertThat(underTest.authenticate(revoked.credential())).as("and it stays burned").isEmpty();

        ApiKeyCreatedDTO live = underTest.create(request("live", null), principalId);
        for (LocalDate notAhead : List.of(LocalDate.now(clock), LocalDate.now(clock).minusDays(1))) {
            assertThatThrownBy(() -> underTest.renew(live.id(), notAhead, principalId))
                    .isInstanceOf(InvalidDataException.class)
                    .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).hasValue("apiKey.expiry.past"));
        }
    }

    // ---------------------------------------------------------------- replacing

    @Test
    @DisplayName("a revoked V1 key is replaced: a new key and secret, its title, folders and date carried over; the old one stays burned")
    void aReplacementCarriesEverythingButTheSecret() {
        LocalDate lastDay = LocalDate.now(clock).plusDays(60);
        ApiKeyCreatedDTO old = underTest.create(request("erp import", lastDay, folder.getId() + ":WRITE"), principalId);
        underTest.revoke(old.id(), principalId);

        ApiKeyCreatedDTO replacement = underTest.reissue(old.id(), principalId);
        entityManager.flush();
        entityManager.clear();

        assertThat(replacement.id()).isNotEqualTo(old.id());
        assertThat(replacement.keyId()).isNotEqualTo(old.keyId());
        assertThat(replacement.credential()).startsWith("fmk_").isNotEqualTo(old.credential());
        assertThat(underTest.authenticate(replacement.credential())).isPresent();
        assertThat(underTest.authenticate(old.credential())).as("the revoked key is never back").isEmpty();

        ApiKeyDTO was = underTest.getByIdWithGrants(old.id());
        ApiKeyDTO now = underTest.getByIdWithGrants(replacement.id());
        assertThat(now.getTitle()).isEqualTo(was.getTitle());
        assertThat(now.getDescription()).isEqualTo(was.getDescription());
        assertThat(now.getFolderGrants()).containsExactly(folder.getId() + ":WRITE");
        assertThat(now.getExpiresAt()).isEqualTo(lastDay);
        assertThat(now.getKind()).isEqualTo(ApiKeyKind.V1);
        assertThat(apiKeyRepository.findById(old.id()).orElseThrow().getRevokedAt()).isNotNull();

        assertThat(listed(old.id()).getReplacedById()).isEqualTo(replacement.id());
        assertThat(listed(replacement.id()).getReplacesId()).isEqualTo(old.id());
        assertThat(history(old.id())).anyMatch(entry -> entry.startsWith("REISSUE API_KEY"));
    }

    @Test
    @DisplayName("a replacement of a key whose date has passed has no date; an S3 key's replacement is an S3 key with its capabilities")
    void replacingCarriesTheKindAndDropsAPastDate() {
        ApiKeyDTO s3Request = request("erp s3", LocalDate.now(clock).plusDays(2), folder.getId() + ":READ");
        s3Request.setKind(ApiKeyKind.S3);
        s3Request.setMayCreateFolders(true);
        s3Request.setMayDeleteFolders(true);
        ApiKeyCreatedDTO old = underTest.create(s3Request, principalId);
        expire(old.id());
        underTest.revoke(old.id(), principalId);

        ApiKeyCreatedDTO replacement = underTest.reissue(old.id(), principalId);
        entityManager.flush();
        entityManager.clear();

        ApiKey stored = apiKeyRepository.findById(replacement.id()).orElseThrow();
        assertThat(stored.getKind()).isEqualTo(ApiKeyKind.S3);
        assertThat(stored.isMayCreateFolders()).isTrue();
        assertThat(stored.isMayDeleteFiles()).isFalse();
        assertThat(stored.isMayDeleteFolders()).isTrue();
        assertThat(stored.getExpiresAt()).as("the old date had passed").isNull();
        assertThat(replacement.keyId()).startsWith("FM");
        assertThat(underTest.s3Credential(replacement.keyId())).hasValueSatisfying(credential ->
                assertThat(credential.secret()).isEqualTo(replacement.credential()));
        assertThat(underTest.s3Credential(old.keyId())).isEmpty();
    }

    @Test
    @DisplayName("only a revoked key is replaced, and only once - so two keys never carry one key's place")
    void replacingRefusals() {
        ApiKeyCreatedDTO live = underTest.create(request("live", null), principalId);
        assertThatThrownBy(() -> underTest.reissue(live.id(), principalId))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).hasValue("apiKey.reissue.notRevoked"));

        underTest.revoke(live.id(), principalId);
        underTest.reissue(live.id(), principalId);
        entityManager.flush();
        assertThatThrownBy(() -> underTest.reissue(live.id(), principalId))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).hasValue("apiKey.reissue.already"));
        assertThat(apiKeyRepository.findAll().stream().filter(k -> k.getTitle().equals(
                apiKeyRepository.findById(live.id()).orElseThrow().getTitle()))).as("the key and one replacement").hasSize(2);
    }

    @Test
    @DisplayName("the schema refuses a replacement on a key that is not revoked")
    void theSchemaHoldsTheRule() {
        ApiKeyCreatedDTO live = underTest.create(request("live", null), principalId);
        ApiKeyCreatedDTO other = underTest.create(request("other", null), principalId);
        entityManager.flush();
        assertThatThrownBy(() -> jdbc.update("UPDATE api_key SET replaced_by_id = ? WHERE id = ?", other.id(), live.id()))
                .hasMessageContaining("ck_api_key_replaced_only_when_revoked");
    }

    // ---------------------------------------------------------------- helpers

    /** Puts the key's end in the past, as time would. */
    private void expire(int id) {
        entityManager.flush();
        jdbc.update("UPDATE api_key SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now(clock).minus(Duration.ofHours(1))), id);
        entityManager.clear();
    }

    private ApiKeyDTO listed(int id) {
        entityManager.flush();
        entityManager.clear();
        return underTest.getAll().stream().filter(k -> k.getId() == id).findFirst().orElseThrow();
    }

    private List<String> history(int apiKeyId) {
        entityManager.flush();
        return jdbc.queryForList("SELECT action_description FROM action_history WHERE entity_id = ? AND entity_name = 'ApiKey'",
                String.class, apiKeyId);
    }

    private ApiKeyDTO request(String title, LocalDate expiresAt, String... grants) {
        ApiKeyDTO dto = new ApiKeyDTO();
        dto.setTitle(title + TestData.nextSequence());
        dto.setDescription("used by " + title);
        dto.setExpiresAt(expiresAt);
        dto.setFolderGrants(List.of(grants));
        return dto;
    }
}
