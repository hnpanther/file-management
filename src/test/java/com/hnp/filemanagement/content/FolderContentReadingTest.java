package com.hnp.filemanagement.content;

import com.hnp.filemanagement.content.FolderReadingService.Choice;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderContentService;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether the files of a folder, and of the folders below it, are read for the search in contents
 * (2.16.0): every folder reads until switched off; switched off, nothing read changes - its text stays and
 * is found - while what is uploaded, and what was waiting, is held back; switched on again, what was held
 * back is queued; the nearest setting above decides, so a folder below one switched off may read; a file or
 * a folder moved takes the setting of where it arrives; the backfill and the worker ask the same; and only
 * whoever may write in the folder changes it.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class FolderContentReadingTest extends DatabaseSupport {

    @Autowired
    private FolderReadingService underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private ContentSearchService contentSearchService;
    @Autowired
    private FileContentRepository repository;
    @Autowired
    private ContentWorker worker;
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
    private Folder top;
    private Folder archive;
    private Folder inside;
    private Folder beside;
    private String run;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        top = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        archive = FolderFixture.tag(folderRepository, top, admin, "archive" + TestData.nextSequence());
        inside = FolderFixture.tag(folderRepository, archive, admin, "inside" + TestData.nextSequence());
        beside = FolderFixture.tag(folderRepository, top, admin, "beside" + TestData.nextSequence());
        run = "رمز" + Long.toString(TestData.nextSequence() + 1000, 36).replaceAll("[0-9]", "");
    }

    @Test
    @DisplayName("by default every folder reads: an upload waits to be read, and the page says it is the default")
    void readByDefault() {
        FileDetailsDTO file = upload("note.txt", inside);

        assertThat(stateOf(file.getId())).isEqualTo("PENDING");
        FolderReadingService.View view = underTest.view(inside.getId(), adminId, true);
        assertThat(view.reads()).isTrue();
        assertThat(view.byDefault()).isTrue();
        assertThat(view.choice()).isEqualTo(Choice.INHERIT);
        assertThat(view.mayChange()).isTrue();
        assertThat(folderContentService.detailsOf(inside.getId(), adminId).contentRead()).isTrue();
    }

    @Test
    @DisplayName("switched off: what was read stays read and found; what waited, and every upload after, is held back - below it too, not beside it")
    void switchedOff() {
        FileDetailsDTO read = upload("read.txt", archive);
        read(read.getId(), run + " قرارداد خوانده شده");
        FileDetailsDTO waiting = upload("waiting.txt", inside);
        long pagesBefore = pagesOf(read.getId());

        FolderReadingService.Changed changed = underTest.set(archive.getId(), Choice.OFF, adminId);
        flush();

        assertThat(changed.readBefore()).isTrue();
        assertThat(changed.readsNow()).isFalse();
        assertThat(changed.heldBack()).isEqualTo(1);
        assertThat(stateOf(read.getId())).as("read before: untouched").isEqualTo("DONE");
        assertThat(pagesOf(read.getId())).isEqualTo(pagesBefore);
        assertThat(contentSearchService.search(run + " قرارداد", false, 0, 10, adminId).items())
                .as("and still found").extracting(result -> result.hit().fileDetailsId()).containsExactly(read.getId());
        assertThat(stateOf(waiting.getId())).as("waiting below: held back").isEqualTo("EXCLUDED");

        FileDetailsDTO after = upload("after.txt", archive);
        FileDetailsDTO deeper = upload("deeper.txt", inside);
        FileDetailsDTO elsewhere = upload("elsewhere.txt", beside);
        assertThat(stateOf(after.getId())).isEqualTo("EXCLUDED");
        assertThat(stateOf(deeper.getId())).isEqualTo("EXCLUDED");
        assertThat(stateOf(elsewhere.getId())).as("a folder beside it reads").isEqualTo("PENDING");
        int version = nextVersion(read);
        assertThat(stateOf(version)).as("a new version of a file read before").isEqualTo("EXCLUDED");
        assertThat(stateOf(read.getId())).isEqualTo("DONE");

        FolderReadingService.View view = underTest.view(inside.getId(), adminId, true);
        assertThat(view.reads()).isFalse();
        assertThat(view.decidedById()).as("decided by the folder above").isEqualTo(archive.getId());
        assertThat(underTest.view(archive.getId(), adminId, true).heldBack()).isEqualTo(4);
        assertThat(folderContentService.detailsOf(inside.getId(), adminId).contentRead()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM action_history WHERE entity_name = 'Folder' AND entity_id = ? AND user_id = ?""",
                Integer.class, archive.getId(), adminId)).isEqualTo(1);
    }

    @Test
    @DisplayName("switched on again: everything held back below it is queued behind new uploads; nothing read is read again")
    void switchedOnAgain() {
        FileDetailsDTO read = upload("read.txt", archive);
        read(read.getId(), run + " متن");
        Instant readAt = readAtOf(read.getId());
        underTest.set(archive.getId(), Choice.OFF, adminId);
        FileDetailsDTO heldHere = upload("held.txt", archive);
        FileDetailsDTO heldBelow = upload("below.txt", inside);
        flush();

        FolderReadingService.Changed changed = underTest.set(archive.getId(), Choice.INHERIT, adminId);
        flush();

        assertThat(changed.readsNow()).isTrue();
        assertThat(changed.queued()).isEqualTo(2);
        for (int id : List.of(heldHere.getId(), heldBelow.getId())) {
            assertThat(stateOf(id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT priority FROM file_content WHERE file_details_id = ?", Integer.class, id))
                    .as("behind every new upload").isEqualTo(FileContentRepository.PRIORITY_BACKFILL);
        }
        assertThat(stateOf(read.getId())).isEqualTo("DONE");
        assertThat(readAtOf(read.getId())).isEqualTo(readAt);
        assertThat(underTest.view(archive.getId(), adminId, true).byDefault()).isTrue();
        assertThat(underTest.view(archive.getId(), adminId, true).heldBack()).isZero();
    }

    @Test
    @DisplayName("the nearest setting decides: a folder switched on below one switched off reads, and its held-back revisions are queued")
    void theNearestDecides() {
        underTest.set(archive.getId(), Choice.OFF, adminId);
        FileDetailsDTO heldBelow = upload("below.txt", inside);
        FileDetailsDTO heldHere = upload("here.txt", archive);
        assertThat(stateOf(heldBelow.getId())).isEqualTo("EXCLUDED");

        underTest.set(inside.getId(), Choice.ON, adminId);
        flush();
        assertThat(stateOf(heldBelow.getId())).isEqualTo("PENDING");
        assertThat(stateOf(heldHere.getId())).as("the folder above is still off").isEqualTo("EXCLUDED");
        assertThat(stateOf(upload("new.txt", inside).getId())).isEqualTo("PENDING");

        FolderReadingService.View archiveView = underTest.view(archive.getId(), adminId, true);
        assertThat(archiveView.exceptions()).singleElement().satisfies(own -> {
            assertThat(own.folderId()).isEqualTo(inside.getId());
            assertThat(own.reads()).isTrue();
            assertThat(own.setBy()).isNotBlank();
        });
        assertThat(underTest.view(inside.getId(), adminId, true).decidedHere()).isTrue();
        assertThat(underTest.everywhere(adminId)).extracting(FolderReadingService.OwnChoice::folderId)
                .contains(archive.getId(), inside.getId());

        // Switched on at the top, the folder switched on below keeps its own setting; switched off at the
        // top again, it still reads.
        underTest.set(archive.getId(), Choice.ON, adminId);
        flush();
        assertThat(stateOf(heldHere.getId())).isEqualTo("PENDING");
        underTest.set(archive.getId(), Choice.OFF, adminId);
        flush();
        assertThat(stateOf(heldBelow.getId())).as("its own setting").isEqualTo("PENDING");
        assertThat(stateOf(heldHere.getId())).isEqualTo("EXCLUDED");
    }

    @Test
    @DisplayName("a file moved takes the setting of where it arrives; what it had read stays read")
    void aFileMoved() {
        underTest.set(archive.getId(), Choice.OFF, adminId);
        FileDetailsDTO held = upload("held.txt", archive);
        FileDetailsDTO read = upload("read.txt", beside);
        read(read.getId(), run + " متن");
        FileDetailsDTO waiting = upload("waiting.txt", beside);

        fileService.moveFile(held.getFileInfoId(), beside.getId(), adminId);
        flush();
        assertThat(stateOf(held.getId())).as("into a folder that reads").isEqualTo("PENDING");

        fileService.moveFile(waiting.getFileInfoId(), inside.getId(), adminId);
        flush();
        assertThat(stateOf(waiting.getId())).as("into one that does not").isEqualTo("EXCLUDED");
        fileService.moveFile(read.getFileInfoId(), inside.getId(), adminId);
        flush();
        assertThat(stateOf(read.getId())).isEqualTo("DONE");
    }

    @Test
    @DisplayName("a folder moved takes the setting of where it arrives, with everything below it - and of its own where it has one")
    void aFolderMoved() {
        Folder wanderer = FolderFixture.tag(folderRepository, beside, userRepository.getReferenceById(adminId), "w" + TestData.nextSequence());
        Folder own = FolderFixture.tag(folderRepository, wanderer, userRepository.getReferenceById(adminId), "o" + TestData.nextSequence());
        FileDetailsDTO file = upload("file.txt", wanderer);
        underTest.set(own.getId(), Choice.ON, adminId);
        underTest.set(archive.getId(), Choice.OFF, adminId);
        FileDetailsDTO ownFile = upload("own.txt", own);
        flush();

        folderService.move(wanderer.getId(), archive.getId(), adminId);
        flush();
        assertThat(stateOf(file.getId())).as("under a folder switched off").isEqualTo("EXCLUDED");
        assertThat(stateOf(ownFile.getId())).as("its own setting still decides").isEqualTo("PENDING");

        folderService.move(wanderer.getId(), beside.getId(), adminId);
        flush();
        assertThat(stateOf(file.getId())).as("back where it reads").isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the backfill queues an old revision held back in a folder switched off, and waiting elsewhere")
    void theBackfill() {
        FileDetailsDTO old = upload("old.txt", archive);
        FileDetailsDTO elsewhere = upload("elsewhere.txt", beside);
        jdbc.update("DELETE FROM file_content WHERE file_details_id IN (?, ?)", old.getId(), elsewhere.getId());
        underTest.set(archive.getId(), Choice.OFF, adminId);
        flush();

        int unread = jdbc.queryForObject("SELECT count(*) FROM file_details d WHERE NOT EXISTS "
                + "(SELECT 1 FROM file_content c WHERE c.file_details_id = d.id)", Integer.class);
        repository.enqueueUnread(unread + 10, Instant.now());

        assertThat(stateOf(old.getId())).isEqualTo("EXCLUDED");
        assertThat(stateOf(elsewhere.getId())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the worker reads nothing in a folder switched off since it was queued - and reads in one that reads")
    void theWorkerAsksAgain() throws Exception {
        FileDetailsDTO queued = upload("queued.txt", archive);
        FileDetailsDTO reads = upload("reads.txt", beside);
        // Switched off behind the queue's back, as by a change that has not settled this revision.
        jdbc.update("UPDATE folder SET content_reading = FALSE WHERE id = ?", archive.getId());
        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = now() + interval '1 hour' WHERE file_details_id IN (?, ?)",
                queued.getId(), reads.getId());

        worker.readOne(queued.getId());
        assertThat(stateOf(queued.getId())).isEqualTo("EXCLUDED");
        assertThat(jdbc.queryForObject("SELECT lease_until FROM file_content WHERE file_details_id = ?", Object.class, queued.getId()))
                .isNull();
        assertThat(repository.excludeIfNotRead(reads.getId())).as("its folder reads").isFalse();
        assertThat(stateOf(reads.getId())).isEqualTo("READING");
    }

    @Test
    @DisplayName("seen by whoever may read the folder, changed only by whoever may write in it; a choice is required")
    void whoMay() {
        User reader = userRepository.save(TestData.user());
        User writer = userRepository.save(TestData.user());
        grant(reader, archive, FolderPermission.READ);
        grant(writer, archive, FolderPermission.WRITE);

        assertThat(underTest.view(inside.getId(), reader.getId(), true).mayChange()).as("read only").isFalse();
        assertThatThrownBy(() -> underTest.set(archive.getId(), Choice.OFF, reader.getId())).isInstanceOf(AccessDeniedException.class);
        assertThat(underTest.view(inside.getId(), writer.getId(), true).mayChange()).isTrue();
        assertThat(underTest.view(inside.getId(), writer.getId(), false).mayChange()).as("without the permission").isFalse();
        assertThatThrownBy(() -> underTest.view(beside.getId(), writer.getId(), true)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.set(beside.getId(), Choice.OFF, writer.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.set(archive.getId(), null, adminId)).isInstanceOf(InvalidDataException.class);

        underTest.set(inside.getId(), Choice.OFF, writer.getId());
        flush();
        assertThat(underTest.view(inside.getId(), adminId, true).reads()).isFalse();
        assertThat(underTest.everywhere(reader.getId())).extracting(FolderReadingService.OwnChoice::folderId)
                .containsExactly(inside.getId());
        User stranger = userRepository.save(TestData.user());
        flush();
        assertThat(underTest.everywhere(stranger.getId())).as("no folder names a stranger cannot see").isEmpty();
    }

    // ================================================================ helpers

    private String stateOf(int fileDetailsId) {
        return jdbc.queryForObject("SELECT state FROM file_content WHERE file_details_id = ?", String.class, fileDetailsId);
    }

    private Instant readAtOf(int fileDetailsId) {
        return jdbc.queryForObject("SELECT read_at FROM file_content WHERE file_details_id = ?", java.sql.Timestamp.class,
                fileDetailsId).toInstant();
    }

    private long pagesOf(int fileDetailsId) {
        return jdbc.queryForObject("SELECT count(*) FROM file_content_page WHERE file_details_id = ?", Long.class, fileDetailsId);
    }

    /** A reading written as the worker writes one: claimed, finished with its page. */
    private void read(int fileDetailsId, String text) {
        flush();
        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = now() + interval '1 hour' WHERE file_details_id = ?",
                fileDetailsId);
        assertThat(repository.finish(fileDetailsId, new FileContentRepository.Outcome("DONE", "TEXT", "text/plain", null,
                false, null, null, text.length()), List.of(new FileContentRepository.PageRow(0, 0, "WHOLE", null, "TEXT", null,
                text, ContentFolding.fold(text))), Instant.now())).isTrue();
    }

    private FileDetailsDTO upload(String fileName, Folder folder) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folder.getId());
        String unique = fileName.replace(".txt", "-" + TestData.nextSequence() + ".txt");
        request.setMultipartFile(new MockMultipartFile("file", unique, "text/plain", ("content of " + unique).getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PRIVATE);
        flush();
        return uploaded;
    }

    /** A new version of the file, queued as every upload is; its revision id. */
    private int nextVersion(FileDetailsDTO of) {
        String title = of.getFileName().substring(0, of.getFileName().lastIndexOf('.'));
        FileUploadDTO next = new FileUploadDTO();
        next.setFileId(of.getFileInfoId());
        next.setFileName(title);
        next.setFileNameWithoutExtension(title);
        next.setVersion(2);
        next.setType("version");
        next.setFileDetailsDescription("v2");
        next.setMultipartFile(new MockMultipartFile("file", of.getFileName(), "text/plain", "v2".getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFileDetails(next, adminId);
        flush();
        return jdbc.queryForObject("SELECT id FROM file_details WHERE file_info_id = ? AND version = 2", Integer.class,
                of.getFileInfoId());
    }

    private void grant(User user, Folder folder, FolderPermission permission) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        loaded.replaceFolderGrants(List.of(new UserFolderGrant(loaded, folderRepository.findById(folder.getId()).orElseThrow(),
                permission)));
        userRepository.save(loaded);
        flush();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
