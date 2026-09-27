package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.file.persistence.FileHistoryQuery;
import com.hnp.filemanagement.file.persistence.FileHistoryRepository;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderTreeDeleteService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The file history (2.5.0): every change to a file leaves one event, in its transaction, with
 * what a person needs to read it later copied in - and the events outlive the file.
 */
@ServiceIntegrationTest
class FileHistoryServiceTest extends DatabaseSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private FileHistoryService underTest;
    @Autowired
    private FileHistoryRepository fileHistoryRepository;
    @Autowired
    private FolderTreeDeleteService folderTreeDeleteService;
    @Autowired
    private ShareLinkService shareLinkService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private String adminName;
    private FolderFixture.Chain chain;
    private Folder other;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        adminName = admin.getUsername();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        other = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Other" + TestData.nextSequence());
    }

    @AfterEach
    void clearTheKey() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- one event per change

    @Test
    @DisplayName("an upload, a new version and a new format are three events, each with its revision, where, and by whom")
    void uploadsAndRevisions() {
        FileDetailsDTO v1 = upload("contract.pdf");
        fileService.createNewFileDetails(revisionRequest(v1.getFileInfoId(), "contract.pdf", 2, "version", null), adminId);
        fileService.createNewFileDetails(revisionRequest(v1.getFileInfoId(), "contract.docx", 2, "format", v2Of(v1.getFileInfoId())), adminId);
        entityManager.flush();

        List<FileHistoryEntry> history = underTest.ofFile(v1.getFileInfoExternalId());

        assertThat(history).extracting(FileHistoryEntry::event)
                .containsExactly(FileEvent.FORMAT_ADDED, FileEvent.VERSION_ADDED, FileEvent.FILE_UPLOADED);
        FileHistoryEntry uploaded = history.get(2);
        assertThat(uploaded.fileName()).isEqualTo("contract");
        assertThat(uploaded.version()).isEqualTo(1);
        assertThat(uploaded.fileExtension()).isEqualTo("pdf");
        assertThat(uploaded.fileSize()).isEqualTo(TestData.bytesFor("contract.pdf").length);
        assertThat(uploaded.folderTitle()).endsWith(chain.tag().getDisplayName());
        assertThat(uploaded.username()).isEqualTo(adminName);
        assertThat(uploaded.apiKeyTitle()).isNull();
        assertThat(uploaded.liveFileId()).isEqualTo(v1.getFileInfoId());
        assertThat(history.get(0).fileExtension()).isEqualTo("docx");
        assertThat(history.get(0).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("a description, a move and a change of visibility are recorded - the move with where it went and where it left")
    void changes() {
        FileDetailsDTO stored = upload("policy.pdf");
        int fileId = stored.getFileInfoId();
        String sourceTitle = underTest.titleOf(chain.tag());

        fileService.updateFileInfoDescription(fileId, "the new description", adminId);
        fileService.moveFile(fileId, other.getId(), adminId);
        fileService.changeFileInfoState(fileId, -1, adminId);
        fileService.changeFileInfoState(fileId, -1, adminId);   // no change: no event
        fileService.changeFileDetailsState(stored.getId(), -1, adminId);
        entityManager.flush();

        List<FileHistoryEntry> history = underTest.ofFile(stored.getFileInfoExternalId());
        assertThat(history).extracting(FileHistoryEntry::event).containsExactly(
                FileEvent.REVISION_UNPUBLISHED, FileEvent.FILE_UNPUBLISHED, FileEvent.FILE_MOVED,
                FileEvent.DESCRIPTION_CHANGED, FileEvent.FILE_UPLOADED);
        FileHistoryEntry moved = history.get(2);
        assertThat(moved.folderTitle()).endsWith(other.getDisplayName());
        assertThat(moved.detail()).isEqualTo(sourceTitle);
        assertThat(history.get(3).detail()).isEqualTo("the new description");
    }

    @Test
    @DisplayName("a deleted file keeps its whole history - who uploaded it, who deleted which version and then the file - and is no longer a link")
    void theHistoryOutlivesTheFile() {
        FileDetailsDTO v1 = upload("minutes.txt");
        int fileId = v1.getFileInfoId();
        fileService.createNewFileDetails(revisionRequest(fileId, "minutes.txt", 2, "version", null), adminId);
        entityManager.flush();
        int v2 = v2Of(fileId);

        User deleter = userRepository.save(TestData.user());
        fileService.deleteFileDetails(v2, adminId);
        fileService.deleteCompleteFileById(fileId, deleter.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM file_info WHERE id = ?", Integer.class, fileId)).isZero();
        List<FileHistoryEntry> history = underTest.ofFile(v1.getFileInfoExternalId());
        assertThat(history).extracting(FileHistoryEntry::event).containsExactly(
                FileEvent.FILE_DELETED, FileEvent.REVISION_DELETED, FileEvent.VERSION_ADDED, FileEvent.FILE_UPLOADED);
        assertThat(history).allSatisfy(e -> {
            assertThat(e.fileName()).isEqualTo("minutes");
            assertThat(e.liveFileId()).as("the file is gone: nothing to link to").isNull();
        });
        assertThat(history.get(0).username()).isEqualTo(deleter.getUsername());
        assertThat(history.get(0).version()).as("the version it had when it went").isEqualTo(1);
        assertThat(history.get(1).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("a folder deleted with everything in it leaves each file's deletion, saying which folder took it")
    void aTreeDeleteRecordsEveryFile() {
        Folder doomed = FolderFixture.tag(folderRepository, chain.subCategory(), userRepository.getReferenceById(adminId),
                "Doomed" + TestData.nextSequence());
        String doomedTitle = underTest.titleOf(doomed);
        FileDetailsDTO first = upload(doomed.getId(), "one.txt");
        FileDetailsDTO second = upload(doomed.getId(), "two.txt");
        entityManager.flush();

        folderTreeDeleteService.deleteTree(doomed.getId(), adminId);
        entityManager.flush();

        for (FileDetailsDTO file : List.of(first, second)) {
            FileHistoryEntry deleted = underTest.ofFile(file.getFileInfoExternalId()).getFirst();
            assertThat(deleted.event()).isEqualTo(FileEvent.FILE_DELETED);
            assertThat(deleted.detail()).isEqualTo(doomedTitle);
            assertThat(deleted.folderTitle()).isEqualTo(doomedTitle);
        }
    }

    @Test
    @DisplayName("a refused change records nothing: the event is in the transaction of the change")
    void aRefusalRecordsNothing() {
        upload("dup.txt");
        entityManager.flush();
        long before = fileHistoryRepository.count();

        assertThatThrownBy(() -> upload("dup.txt")).isInstanceOf(DuplicateResourceException.class);

        assertThat(fileHistoryRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("what an API key does is recorded as the key's; a share link made and revoked are two events")
    void theKeyAndTheShareLinks() {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("اپکس " + TestData.nextSequence());
        request.setFolderGrants(List.of(chain.tagId() + ":WRITE"));
        int keyId = apiKeyService.create(request, adminId).id();
        actAsKey(keyId);
        FileDetailsDTO byKey = upload("bykey.txt");
        SecurityContextHolder.clearContext();

        ShareLinkDTO link = shareLinkService.create(byKey.getId(), 10, null, null, adminId);
        shareLinkService.revoke(link.id(), adminId, true);
        entityManager.flush();

        List<FileHistoryEntry> history = underTest.ofFile(byKey.getFileInfoExternalId());
        assertThat(history).extracting(FileHistoryEntry::event).containsExactly(
                FileEvent.SHARE_LINK_REVOKED, FileEvent.SHARE_LINK_CREATED, FileEvent.FILE_UPLOADED);
        assertThat(history.get(2).apiKeyTitle()).isEqualTo(request.getTitle());
        assertThat(history.get(1).apiKeyTitle()).as("the link was made by a person").isNull();
        assertThat(Instant.parse(history.get(1).detail())).as("the link's expiry").isAfter(Instant.now().minusSeconds(60));

        assertThat(underTest.search(FileHistoryQuery.everything().byApiKey(keyId), 0, 50, adminId).entries())
                .extracting(FileHistoryEntry::event).containsExactly(FileEvent.FILE_UPLOADED);
    }

    @Test
    @DisplayName("the username is the one at the time, not the one the account has now")
    void theUsernameThen() {
        User person = userRepository.save(TestData.user());
        String then = person.getUsername();
        FileDetailsDTO stored = upload(chain.tagId(), "renamed.txt", person.getId());
        jdbcTemplate.update("UPDATE app_user SET username = ? WHERE id = ?", then + "-renamed", person.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(underTest.ofFile(stored.getFileInfoExternalId()).getFirst().username()).isEqualTo(then);
    }

    @Test
    @DisplayName("a file's history is followed by its external id: an older file that had its number is not in it")
    void followedByTheExternalIdNotTheNumber() {
        FileDetailsDTO stored = upload("numbered.txt");
        jdbcTemplate.update("""
                INSERT INTO file_history (occurred_at, event, file_info_id, file_external_id, file_name, user_id, username)
                VALUES (now() - interval '1 day', 'FILE_DELETED', ?, '00000000-0000-4000-8000-000000000000', 'older', ?, 'x')
                """, stored.getFileInfoId(), adminId);

        assertThat(underTest.ofFile(stored.getFileInfoExternalId())).extracting(FileHistoryEntry::fileName).containsOnly("numbered");
    }

    // ---------------------------------------------------------------- the history page's search

    @Test
    @DisplayName("the search filters by name (a % in it standing for itself), by event and by time, newest first")
    void theSearch() {
        String token = "hist" + TestData.nextSequence();
        FileDetailsDTO a = upload(token + "_a.txt");
        FileDetailsDTO b = upload(token + "Xa.txt");
        fileService.updateFileInfoDescription(b.getFileInfoId(), "changed", adminId);
        entityManager.flush();

        assertThat(search(token + "_a", null)).extracting(FileHistoryEntry::fileName).containsExactly(token + "_a");
        assertThat(search(token, null)).extracting(FileHistoryEntry::event)
                .containsExactly(FileEvent.DESCRIPTION_CHANGED, FileEvent.FILE_UPLOADED, FileEvent.FILE_UPLOADED);
        assertThat(search(token, FileEvent.DESCRIPTION_CHANGED)).extracting(FileHistoryEntry::fileName).containsExactly(token + "Xa");
        assertThat(search("%", null)).isEmpty();

        Instant later = Instant.now().plusSeconds(3600);
        assertThat(underTest.search(new FileHistoryQuery(key(token), null, later, null, null, null, null), 0, 50, adminId).entries())
                .as("nothing from the future").isEmpty();
        assertThat(underTest.search(new FileHistoryQuery(key(token), null, null, later, null, null, null), 0, 50, adminId).entries())
                .hasSize(3);
        assertThat(a.getFileInfoId()).isNotNull();
    }

    @Test
    @DisplayName("a page says whether there is another, without counting the history")
    void paging() {
        String token = "page" + TestData.nextSequence();
        for (int i = 0; i < 5; i++) {
            upload(token + i + ".txt");
        }
        entityManager.flush();
        FileHistoryQuery query = new FileHistoryQuery(key(token), null, null, null, null, null, null);

        FileHistoryService.HistoryPage first = underTest.search(query, 0, 2, adminId);
        FileHistoryService.HistoryPage last = underTest.search(query, 2, 2, adminId);

        assertThat(first.entries()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.hasPrevious()).isFalse();
        assertThat(last.entries()).hasSize(1);
        assertThat(last.hasNext()).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private List<FileHistoryEntry> search(String name, FileEvent event) {
        return underTest.search(new FileHistoryQuery(key(name), event, null, null, null, null, null), 0, 50, adminId).entries();
    }

    private static String key(String name) {
        return SearchTerms.escapeLike(SearchKey.forSearch(name));
    }

    private void actAsKey(int keyId) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(adminId);
        principal.setUsername("key");
        principal.setPassword("");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setApiKeyId(keyId);
        principal.setPermissions(List.of(PermissionEnum.API_KEY));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private int v2Of(int fileId) {
        return jdbcTemplate.queryForObject(
                "SELECT min(id) FROM file_details WHERE file_info_id = ? AND version = 2", Integer.class, fileId);
    }

    private FileDetailsDTO upload(String fileName) {
        return upload(chain.tagId(), fileName, adminId);
    }

    private FileDetailsDTO upload(int folderId, String fileName) {
        return upload(folderId, fileName, adminId);
    }

    private FileDetailsDTO upload(int folderId, String fileName, int userId) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("history of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, userId, FileService.PUBLIC);
    }

    private FileUploadDTO revisionRequest(int fileId, String fileName, int version, String type, Integer sampleId) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileId);
        request.setFileName(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setFileNameWithoutExtension(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setVersion(version);
        request.setType(type);
        request.setFileDetailsId(sampleId);
        request.setFileDetailsDescription(type + " " + version);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", TestData.bytesFor(fileName)));
        return request;
    }
}
