package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.file.persistence.UploadPolicyRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyCreatedDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An API key's own upload policy (roadmap 12.7), at the service: what a key may upload is its own
 * policy when it has one - whoever made it, ADMIN or not - and the system-wide one otherwise, exactly as
 * before; the system-wide policy is still found as the one row of neither a role nor a key; the policy
 * is set, kept, given back, copied to a replacing key, recorded, and held by the database to one owner.
 */
@ServiceIntegrationTest
class ApiKeyUploadPolicyServiceTest extends DatabaseSupport {

    private static final long MB = 1024L * 1024L;

    @Autowired
    private UploadPolicyService underTest;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private UploadPolicyRepository uploadPolicyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    private int adminId;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        underTest.saveGlobal(mb("pdf", 5, "png", 3, "txt", 1), adminId);
    }

    @AfterEach
    void clearTheKey() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a key of its own policy may upload that alone - though its maker is an administrator; a key without one, the system-wide policy")
    void aKeysOwnPolicyAlone() {
        ApiKeyCreatedDTO restricted = key("OWN", mb("txt", 2));
        ApiKeyCreatedDTO plain = key(null, null);

        actingAs(restricted.id());
        assertThat(underTest.effectiveLimitsFor(adminId)).containsExactly(Map.entry("txt", 2 * MB));
        assertThatThrownBy(() -> underTest.requireAllowed(adminId, file("photo.png", 10)))
                .isInstanceOf(UploadRefusedException.class).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> underTest.requireAllowed(adminId, file("long.txt", 2 * MB + 1)))
                .isInstanceOf(UploadRefusedException.class);
        underTest.requireAllowed(adminId, file("short.txt", 2 * MB));

        actingAs(plain.id());
        assertThat(underTest.effectiveLimitsFor(adminId)).containsExactly(Map.entry("pdf", 5 * MB), Map.entry("png", 3 * MB),
                Map.entry("txt", 1 * MB));
        SecurityContextHolder.clearContext();
        assertThat(underTest.effectiveLimitsFor(adminId)).as("the maker, signed in: an administrator, as before")
                .isEqualTo(underTest.administratorLimits());
    }

    @Test
    @DisplayName("the system-wide policy is still the one row of neither a role nor a key - read and saved as before")
    void theSystemWidePolicyIsStillOne() {
        key("OWN", mb("txt", 2));
        key("OWN", mb("pdf", 1));
        flush();
        assertThat(underTest.globalLimits()).containsExactly(Map.entry("pdf", 5 * MB), Map.entry("png", 3 * MB), Map.entry("txt", 1 * MB));
        underTest.saveGlobal(mb("pdf", 7), adminId);
        flush();
        assertThat(underTest.globalLimits()).containsExactly(Map.entry("pdf", 7 * MB));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_policy WHERE role_id IS NULL AND api_key_id IS NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("set on the key's page: OWN keeps the ticked kinds, GLOBAL gives it back, a form without the section leaves it be")
    void setKeptAndGivenBack() {
        ApiKeyCreatedDTO created = key(null, null);
        assertThat(underTest.apiKeyLimits(created.id())).isEmpty();

        update(created.id(), "OWN", mb("pdf", 2, "txt", 1));
        assertThat(underTest.apiKeyLimits(created.id())).hasValue(Map.of("pdf", 2 * MB, "txt", MB));
        assertThat(apiKeyService.getByIdWithGrants(created.id()).isUploadPolicyOwn()).isTrue();
        assertThat(apiKeyService.getAll()).filteredOn(k -> k.getId() == created.id()).singleElement()
                .satisfies(k -> assertThat(k.isUploadPolicyOwn()).isTrue());

        update(created.id(), null, null);
        assertThat(underTest.apiKeyLimits(created.id())).as("not carried: untouched").hasValue(Map.of("pdf", 2 * MB, "txt", MB));

        update(created.id(), "OWN", mb("txt", 4));
        assertThat(underTest.apiKeyLimits(created.id())).hasValue(Map.of("txt", 4 * MB));

        update(created.id(), "GLOBAL", null);
        assertThat(underTest.apiKeyLimits(created.id())).isEmpty();
        assertThat(jdbc.queryForList("SELECT action FROM action_history WHERE entity_name = 'UploadPolicy' AND description LIKE ?"
                + " ORDER BY id", String.class, "%apiKey=" + created.keyId() + "%")).as("the form without the section recorded nothing")
                .containsExactly("CREATE", "UPDATE_VALUES", "DELETE");
    }

    @Test
    @DisplayName("an own policy with nothing ticked: the key may upload nothing")
    void nothingTicked() {
        ApiKeyCreatedDTO created = key("OWN", new LinkedHashMap<>());
        assertThat(underTest.apiKeyLimits(created.id())).hasValue(Map.of());
        actingAs(created.id());
        assertThatThrownBy(() -> underTest.requireAllowed(adminId, file("note.txt", 10))).isInstanceOf(UploadRefusedException.class);
    }

    @Test
    @DisplayName("a policy that could not be kept - above the server's cap, a kind not catalogued, nothing - refused, and no key made with it")
    void refused() {
        long keysBefore = keys();
        assertThatThrownBy(() -> key("OWN", mb("pdf", underTest.serverCapMb() + 1))).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> key("OWN", mb("exe", 1))).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> key("OWN", mb("pdf", 0))).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> key("SOMETHING", mb("pdf", 1))).isInstanceOf(InvalidDataException.class);
        flush();
        assertThat(keys()).isEqualTo(keysBefore);
    }

    @Test
    @DisplayName("a revoked key replaced: the new one may upload what the old one might")
    void aReplacementKeepsIt() {
        ApiKeyCreatedDTO old = key("OWN", mb("txt", 2));
        ApiKeyCreatedDTO plainOld = key(null, null);
        apiKeyService.revoke(old.id(), adminId);
        apiKeyService.revoke(plainOld.id(), adminId);
        ApiKeyCreatedDTO replacement = apiKeyService.reissue(old.id(), adminId);
        ApiKeyCreatedDTO plainReplacement = apiKeyService.reissue(plainOld.id(), adminId);
        flush();
        assertThat(underTest.apiKeyLimits(replacement.id())).hasValue(Map.of("txt", 2 * MB));
        assertThat(underTest.apiKeyLimits(old.id())).as("the old one's stays its own").hasValue(Map.of("txt", 2 * MB));
        assertThat(underTest.apiKeyLimits(plainReplacement.id())).isEmpty();
    }

    @Test
    @DisplayName("the database holds a policy to one owner, and a key's goes with the key")
    void heldByTheDatabase() {
        ApiKeyCreatedDTO created = key("OWN", mb("txt", 2));
        Role role = roleRepository.save(TestData.role("R" + TestData.nextSequence()));
        flush();
        assertThatThrownBy(() -> jdbc.update("UPDATE upload_policy SET role_id = ? WHERE api_key_id = ?", role.getId(), created.id()))
                .as("one owner").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a key deleted takes its policy and rules with it")
    void goesWithTheKey() {
        ApiKeyCreatedDTO created = key("OWN", mb("txt", 2, "pdf", 1));
        flush();
        Integer policyId = jdbc.queryForObject("SELECT id FROM upload_policy WHERE api_key_id = ?", Integer.class, created.id());
        jdbc.update("DELETE FROM api_key WHERE id = ?", created.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_policy WHERE id = ?", Integer.class, policyId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_policy_rule WHERE policy_id = ?", Integer.class, policyId)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private ApiKeyCreatedDTO key(String mode, Map<String, Long> ownMb) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("policy " + TestData.nextSequence());
        request.setUploadPolicyMode(mode);
        if (ownMb != null) {
            request.setUploadAllowed(List.copyOf(ownMb.keySet()));
            request.setUploadMax(ownMb);
        }
        return apiKeyService.create(request, adminId);
    }

    private void update(int id, String mode, Map<String, Long> ownMb) {
        ApiKeyDTO request = apiKeyService.getByIdWithGrants(id);
        request.setUploadPolicyMode(mode);
        request.setUploadAllowed(ownMb == null ? null : List.copyOf(ownMb.keySet()));
        request.setUploadMax(ownMb);
        apiKeyService.update(id, request, adminId);
        flush();
    }

    private long keys() {
        return jdbc.queryForObject("SELECT count(*) FROM api_key", Long.class);
    }

    private static Map<String, Long> mb(Object... kindsAndMb) {
        Map<String, Long> limits = new LinkedHashMap<>();
        for (int i = 0; i < kindsAndMb.length; i += 2) {
            limits.put((String) kindsAndMb[i], ((Number) kindsAndMb[i + 1]).longValue());
        }
        return limits;
    }

    private static MockMultipartFile file(String name, long size) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[(int) size]);
    }

    private void actingAs(int apiKeyId) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(adminId);
        principal.setUsername("key");
        principal.setApiKeyId(apiKeyId);
        principal.setPermissions(List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
