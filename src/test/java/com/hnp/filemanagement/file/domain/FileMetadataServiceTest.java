package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.file.persistence.FileHistoryRepository;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.PreconditionFailedException;
import com.hnp.filemanagement.shared.metadata.MetadataPrecondition;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A file's metadata (roadmap 12.2): with every upload route, inherited or not by the next version,
 * and set afterwards - where there is none, in place of what was read, cleared - each change an event
 * with both documents; refused for a bad document, a failed condition, or a reader without access,
 * and nothing changed then.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileMetadataServiceTest extends DatabaseSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private FileMetadataService underTest;
    @Autowired
    private FileHistoryRepository fileHistoryRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    private int adminId;
    private int folderId;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tagId();
    }

    // ================================================================ uploads

    @Nested
    @DisplayName("an upload")
    class Uploads {

        @Test
        @DisplayName("stores what it is sent, as jsonb stores it, on the event too; none is null")
        void storesIt() {
            FileDetailsDTO with = upload("deal.txt", "{\"contractNo\":\"C-5678\",\"party\":{\"name\":\"علی\"}}");
            assertThat(stored(with.getId())).isEqualTo("{\"party\": {\"name\": \"علی\"}, \"contractNo\": \"C-5678\"}");
            assertThat(with.getMetadata().get("contractNo").stringValue()).isEqualTo("C-5678");
            assertThat(jdbc.queryForObject("SELECT metadata_after::text FROM file_history WHERE file_details_id = ? AND event = 'FILE_UPLOADED'",
                    String.class, with.getId())).isEqualTo(stored(with.getId()));

            FileDetailsDTO without = upload("plain.txt", null);
            assertThat(stored(without.getId())).isNull();
            assertThat(upload("empty.txt", "{}").getMetadata()).as("{} is none").isNull();
        }

        @Test
        @DisplayName("is refused whole for a document the rules refuse - no file, no revision, no event")
        void refusedWhole() {
            int files = count("file_info");
            int revisions = count("file_details");
            int events = count("file_history");
            for (String bad : List.of("[1]", "{\"a\":1,\"a\":2}", "not json", "{\"" + "k".repeat(101) + "\":1}")) {
                assertThatThrownBy(() -> upload("refused" + TestData.nextSequence() + ".txt", bad))
                        .isInstanceOf(InvalidDataException.class);
            }
            assertThat(count("file_info")).isEqualTo(files);
            assertThat(count("file_details")).isEqualTo(revisions);
            assertThat(count("file_history")).isEqualTo(events);
        }

        @Test
        @DisplayName("a new version or format without one takes the file's current; with one, its own; from S3, none")
        void nextRevisions() {
            FileDetailsDTO first = upload("report.txt", "{\"stage\":\"draft\"}");
            fileService.createNewFileDetails(version(first, 2, null, true), adminId);
            FileDetails v2 = newest(first.getFileInfoId());
            assertThat(v2.getMetadata()).as("inherited").isEqualTo("{\"stage\": \"draft\"}");

            fileService.createNewFileDetails(version(first, 3, "{\"stage\":\"signed\"}", true), adminId);
            assertThat(newest(first.getFileInfoId()).getMetadata()).isEqualTo("{\"stage\": \"signed\"}");

            fileService.createNewFileDetails(format(newest(first.getFileInfoId()), null), adminId);
            FileDetails pdf = revisions(first.getFileInfoId()).stream().filter(r -> r.getFileExtension().equals("pdf")).findFirst().orElseThrow();
            assertThat(pdf.getVersion()).isEqualTo(3);
            assertThat(pdf.getMetadata()).as("a format inherits too").isEqualTo("{\"stage\": \"signed\"}");

            fileService.createNewFileDetails(version(first, 4, null, false), adminId);
            assertThat(newest(first.getFileInfoId()).getMetadata()).as("S3: a revision carries its own whole").isNull();
            assertThat(revisions(first.getFileInfoId())).extracting(FileDetails::getMetadata)
                    .containsExactly("{\"stage\": \"draft\"}", "{\"stage\": \"draft\"}", "{\"stage\": \"signed\"}",
                            "{\"stage\": \"signed\"}", null);
        }
    }

    // ================================================================ setting it afterwards

    @Nested
    @DisplayName("setting it afterwards")
    class Setting {

        @Test
        @DisplayName("on the file: every format of its newest version, older versions untouched, one event each with both documents")
        void onTheFile() {
            FileDetailsDTO first = upload("plan.txt", "{\"v\":1}");
            fileService.createNewFileDetails(version(first, 2, null, true), adminId);
            fileService.createNewFileDetails(format(newest(first.getFileInfoId()), null), adminId);
            flushAndClear();

            FileMetadataService.Written written = underTest.replaceOnFile(first.getFileInfoId(),
                    "{\"v\":2,\"note\":\"تأیید شد\"}", MetadataPrecondition.NONE, adminId);
            assertThat(written.changed()).isTrue();
            assertThat(written.revisions()).hasSize(2).allSatisfy(r -> assertThat(r.version()).isEqualTo(2));
            flushAndClear();
            assertThat(revisions(first.getFileInfoId())).extracting(FileDetails::getMetadata)
                    .containsExactly("{\"v\": 1}", "{\"v\": 2, \"note\": \"تأیید شد\"}", "{\"v\": 2, \"note\": \"تأیید شد\"}");
            List<Map<String, Object>> events = jdbc.queryForList("""
                    SELECT metadata_before::text AS b, metadata_after::text AS a, detail FROM file_history
                    WHERE file_info_id = ? AND event = 'METADATA_CHANGED' ORDER BY id""", first.getFileInfoId());
            assertThat(events).hasSize(2).allSatisfy(e -> {
                assertThat(e.get("b")).isEqualTo("{\"v\": 1}");
                assertThat(e.get("a")).isEqualTo("{\"v\": 2, \"note\": \"تأیید شد\"}");
                assertThat((String) e.get("detail")).isEqualTo("1 -> 2 keys").doesNotContain("تأیید");
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM action_history WHERE action_description = 'CHANGE METADATA'"
                    + " AND entity_id IN (SELECT id FROM file_details WHERE file_info_id = ?)", Integer.class, first.getFileInfoId()))
                    .isEqualTo(2);
            assertThat(underTest.ofFile(first.getFileInfoId(), adminId).etag()).isEqualTo(written.current().etag());
        }

        @Test
        @DisplayName("on one revision: that one alone")
        void onARevision() {
            FileDetailsDTO first = upload("one.txt", null);
            fileService.createNewFileDetails(version(first, 2, null, true), adminId);
            flushAndClear();
            underTest.replaceOnRevision(first.getId(), "{\"old\":true}", MetadataPrecondition.NONE, adminId);
            flushAndClear();
            assertThat(revisions(first.getFileInfoId())).extracting(FileDetails::getMetadata).containsExactly("{\"old\": true}", null);
            assertThat(underTest.ofFile(first.getFileInfoId(), adminId).document()).as("the file's is its newest revision's").isEmpty();
        }

        @Test
        @DisplayName("If-None-Match: * sets it where there is none, and where there is one changes nothing: 412")
        void onlyWhereNone() {
            FileDetailsDTO bare = upload("bare.txt", null);
            MetadataPrecondition onlyIfNone = new MetadataPrecondition(null, "*");
            underTest.replaceOnFile(bare.getFileInfoId(), "{\"by\":\"integration\"}", onlyIfNone, adminId);
            flushAndClear();
            underTest.replaceOnFile(bare.getFileInfoId(), "{\"by\":\"a person\"}", MetadataPrecondition.NONE, adminId);
            flushAndClear();
            int events = count("file_history");
            assertThatThrownBy(() -> underTest.replaceOnFile(bare.getFileInfoId(), "{\"by\":\"integration\"}", onlyIfNone, adminId))
                    .isInstanceOf(PreconditionFailedException.class);
            flushAndClear();
            assertThat(stored(bare.getId())).isEqualTo("{\"by\": \"a person\"}");
            assertThat(count("file_history")).isEqualTo(events);
        }

        @Test
        @DisplayName("If-Match replaces only what was read - a stale tag, or * where there is none, is a 412")
        void onlyWhatWasRead() {
            FileDetailsDTO file = upload("match.txt", "{\"n\":1}");
            flushAndClear();
            String read = underTest.ofFile(file.getFileInfoId(), adminId).etag();
            underTest.replaceOnFile(file.getFileInfoId(), "{\"n\":2}", new MetadataPrecondition(read, null), adminId);
            flushAndClear();
            assertThatThrownBy(() -> underTest.replaceOnFile(file.getFileInfoId(), "{\"n\":3}", new MetadataPrecondition(read, null), adminId))
                    .as("the tag read before the change").isInstanceOf(PreconditionFailedException.class);
            String now = underTest.ofFile(file.getFileInfoId(), adminId).etag();
            underTest.replaceOnFile(file.getFileInfoId(), "{\"n\":3}", new MetadataPrecondition("\"x\", W/" + now, null), adminId);
            flushAndClear();
            assertThat(stored(file.getId())).isEqualTo("{\"n\": 3}");

            FileDetailsDTO bare = upload("bare2.txt", null);
            assertThatThrownBy(() -> underTest.replaceOnFile(bare.getFileInfoId(), "{\"n\":1}", new MetadataPrecondition("*", null), adminId))
                    .isInstanceOf(PreconditionFailedException.class);
        }

        @Test
        @DisplayName("{} clears it, recorded; the same document again records nothing; a bad one changes nothing")
        void clearingAndNothing() {
            FileDetailsDTO file = upload("clear.txt", "{\"a\":1}");
            flushAndClear();
            int events = count("file_history");
            assertThat(underTest.replaceOnFile(file.getFileInfoId(), "{ \"a\" : 1 }", MetadataPrecondition.NONE, adminId).changed())
                    .as("the same, spaced otherwise").isFalse();
            assertThat(count("file_history")).isEqualTo(events);

            assertThatThrownBy(() -> underTest.replaceOnFile(file.getFileInfoId(), "[1]", MetadataPrecondition.NONE, adminId))
                    .isInstanceOf(InvalidDataException.class);
            assertThat(stored(file.getId())).isEqualTo("{\"a\": 1}");

            underTest.replaceOnFile(file.getFileInfoId(), "{}", MetadataPrecondition.NONE, adminId);
            flushAndClear();
            assertThat(stored(file.getId())).isNull();
            assertThat(jdbc.queryForObject("SELECT metadata_after IS NULL AND metadata_before::text = '{\"a\": 1}' FROM file_history"
                    + " WHERE file_details_id = ? AND event = 'METADATA_CHANGED'", Boolean.class, file.getId())).isTrue();
        }
    }

    // ================================================================ access

    @Test
    @DisplayName("reading asks READ on the file's folder, writing WRITE - a reader with READ only sets nothing")
    void access() {
        FileDetailsDTO file = upload("guarded.txt", "{\"a\":1}");
        int readerId = userRepository.save(TestData.user()).getId();
        flushAndClear();
        assertThatThrownBy(() -> underTest.ofFile(file.getFileInfoId(), readerId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.ofRevision(file.getId(), readerId)).isInstanceOf(AccessDeniedException.class);

        grant(readerId, folderId, FolderPermission.READ);
        assertThat(underTest.ofFile(file.getFileInfoId(), readerId).document()).isPresent();
        assertThatThrownBy(() -> underTest.replaceOnFile(file.getFileInfoId(), "{\"a\":2}", MetadataPrecondition.NONE, readerId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.replaceOnRevision(file.getId(), "{\"a\":2}", MetadataPrecondition.NONE, readerId))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(stored(file.getId())).isEqualTo("{\"a\": 1}");

        grant(readerId, folderId, FolderPermission.WRITE);
        underTest.replaceOnFile(file.getFileInfoId(), "{\"a\":2}", MetadataPrecondition.NONE, readerId);
        flushAndClear();
        assertThat(stored(file.getId())).isEqualTo("{\"a\": 2}");
    }

    // ================================================================ helpers

    private FileDetailsDTO upload(String fileName, String metadata) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMetadata(metadata);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        return fileService.createNewFile(request, adminId, FileService.PRIVATE);
    }

    private FileUploadDTO version(FileDetailsDTO file, int version, String metadata, boolean inherit) {
        String name = file.getFileName().substring(0, file.getFileName().lastIndexOf('.'));
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(file.getFileInfoId());
        request.setFileName(name);
        request.setFileNameWithoutExtension(name);
        request.setVersion(version);
        request.setType("version");
        request.setFileDetailsDescription("version " + version);
        request.setMetadata(metadata);
        request.setInheritMetadata(inherit);
        request.setMultipartFile(new MockMultipartFile("file", file.getFileName(), "text/plain",
                ("v" + version).getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private FileUploadDTO format(FileDetails sample, String metadata) {
        String name = sample.getFileInfo().getFileName();
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(sample.getFileInfo().getId());
        request.setFileDetailsId(sample.getId());
        request.setFileName(name);
        request.setFileNameWithoutExtension(name);
        request.setVersion(sample.getVersion());
        request.setType("format");
        request.setFileDetailsDescription("as pdf");
        request.setMetadata(metadata);
        request.setMultipartFile(new MockMultipartFile("file", name + ".pdf", "application/pdf",
                "%PDF-1.4\nthe same, as pdf".getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private List<FileDetails> revisions(int fileInfoId) {
        flushAndClear();
        return entityManager.createQuery("SELECT d FROM FileDetails d WHERE d.fileInfo.id = :id ORDER BY d.version, d.id",
                FileDetails.class).setParameter("id", fileInfoId).getResultList();
    }

    private FileDetails newest(int fileInfoId) {
        return revisions(fileInfoId).getLast();
    }

    private String stored(int fileDetailsId) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT metadata::text FROM file_details WHERE id = ?", String.class, fileDetailsId);
    }

    private int count(String table) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void grant(int userId, int folder, FolderPermission permission) {
        User user = userRepository.findById(userId).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.removeIf(g -> g.getFolder().getId() == folder);
        grants.add(new UserFolderGrant(user, folderRepository.findById(folder).orElseThrow(), permission));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
        flushAndClear();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
