package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.file.persistence.FileShareLinkRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.file.domain.ShareLinkService.Attempt;
import com.hnp.filemanagement.file.domain.ShareLinkService.Outcome;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Temporary share links (roadmap 10.5), rule by rule: what a link is made of, who may make one,
 * when it stops answering - expiry, revocation, the download cap - and how a password guards it,
 * wrong guesses included. The clock is moved by hand; the cap is ten minutes and the lock two
 * wrong guesses, so that both are reachable.
 */
@ServiceIntegrationTest
@Import(MutableClock.Config.class)
@TestPropertySource(properties = {
        "filemanagement.folder-access.enabled=true",
        "filemanagement.share-links.max-minutes=10",
        "filemanagement.share-links.default-minutes=3",
        "filemanagement.share-links.max-failed-attempts=2",
        "filemanagement.share-links.lock-minutes=5"})
class ShareLinkServiceTest extends DatabaseSupport {

    @Autowired
    private ShareLinkService underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private ActionHistoryService actionHistoryService;
    @Autowired
    private FileShareLinkRepository shareLinkRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MutableClock clock;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private int otherAdminId;
    private int restrictedId;
    private FolderFixture.Chain chain;
    private FileDetailsDTO revision;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        User otherAdmin = TestData.user();
        otherAdmin.getRoles().add(adminRole);
        otherAdminId = userRepository.save(otherAdmin).getId();
        restrictedId = userRepository.save(TestData.user()).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        revision = upload("shared.txt", chain.tagId());
    }

    // ---------------------------------------------------------------- making one

    @Test
    @DisplayName("a link is a random token whose hash alone is stored, to one revision, for the minutes asked - defaulted, and clamped to the cap")
    void makesALink() {
        LocalDateTime now = LocalDateTime.now(clock);

        ShareLinkDTO link = underTest.create(revision.getId(), 5, null, null, adminId);
        assertThat(link.token()).hasSize(43).doesNotContain("=", "+", "/");
        assertThat(link.path()).isEqualTo("/share/" + link.token());
        assertThat(link.expiresAt()).isEqualTo(now.plusMinutes(5));
        assertThat(link.status()).isEqualTo(ShareLinkDTO.Status.ACTIVE);
        assertThat(link.passwordProtected()).isFalse();
        assertThat(link.maxDownloads()).isNull();
        assertThat(link.fileDetailsId()).isEqualTo(revision.getId());
        assertThat(link.fileInfoId()).isEqualTo(revision.getFileInfoId());
        assertThat(link.fileName()).isEqualTo("shared.txt");

        FileShareLink stored = shareLinkRepository.findById(link.id()).orElseThrow();
        assertThat(stored.getTokenHash()).isEqualTo(ShareLinkService.sha256(link.token())).hasSize(64);
        assertThat(stored.getTokenHash()).as("the token itself is nowhere").isNotEqualTo(link.token());
        assertThat(actionHistoryService.getActionHistoriesOfEntity(link.id(), EntityEnum.FileShareLink)).hasSize(1);

        assertThat(underTest.create(revision.getId(), null, null, null, adminId).expiresAt())
                .as("the default").isEqualTo(now.plusMinutes(3));
        assertThat(underTest.create(revision.getId(), 10_000, null, null, adminId).expiresAt())
                .as("clamped, silently").isEqualTo(now.plusMinutes(10));
        assertThat(underTest.create(revision.getId(), 7, "  ", 2, adminId))
                .satisfies(l -> {
                    assertThat(l.passwordProtected()).as("blank is no password").isFalse();
                    assertThat(l.maxDownloads()).isEqualTo(2);
                });
        assertThat(underTest.listMine(adminId)).hasSize(4);
        assertThat(underTest.listMine(otherAdminId)).isEmpty();
        assertThat(underTest.listAll()).hasSize(4);
    }

    @Test
    @DisplayName("refused: minutes or downloads below one, a password too long, a revision that does not exist, and a folder the maker cannot read")
    void refusals() {
        assertThatThrownBy(() -> underTest.create(revision.getId(), 0, null, null, adminId)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.create(revision.getId(), 5, null, 0, adminId)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.create(revision.getId(), 5, "x".repeat(73), null, adminId)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.create(999_999, 5, null, null, adminId)).isInstanceOf(ResourceNotFoundException.class);

        // The link is the access, so making one needs the access: READ on the folder.
        assertThatThrownBy(() -> underTest.create(revision.getId(), 5, null, null, restrictedId))
                .isInstanceOf(AccessDeniedException.class);
        grant(restrictedId, chain.tagId(), FolderPermission.READ);
        assertThat(underTest.create(revision.getId(), 5, null, null, restrictedId).createdBy())
                .isEqualTo(userRepository.findById(restrictedId).orElseThrow().getUsername());
    }

    // ---------------------------------------------------------------- using one

    @Test
    @DisplayName("a link without a password downloads at once and counts; at the cap it stops answering, like any dead link")
    void downloadsUntilTheCap() {
        ShareLinkDTO link = underTest.create(revision.getId(), 5, null, 2, adminId);

        Attempt first = underTest.download(link.token(), null);
        assertThat(first.outcome()).isEqualTo(Outcome.DOWNLOAD);
        assertThat(first.file().getFileName()).isEqualTo("shared.txt");
        assertThat(first.file().getResource().exists()).isTrue();
        assertThat(underTest.download(link.token(), "ignored").outcome()).isEqualTo(Outcome.DOWNLOAD);
        entityManager.flush();
        entityManager.clear();

        assertThat(underTest.usable(link.token())).as("used up").isEmpty();
        assertThatThrownBy(() -> underTest.download(link.token(), null)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(underTest.listMine(adminId).getFirst().status()).isEqualTo(ShareLinkDTO.Status.EXHAUSTED);
        assertThat(underTest.listMine(adminId).getFirst().downloadCount()).isEqualTo(2);
        assertThat(actionHistoryService.getActionHistoriesOfEntity(link.id(), EntityEnum.FileShareLink))
                .as("creation and two downloads, the downloads on the maker").hasSize(3);
    }

    @Test
    @DisplayName("a link expires on the minute, is revoked by its maker or by whoever may revoke any, and an unknown token is nothing - all alike")
    void expiryAndRevocation() {
        ShareLinkDTO expiring = underTest.create(revision.getId(), 5, null, null, adminId);
        ShareLinkDTO revokable = underTest.create(revision.getId(), 5, null, null, adminId);

        clock.advance(Duration.ofMinutes(4));
        assertThat(underTest.usable(expiring.token())).isPresent();
        clock.advance(Duration.ofMinutes(1));
        assertThat(underTest.usable(expiring.token())).as("at the minute, gone").isEmpty();
        assertThat(underTest.listMine(adminId)).extracting(ShareLinkDTO::status)
                .containsExactlyInAnyOrder(ShareLinkDTO.Status.EXPIRED, ShareLinkDTO.Status.EXPIRED);

        ShareLinkDTO fresh = underTest.create(revision.getId(), 5, null, null, adminId);
        assertThatThrownBy(() -> underTest.revoke(fresh.id(), otherAdminId, false))
                .as("someone else's, without the right to revoke any").isInstanceOf(AccessDeniedException.class);
        assertThat(underTest.usable(fresh.token())).isPresent();
        underTest.revoke(fresh.id(), otherAdminId, true);
        assertThat(underTest.usable(fresh.token())).isEmpty();
        assertThat(underTest.listMine(adminId)).filteredOn(l -> l.id() == fresh.id()).singleElement()
                .satisfies(l -> {
                    assertThat(l.status()).isEqualTo(ShareLinkDTO.Status.REVOKED);
                    assertThat(l.revokedAt()).isEqualTo(LocalDateTime.now(clock));
                });
        underTest.revoke(revokable.id(), adminId, false);
        assertThat(underTest.usable(revokable.token())).isEmpty();
        assertThatThrownBy(() -> underTest.revoke(999_999, adminId, true)).isInstanceOf(ResourceNotFoundException.class);

        assertThat(underTest.usable("no-such-token")).isEmpty();
        assertThat(underTest.usable("")).isEmpty();
        assertThat(underTest.usable(null)).isEmpty();
        assertThat(underTest.usable("x".repeat(200))).isEmpty();
    }

    @Test
    @DisplayName("a password stands before the download: asked for, checked, and wrong guesses lock the link for a while")
    void aPasswordGuardsTheLink() {
        ShareLinkDTO link = underTest.create(revision.getId(), 8, "s3cret", null, adminId);
        assertThat(link.passwordProtected()).isTrue();
        assertThat(passwordEncoder.matches("s3cret", shareLinkRepository.findById(link.id()).orElseThrow().getPasswordHash())).isTrue();

        assertThat(underTest.download(link.token(), null).outcome()).isEqualTo(Outcome.PASSWORD_REQUIRED);
        assertThat(underTest.download(link.token(), "wrong").outcome()).isEqualTo(Outcome.WRONG_PASSWORD);
        Attempt locked = underTest.download(link.token(), "wrong again");
        assertThat(locked.outcome()).as("the second wrong guess locks").isEqualTo(Outcome.LOCKED);
        assertThat(locked.lockedUntil()).isEqualTo(LocalDateTime.now(clock).plusMinutes(5));
        assertThat(underTest.download(link.token(), "s3cret").outcome()).as("even the right password, while locked").isEqualTo(Outcome.LOCKED);
        assertThat(underTest.usable(link.token())).as("locked is not dead: the page still shows it").isPresent();

        clock.advance(Duration.ofMinutes(5));
        assertThat(underTest.download(link.token(), "wrong").outcome()).as("the lock is over, the count started again").isEqualTo(Outcome.WRONG_PASSWORD);
        Attempt ok = underTest.download(link.token(), "s3cret");
        assertThat(ok.outcome()).isEqualTo(Outcome.DOWNLOAD);
        assertThat(ok.file().getFileName()).isEqualTo("shared.txt");
        assertThat(shareLinkRepository.findById(link.id()).orElseThrow().getFailedAttempts()).as("reset by a right guess").isZero();
        assertThat(shareLinkRepository.findById(link.id()).orElseThrow().getDownloadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting the revision takes its links with it, and so does deleting the whole file")
    void theRevisionTakesTheLink() {
        FileDetailsDTO second = upload("second.txt", chain.tagId());
        ShareLinkDTO link = underTest.create(revision.getId(), 5, null, null, adminId);
        ShareLinkDTO toSecond = underTest.create(second.getId(), 5, null, null, adminId);
        entityManager.flush();

        fileService.deleteFileDetails(revision.getFileInfoId(), revision.getId(), adminId);
        entityManager.flush();
        entityManager.clear();
        assertThat(shareLinkRepository.findById(link.id())).isEmpty();
        assertThat(underTest.usable(link.token())).isEmpty();
        assertThat(underTest.usable(toSecond.token())).as("another file's link is untouched").isPresent();

        fileService.deleteCompleteFileById(second.getFileInfoId(), adminId);
        entityManager.flush();
        entityManager.clear();
        assertThat(shareLinkRepository.findById(toSecond.id())).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private FileDetailsDTO upload(String fileName, int folderId) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile(fileName, fileName, "text/plain", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, 0);
    }

    private void grant(int userId, int folderId, FolderPermission permission) {
        User user = userRepository.findById(userId).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), permission));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
        entityManager.flush();
        entityManager.clear();
    }
}
