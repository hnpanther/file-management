package com.hnp.filemanagement.service;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.UploadRefusedException;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UploadPolicyRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.validation.ContentTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upload policy: the system-wide defaults, a role's own policy replacing them, the union
 * across a person's roles, and the two refusals.
 */
@ServiceIntegrationTest
class UploadPolicyServiceTest extends MySqlSupport {

    private static final long MB = 1024L * 1024L;

    @Autowired
    private UploadPolicyService underTest;
    @Autowired
    private UploadPolicyRepository uploadPolicyRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ContentKindService contentKindService;

    private int adminId;

    @BeforeEach
    void setUp() {
        adminId = userRepository.save(TestData.user()).getId();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- the system-wide policy

    @Test
    @DisplayName("the migration seeds the system-wide policy: the nine default kinds, each at the server cap")
    void theSeededGlobalPolicyIsTheOldBehaviour() {
        Map<String, Long> limits = underTest.globalLimits();

        assertThat(limits.keySet()).containsExactlyInAnyOrderElementsOf(ContentTypes.defaultExtensions());
        assertThat(limits.values()).containsOnly(underTest.serverCapMb() * MB);
        assertThat(uploadPolicyRepository.findGlobal()).isPresent();
    }

    @Test
    @DisplayName("a person with no role, and an API key, are governed by the system-wide policy")
    void noRoleMeansGlobal() {
        int nobody = userRepository.save(TestData.user()).getId();
        underTest.saveGlobal(mb("pdf", 5, "zip", 10), adminId);

        assertThat(underTest.effectiveLimitsFor(nobody)).containsExactly(
                Map.entry("pdf", 5 * MB), Map.entry("zip", 10 * MB));

        actingAsApiKey(nobody);
        assertThat(underTest.effectiveLimitsFor(nobody)).containsExactly(
                Map.entry("pdf", 5 * MB), Map.entry("zip", 10 * MB));
    }

    @Test
    @DisplayName("saving the system-wide policy replaces it: kinds left out are gone, kinds kept take the new limit")
    void savingReplaces() {
        underTest.saveGlobal(mb("pdf", 5, "png", 2), adminId);
        underTest.saveGlobal(mb("pdf", 7, "docx", 3), adminId);

        assertThat(underTest.globalLimits()).containsExactly(Map.entry("pdf", 7 * MB), Map.entry("docx", 3 * MB));
    }

    // ---------------------------------------------------------------- a role's own policy

    @Test
    @DisplayName("a role without a policy of its own is governed by the system-wide one; with one, by that alone")
    void aRolePolicyReplacesTheGlobalOne() {
        underTest.saveGlobal(mb("pdf", 20, "png", 20), adminId);
        Role role = roleRepository.save(TestData.role("R" + TestData.nextSequence()));
        int member = userWithRoles(role);

        assertThat(underTest.roleLimits(role.getId())).isEmpty();
        assertThat(underTest.effectiveLimitsFor(member)).containsExactly(Map.entry("pdf", 20 * MB), Map.entry("png", 20 * MB));

        underTest.saveForRole(role.getId(), mb("pdf", 2), adminId);

        assertThat(underTest.roleLimits(role.getId())).contains(Map.of("pdf", 2 * MB));
        assertThat(underTest.effectiveLimitsFor(member)).as("png is gone, pdf is smaller").containsExactly(Map.entry("pdf", 2 * MB));

        underTest.saveForRole(role.getId(), null, adminId);

        assertThat(underTest.roleLimits(role.getId())).as("back to the system-wide policy").isEmpty();
        assertThat(underTest.effectiveLimitsFor(member)).containsExactly(Map.entry("pdf", 20 * MB), Map.entry("png", 20 * MB));
    }

    @Test
    @DisplayName("a role's own policy with nothing in it means the role may upload nothing")
    void anEmptyOwnPolicyAllowsNothing() {
        Role role = roleRepository.save(TestData.role("R" + TestData.nextSequence()));
        int member = userWithRoles(role);
        underTest.saveForRole(role.getId(), Map.of(), adminId);

        assertThat(underTest.effectiveLimitsFor(member)).isEmpty();
        assertThatThrownBy(() -> underTest.requireAllowed(member, pdf(MB)))
                .isInstanceOf(UploadRefusedException.class)
                .satisfies(e -> assertThat(((UploadRefusedException) e).getAllowed()).isEmpty());
    }

    @Test
    @DisplayName("across several roles a person may upload what any role allows, up to the largest limit any gives")
    void severalRolesUnion() {
        underTest.saveGlobal(mb("pdf", 20, "txt", 1), adminId);
        Role restricted = roleRepository.save(TestData.role("R" + TestData.nextSequence()));
        Role media = roleRepository.save(TestData.role("M" + TestData.nextSequence()));
        underTest.saveForRole(restricted.getId(), mb("pdf", 2), adminId);
        underTest.saveForRole(media.getId(), mb("mp4", 15, "pdf", 8), adminId);

        assertThat(underTest.effectiveLimitsFor(userWithRoles(restricted)))
                .containsExactly(Map.entry("pdf", 2 * MB));
        assertThat(underTest.effectiveLimitsFor(userWithRoles(restricted, media)))
                .as("union of the two own policies, pdf at the larger of 2 and 8")
                .containsExactly(Map.entry("pdf", 8 * MB), Map.entry("mp4", 15 * MB));
        Role plain = roleRepository.save(TestData.role("P" + TestData.nextSequence()));
        assertThat(underTest.effectiveLimitsFor(userWithRoles(restricted, plain)))
                .as("a role without its own policy brings the system-wide one into the union")
                .containsExactly(Map.entry("pdf", 20 * MB), Map.entry("txt", 1 * MB));
    }

    // ---------------------------------------------------------------- refusing

    @Test
    @DisplayName("a kind the policy does not list is refused, naming what is allowed")
    void aKindNotListedIsRefused() {
        underTest.saveGlobal(mb("pdf", 5), adminId);

        assertThatThrownBy(() -> underTest.requireAllowed(adminId, new MockMultipartFile("f", "a.png", null, TestData.bytesFor("a.png"))))
                .isInstanceOf(UploadRefusedException.class)
                .hasMessageContaining("not allowed")
                .satisfies(e -> {
                    UploadRefusedException refused = (UploadRefusedException) e;
                    assertThat(refused.getReason()).isEqualTo(UploadRefusedException.Reason.TYPE_NOT_ALLOWED);
                    assertThat(refused.getExtension()).isEqualTo("png");
                    assertThat(refused.getAllowed()).containsExactly("pdf");
                });
        assertThatThrownBy(() -> underTest.requireAllowed(adminId, new MockMultipartFile("f", "noextension", null, new byte[]{1})))
                .isInstanceOf(UploadRefusedException.class);
    }

    @Test
    @DisplayName("a file above the kind's limit is refused, naming the size and the limit; one at the limit passes")
    void aFileAboveTheLimitIsRefused() {
        underTest.saveGlobal(mb("pdf", 1), adminId);

        underTest.requireAllowed(adminId, pdf(MB));
        assertThatThrownBy(() -> underTest.requireAllowed(adminId, pdf(MB + 1)))
                .isInstanceOf(UploadRefusedException.class)
                .satisfies(e -> {
                    UploadRefusedException refused = (UploadRefusedException) e;
                    assertThat(refused.getReason()).isEqualTo(UploadRefusedException.Reason.TOO_LARGE);
                    assertThat(refused.getLimitBytes()).isEqualTo(MB);
                    assertThat(refused.getSizeBytes()).isEqualTo(MB + 1);
                });
    }

    @Test
    @DisplayName("the extension is matched case-insensitively")
    void extensionIsCaseInsensitive() {
        underTest.saveGlobal(mb("pdf", 5), adminId);
        underTest.requireAllowed(adminId, new MockMultipartFile("f", "REPORT.PDF", null, TestData.bytesFor("report.pdf")));
    }

    // ---------------------------------------------------------------- what may be saved

    @Test
    @DisplayName("a limit above the server cap, or below one megabyte, or a kind the application cannot recognise, is refused")
    void savingIsValidated() {
        long cap = underTest.serverCapMb();
        assertThatThrownBy(() -> underTest.saveGlobal(mb("pdf", cap + 1), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("server limit");
        assertThatThrownBy(() -> underTest.saveGlobal(mb("pdf", 0), adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.saveGlobal(mb("exe", 1), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("does not recognise");
        assertThatThrownBy(() -> underTest.saveGlobal(mb("svg", 1), adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThat(underTest.globalLimits().keySet()).as("nothing changed").containsExactlyInAnyOrderElementsOf(ContentTypes.defaultExtensions());
    }

    @Test
    @DisplayName("the rows for a page cover the whole catalogue, ticked where the limits list them")
    void rowsCoverTheCatalogue() {
        underTest.saveGlobal(mb("pdf", 3), adminId);

        var rows = underTest.rowsFor(underTest.globalLimits());

        assertThat(rows).extracting(r -> r.extension()).containsExactlyElementsOf(ContentTypes.knownExtensions());
        assertThat(rows).filteredOn(r -> r.allowed()).singleElement().satisfies(r -> {
            assertThat(r.extension()).isEqualTo("pdf");
            assertThat(r.maxMb()).isEqualTo(3);
            assertThat(r.mediaType()).isEqualTo("application/pdf");
        });
    }

    // ---------------------------------------------------------------- helpers

    // ---------------------------------------------------------------- the administrator (1.9.0)

    @Test
    @DisplayName("a holder of ADMIN may upload every catalogued kind up to the server cap, whatever the system-wide policy says")
    void theAdministratorIsAboveThePolicy() {
        underTest.saveGlobal(mb("pdf", 1), adminId);
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.findByRoleNameIgnoreCase("ADMIN")
                .orElseGet(() -> roleRepository.save(TestData.role("ADMIN"))));
        int administrator = userRepository.save(admin).getId();
        long cap = underTest.serverCapMb() * MB;

        Map<String, Long> limits = underTest.effectiveLimitsFor(administrator);

        assertThat(limits.keySet()).containsExactlyElementsOf(ContentTypes.knownExtensions());
        assertThat(limits.values()).containsOnly(cap);
        assertThat(underTest.administratorLimits()).isEqualTo(limits);
        underTest.requireAllowed(administrator, new MockMultipartFile("f", "movie.mp4", "video/mp4", new byte[10]));
        // Everybody else is still held to the policy.
        assertThatThrownBy(() -> underTest.requireAllowed(adminId, new MockMultipartFile("f", "movie.mp4", "video/mp4", new byte[10])))
                .isInstanceOf(UploadRefusedException.class);
    }

    @Test
    @DisplayName("a custom kind registered later is the administrator's at once, with no policy edited")
    void aNewKindReachesTheAdministrator() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.findByRoleNameIgnoreCase("ADMIN")
                .orElseGet(() -> roleRepository.save(TestData.role("ADMIN"))));
        int administrator = userRepository.save(admin).getId();
        assertThat(underTest.effectiveLimitsFor(administrator)).doesNotContainKey("vsdx");
        try {
            ContentTypes.registerCustom(List.of(new ContentTypes.CustomKind("vsdx", "application/vnd.ms-visio.drawing",
                    new byte[]{'P', 'K', 3, 4}, 0, false)));

            assertThat(underTest.effectiveLimitsFor(administrator)).containsEntry("vsdx", underTest.serverCapMb() * MB);
            assertThat(underTest.globalLimits()).as("nobody else's").doesNotContainKey("vsdx");
        } finally {
            // The registry is static: back to what the table says, for the tests that follow.
            contentKindService.refreshRegistry();
        }
    }

    private static Map<String, Long> mb(Object... pairs) {
        Map<String, Long> limits = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            limits.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return limits;
    }

    private static MockMultipartFile pdf(long size) {
        byte[] bytes = new byte[(int) size];
        System.arraycopy("%PDF-".getBytes(), 0, bytes, 0, 5);
        return new MockMultipartFile("f", "report.pdf", null, bytes);
    }

    private int userWithRoles(Role... roles) {
        User user = TestData.user();
        user.getRoles().addAll(List.of(roles));
        return userRepository.save(user).getId();
    }

    private static void actingAsApiKey(int creatorId) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(creatorId);
        principal.setUsername("key");
        principal.setApiKeyId(42);
        principal.setPermissions(List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
