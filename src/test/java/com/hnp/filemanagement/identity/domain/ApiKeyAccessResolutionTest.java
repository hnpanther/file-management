package com.hnp.filemanagement.identity.domain;

import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.folder.domain.FolderAccess;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose folders a request reaches when an API key is the one asking (roadmap 9.2).
 *
 * <p><b>This is the seam the whole design rests on, and it is the one that is invisible at the call
 * site.</b> Every service in this application asks {@code accessFor(principalId)}, and a key's
 * principal carries the id of the person who created it — so that {@code action_history} and every
 * existing signature keep working. What must <em>not</em> happen is that the key then reaches that
 * person's folders. The switch lives inside {@code accessFor}, which reads the current
 * authentication, precisely so that no call site can forget it; a test that only ever called
 * {@code accessForApiKey} directly would prove nothing about the path production takes.
 */
@ServiceIntegrationTest
@org.springframework.test.context.TestPropertySource(
        properties = "filemanagement.folder-access.enabled=true")
class ApiKeyAccessResolutionTest extends DatabaseSupport {

    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private com.hnp.filemanagement.identity.persistence.RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;

    private int userId;
    private int rootFolderId;
    private String rootPath;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(TestData.user()).getId();
        var root = folderRepository.findRoots().getFirst();
        rootFolderId = root.getId();
        rootPath = root.getPath();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("with no key acting, a person reaches their own folders")
    void aPersonReachesTheirOwnGrants() {
        grantToUser(FolderPermission.WRITE);

        FolderAccess access = folderAccessService.accessFor(userId);

        assertThat(access.canRead(rootPath)).isTrue();
        assertThat(access.canWrite(rootPath)).isTrue();
    }

    /**
     * The case this test exists for. The principal's id is the creator's, so a resolver that used
     * the id alone would hand the key everything its creator has — here, write access to the whole
     * tree — instead of the nothing it was granted.
     */
    @Test
    @DisplayName("a key does not inherit the folders of the person who created it")
    void aKeyDoesNotInheritItsCreatorsGrants() {
        grantToUser(FolderPermission.WRITE);
        ApiKeyCreatedDTO key = createKey();

        actAs(key.id());

        FolderAccess access = folderAccessService.accessFor(userId);
        assertThat(access.isEmpty()).as("the key was granted nothing").isTrue();
        assertThat(access.canRead(rootPath)).isFalse();
        assertThat(access.canWrite(rootPath)).isFalse();
    }

    @Test
    @DisplayName("a key reaches its own folders even when its creator has none")
    void aKeyReachesWhatItWasGranted() {
        ApiKeyCreatedDTO key = createKey(rootFolderId + ":READ");

        actAs(key.id());

        FolderAccess access = folderAccessService.accessFor(userId);
        assertThat(access.canRead(rootPath)).isTrue();
        assertThat(access.canWrite(rootPath)).as("granted READ, not WRITE").isFalse();
    }

    /**
     * An administrator is unrestricted; a key created by one is not. Without this, the safest thing
     * an administrator could do — issue a narrow key instead of sharing their own credentials —
     * would be the most dangerous.
     */
    @Test
    @DisplayName("a key created by an administrator is still only what it was granted")
    void anAdministratorsKeyIsNotAnAdministrator() {
        var adminRole = roleRepository.save(TestData.role("ADMIN"));
        var admin = TestData.user();
        admin.getRoles().add(adminRole);
        int adminId = userRepository.save(admin).getId();

        assertThat(folderAccessService.accessFor(adminId).unrestricted())
                .as("the person is unrestricted")
                .isTrue();

        ApiKeyCreatedDTO key = createKey();
        actAs(key.id());

        assertThat(folderAccessService.accessFor(adminId).unrestricted())
                .as("the key is not")
                .isFalse();
    }

    @Test
    @DisplayName("clearing the authentication puts the person back")
    void theSwitchIsPerRequestAndNotSticky() {
        grantToUser(FolderPermission.READ);
        ApiKeyCreatedDTO key = createKey();

        actAs(key.id());
        assertThat(folderAccessService.accessFor(userId).isEmpty()).isTrue();

        SecurityContextHolder.clearContext();
        assertThat(folderAccessService.accessFor(userId).canRead(rootPath))
                .as("the next request is a person's again")
                .isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private void grantToUser(FolderPermission permission) {
        User user = userRepository.findById(userId).orElseThrow();
        user.replaceFolderGrants(List.of(new UserFolderGrant(user,
                folderRepository.findById(rootFolderId).orElseThrow(), permission)));
        userRepository.save(user);
    }

    private ApiKeyCreatedDTO createKey(String... grants) {
        ApiKeyDTO dto = new ApiKeyDTO();
        dto.setTitle("key" + TestData.nextSequence());
        dto.setFolderGrants(List.of(grants));
        return apiKeyService.create(dto, userId);
    }

    /** Puts a key's principal in the context, exactly as {@code ApiKeyAuthenticationFilter} does. */
    private void actAs(int apiKeyId) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(userId);
        principal.setUsername("a-key");
        principal.setPassword("");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setApiKeyId(apiKeyId);
        principal.setPermissions(List.of());

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }
}
