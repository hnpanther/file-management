package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileMetadataService;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.folder.domain.FolderClassAssignment;
import com.hnp.filemanagement.folder.domain.FolderClassService;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.domain.FolderTreeDeleteService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.metadata.MetadataPrecondition;
import com.hnp.filemanagement.support.ClassFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.trash.TrashEntryRepository;
import com.hnp.filemanagement.trash.TrashPurge;
import com.hnp.filemanagement.trash.TrashRestore;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a change to a file queues for the webhooks (roadmap 13.18), every way of changing it: an upload, a version, a
 * format, metadata, a move (to a subscription on either side), a version or a file deleted - into the bin - restored
 * and purged, a folder deleted with what is in it and restored. Each delivery's body is S3's shape - the bucket, the
 * key as it is now, the revision as its version id - and says nothing of a metadata document. The scopes on the real
 * tree, a folder renamed and moved, the class rules, the filters, the loop guard, a paused subscription, one deleted
 * behind the cache's back. Each test rolls back; what only a commit shows is {@code WebhookCommitTest}'s.
 */
@ServiceIntegrationTest
@Import(MutableClock.Config.class)
@TestPropertySource(properties = {"filemanagement.webhooks.enabled=true", "filemanagement.webhooks.threads=0",
        "filemanagement.webhooks.allowed-hosts=127.0.0.1,localhost", "filemanagement.webhooks.timeout-seconds=2"})
class WebhookOutboxTest extends DatabaseSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebhookService webhookService;
    @Autowired
    private WebhookRuleCache ruleCache;
    @Autowired
    private FileService fileService;
    @Autowired
    private FileMetadataService fileMetadataService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderTreeDeleteService folderTreeDeleteService;
    @Autowired
    private FolderClassService folderClassService;
    @Autowired
    private FolderClassAssignment folderClassAssignment;
    @Autowired
    private TrashRestore trashRestore;
    @Autowired
    private TrashPurge trashPurge;
    @Autowired
    private TrashEntryRepository trashEntries;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private String adminName;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        adminName = admin.getUsername();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        flushAndClear();
        // What a rolled-back test left in memory is gone with it.
        ruleCache.refresh();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an upload: one delivery per matching subscription, its body S3's - bucket, key, size, version id - and ours")
    void anUpload() throws Exception {
        int below = subscribe("below", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED));
        int own = subscribe("own", WebhookScope.FOLDER, chain.tagId(), null, EnumSet.allOf(WebhookEvent.class));
        int notHere = subscribe("not-here", WebhookScope.FOLDER, chain.categoryId(), null, EnumSet.allOf(WebhookEvent.class));
        int otherEvent = subscribe("other-event", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.MOVED));

        FileDetailsDTO uploaded = upload(chain.tagId(), "Report 1.txt", "{\"salary\":\"value-not-to-leak\"}");

        assertThat(deliveriesOf(below)).hasSize(1);
        assertThat(deliveriesOf(own)).hasSize(1);
        assertThat(deliveriesOf(notHere)).as("FOLDER is that folder alone").isEmpty();
        assertThat(deliveriesOf(otherEvent)).isEmpty();

        Map<String, Object> row = deliveriesOf(below).getFirst();
        assertThat(row.get("event")).isEqualTo("FILE_CREATED");
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat(row.get("file_name")).isEqualTo("Report 1");
        String body = (String) row.get("payload");
        assertThat(body).doesNotContain("value-not-to-leak").doesNotContain("salary");

        JsonNode record = JSON.readTree(body).get("Records").get(0);
        String bucket = chain.category().getName().toLowerCase(Locale.ROOT);
        String key = chain.subCategory().getName() + "/" + chain.tag().getName() + "/Report 1.txt";
        assertThat(record.get("eventName").asString()).isEqualTo("s3:ObjectCreated:Put");
        assertThat(record.get("eventSource").asString()).isEqualTo("fm:file-management");
        assertThat(record.get("userIdentity").get("principalId").asString()).isEqualTo(adminName);
        assertThat(record.get("s3").get("configurationId").asString()).isEqualTo("below");
        assertThat(record.get("s3").get("bucket").get("name").asString()).isEqualTo(bucket);
        assertThat(record.get("s3").get("object").get("key").asString()).isEqualTo(key.replace(" ", "+"));
        assertThat(record.get("s3").get("object").get("size").asLong()).isPositive();
        String revisionId = jdbc.queryForObject("SELECT external_id FROM file_details WHERE id = ?", String.class, uploaded.getId());
        assertThat(record.get("s3").get("object").get("versionId").asString()).isEqualTo(revisionId);

        JsonNode fm = JSON.readTree(body).get("fm");
        assertThat(fm.get("event").asString()).isEqualTo("FILE_CREATED");
        assertThat(fm.get("fileEvent").asString()).isEqualTo("FILE_UPLOADED");
        assertThat(fm.get("file").get("name").asString()).isEqualTo("Report 1");
        assertThat(fm.get("file").get("objects").get(0).get("key").asString()).isEqualTo(key);
        assertThat(fm.get("folder").get("id").asInt()).isEqualTo(chain.tagId());
        assertThat(fm.get("folder").get("bucket").asString()).isEqualTo(bucket);
        assertThat(fm.get("actor").get("apiKeyId").isNull()).isTrue();
        assertThat(fm.get("webhook").get("id").asInt()).isEqualTo(below);
        // One occurrence, one event id, whichever subscription it was sent to.
        assertThat(deliveriesOf(own).getFirst().get("event_id")).isEqualTo(row.get("event_id"));
    }

    @Test
    @DisplayName("every event of a file's life, each by its way - the bin's included - and nothing the history keeps for itself")
    void everyEvent() throws Exception {
        int all = subscribe("all", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.allOf(WebhookEvent.class));
        FileDetailsDTO first = upload(chain.tagId(), "life.txt", null);
        int fileId = first.getFileInfoId();
        int version2 = addRevision(fileId, "life.txt", 2, "version", null);
        int pdf = addRevision(fileId, "life.pdf", 2, "format", version2);
        fileMetadataService.replaceOnRevision(pdf, "{\"a\":1}", MetadataPrecondition.NONE, adminId);
        flushAndClear();
        fileService.updateFileInfoDescription(fileId, "a description the history keeps", adminId);
        flushAndClear();
        fileService.deleteFileDetails(fileId, version2, adminId);
        flushAndClear();
        int revisionEntry = trashEntries.findAll().stream().filter(e -> version2 == orZero(e.getFileDetailsId()))
                .findFirst().orElseThrow().getId();
        trashRestore.restore(revisionEntry, null, null, adminId);
        flushAndClear();
        fileService.deleteCompleteFileById(fileId, adminId);
        flushAndClear();
        int fileEntry = trashEntries.findByFileInfoId(fileId).orElseThrow().getId();
        trashRestore.restore(fileEntry, null, null, adminId);
        flushAndClear();
        fileService.deleteCompleteFileById(fileId, adminId);
        flushAndClear();
        trashPurge.purge(trashEntries.findByFileInfoId(fileId).orElseThrow().getId(), adminId);
        flushAndClear();

        assertThat(deliveriesOf(all)).extracting(row -> row.get("event")).containsExactly(
                "FILE_CREATED", "REVISION_CREATED", "REVISION_CREATED", "METADATA_CHANGED", "REVISION_DELETED",
                "RESTORED", "FILE_DELETED", "RESTORED", "FILE_DELETED", "PURGED");

        // An event about the whole file names every format of it, each at its latest version.
        JsonNode deleted = JSON.readTree((String) deliveriesOf(all).get(6).get("payload"));
        assertThat(deleted.get("Records")).hasSize(2);
        assertThat(deleted.get("Records").get(0).get("eventName").asString()).isEqualTo("s3:ObjectRemoved:Delete");
        assertThat(keysOf(deleted)).containsExactlyInAnyOrder(
                chain.subCategory().getName() + "/" + chain.tag().getName() + "/life.pdf",
                chain.subCategory().getName() + "/" + chain.tag().getName() + "/life.txt");
        // A purge is said where the file was deleted from, not the bin.
        JsonNode purged = JSON.readTree((String) deliveriesOf(all).get(9).get("payload"));
        assertThat(purged.get("Records").get(0).get("eventName").asString()).isEqualTo("fm:ObjectPurged");
        assertThat(purged.get("fm").get("folder").get("id").asInt()).isEqualTo(chain.tagId());
        // A revision's event names that revision alone.
        JsonNode revisionDeleted = JSON.readTree((String) deliveriesOf(all).get(4).get("payload"));
        assertThat(revisionDeleted.get("Records")).hasSize(1);
        assertThat(revisionDeleted.get("fm").get("file").get("objects").get(0).get("version").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a move is sent to a subscription on the folder it left and on the one it reached, with where it came from")
    void aMove() throws Exception {
        FolderFixture.Chain other = FolderFixture.chain(folderRepository, tagGroupRepository, userRepository.getReferenceById(adminId));
        flushAndClear();
        int left = subscribe("left", WebhookScope.FOLDER, chain.tagId(), null, EnumSet.of(WebhookEvent.MOVED));
        int reached = subscribe("reached", WebhookScope.SUBTREE, other.categoryId(), null, EnumSet.of(WebhookEvent.MOVED));
        int neither = subscribe("neither", WebhookScope.FOLDER, chain.subCategoryId(), null, EnumSet.of(WebhookEvent.MOVED));
        FileDetailsDTO file = upload(chain.tagId(), "moving.txt", null);

        fileService.moveFile(file.getFileInfoId(), other.tagId(), adminId);
        flushAndClear();

        assertThat(deliveriesOf(left)).hasSize(1);
        assertThat(deliveriesOf(reached)).hasSize(1);
        assertThat(deliveriesOf(neither)).isEmpty();
        JsonNode body = JSON.readTree((String) deliveriesOf(left).getFirst().get("payload"));
        assertThat(body.get("Records").get(0).get("eventName").asString()).isEqualTo("fm:ObjectMoved");
        assertThat(body.get("Records").get(0).get("s3").get("bucket").get("name").asString())
                .isEqualTo(other.category().getName().toLowerCase(Locale.ROOT));
        assertThat(body.get("fm").get("movedFrom").get("bucket").asString())
                .isEqualTo(chain.category().getName().toLowerCase(Locale.ROOT));
        assertThat(body.get("fm").get("movedFrom").get("keys").get(0).asString())
                .isEqualTo(chain.subCategory().getName() + "/" + chain.tag().getName() + "/moving.txt");
        assertThat(body.get("fm").get("movedFrom").get("id").asInt()).isEqualTo(chain.tagId());
    }

    @Test
    @DisplayName("a folder deleted with its files: each file's delete, from where it was; restored: each file's restore")
    void aFolderDeletedAndRestored() {
        int sub = subscribe("sub", WebhookScope.SUBTREE, chain.subCategoryId(), null,
                EnumSet.of(WebhookEvent.FILE_DELETED, WebhookEvent.RESTORED));
        upload(chain.tagId(), "one.txt", null);
        upload(chain.tagId(), "two.txt", null);

        folderTreeDeleteService.deleteTree(chain.tagId(), adminId);
        flushAndClear();
        assertThat(deliveriesOf(sub)).extracting(row -> row.get("event")).containsExactly("FILE_DELETED", "FILE_DELETED");

        int entry = trashEntries.findAll().stream().filter(e -> e.getFolderId() != null && e.getFolderId() == chain.tagId())
                .findFirst().orElseThrow().getId();
        trashRestore.restore(entry, null, null, adminId);
        flushAndClear();
        List<Map<String, Object>> rows = deliveriesOf(sub);
        assertThat(rows).extracting(row -> row.get("event"))
                .containsExactly("FILE_DELETED", "FILE_DELETED", "RESTORED", "RESTORED");
        JsonNode restored = JSON.readTree((String) rows.get(2).get("payload"));
        assertThat(restored.get("fm").get("folder").get("id").asInt()).isEqualTo(chain.tagId());
        assertThat(restored.get("Records").get(0).get("s3").get("object").get("key").asString())
                .startsWith(chain.subCategory().getName() + "/" + chain.tag().getName() + "/");
    }

    @Test
    @DisplayName("a subscription follows its folder: renamed, then moved under another bucket - the key and the bucket as they are now")
    void followsItsFolder() {
        int sub = subscribe("follow", WebhookScope.SUBTREE, chain.subCategoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED));
        folderService.rename(chain.subCategoryId(), "Renamed" + TestData.nextSequence(), null, null, adminId);
        flushAndClear();
        String renamed = folderRepository.findById(chain.subCategoryId()).orElseThrow().getName();
        upload(chain.tagId(), "after-rename.txt", null);

        FolderFixture.Chain other = FolderFixture.chain(folderRepository, tagGroupRepository, userRepository.getReferenceById(adminId));
        flushAndClear();
        folderService.move(chain.subCategoryId(), other.categoryId(), adminId);
        flushAndClear();
        upload(chain.tagId(), "after-move.txt", null);

        List<Map<String, Object>> rows = deliveriesOf(sub);
        assertThat(rows).hasSize(2);
        JsonNode afterRename = JSON.readTree((String) rows.get(0).get("payload")).get("Records").get(0).get("s3");
        assertThat(afterRename.get("object").get("key").asString()).isEqualTo(renamed + "/" + chain.tag().getName() + "/after-rename.txt");
        JsonNode afterMove = JSON.readTree((String) rows.get(1).get("payload")).get("Records").get(0).get("s3");
        assertThat(afterMove.get("bucket").get("name").asString()).isEqualTo(other.category().getName().toLowerCase(Locale.ROOT));
        assertThat(afterMove.get("object").get("key").asString()).isEqualTo(renamed + "/" + chain.tag().getName() + "/after-move.txt");
    }

    @Test
    @DisplayName("CLASS: a file in a folder of the class or below one - and with a folder named, only below that folder")
    void byClass() {
        int classId = folderClassService.create(ClassFixture.definition("hooked" + TestData.nextSequence(), "کلاس آزمایشی",
                false, "NONE", null, List.of()), adminId);
        folderClassAssignment.set(chain.subCategoryId(), classId, adminId);
        flushAndClear();
        FolderFixture.Chain other = FolderFixture.chain(folderRepository, tagGroupRepository, userRepository.getReferenceById(adminId));
        flushAndClear();
        int anywhere = subscribe("anywhere", WebhookScope.CLASS, null, classId, EnumSet.of(WebhookEvent.FILE_CREATED));
        int belowOther = subscribe("below-other", WebhookScope.CLASS, other.categoryId(), classId, EnumSet.of(WebhookEvent.FILE_CREATED));

        upload(chain.tagId(), "classed.txt", null);
        upload(other.tagId(), "unclassed.txt", null);

        assertThat(deliveriesOf(anywhere)).extracting(row -> row.get("file_name")).containsExactly("classed");
        assertThat(deliveriesOf(belowOther)).isEmpty();
    }

    @Test
    @DisplayName("filters: the extensions, the name's glob, people only, and a key left out - its own uploads never come back to it")
    void filters() {
        int pdfOnly = subscribe("pdf-only", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED),
                "pdf", "", WebhookActors.ALL, "");
        int invoices = subscribe("invoices", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED),
                "", "invoice-*", WebhookActors.ALL, "");
        int key = apiKeyService.create(keyDto(chain.categoryId() + ":WRITE"), adminId).id();
        int people = subscribe("people", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED),
                "", "", WebhookActors.PEOPLE, "");
        int notItsOwn = subscribe("not-its-own", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED),
                "", "", WebhookActors.ALL, String.valueOf(key));

        upload(chain.tagId(), "invoice-7.txt", null);
        upload(chain.tagId(), "brochure.pdf", null);
        actAs(key);
        upload(chain.tagId(), "by-key.txt", null);
        SecurityContextHolder.clearContext();

        assertThat(deliveriesOf(pdfOnly)).extracting(row -> row.get("file_name")).containsExactly("brochure");
        assertThat(deliveriesOf(invoices)).extracting(row -> row.get("file_name")).containsExactly("invoice-7");
        assertThat(deliveriesOf(people)).extracting(row -> row.get("file_name")).containsExactly("invoice-7", "brochure");
        assertThat(deliveriesOf(notItsOwn)).extracting(row -> row.get("file_name")).containsExactly("invoice-7", "brochure");
        JsonNode byKey = JSON.readTree((String) deliveriesOf(pdfOnly).getFirst().get("payload"));
        assertThat(byKey.get("fm").get("actor").get("apiKeyId").isNull()).isTrue();
    }

    @Test
    @DisplayName("a paused subscription is still queued for - its deliveries wait; one deleted behind the cache's back fails nothing")
    void pausedAndDeleted() {
        int paused = subscribe("paused", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED));
        int gone = subscribe("gone", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED));
        webhookService.pause(paused, adminId);
        flushAndClear();
        ruleCache.refresh();
        // Deleted in the database - as another instance would - while this one's cache still holds it.
        jdbc.update("DELETE FROM webhook WHERE id = ?", gone);

        upload(chain.tagId(), "while-paused.txt", null);

        assertThat(deliveriesOf(paused)).hasSize(1);
        assertThat(deliveriesOf(gone)).isEmpty();
    }

    @Test
    @DisplayName("nothing in the bin is sent: a version purged where it was deleted from - a folder now in the bin itself")
    void notTheBin() {
        int sub = subscribe("bin", WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.allOf(WebhookEvent.class));
        FileDetailsDTO file = upload(chain.tagId(), "kept.txt", null);
        int version2 = addRevision(file.getFileInfoId(), "kept.txt", 2, "version", null);
        fileService.deleteFileDetails(file.getFileInfoId(), version2, adminId);
        flushAndClear();
        int revisionEntry = trashEntries.findAll().stream().filter(e -> version2 == orZero(e.getFileDetailsId()))
                .findFirst().orElseThrow().getId();
        folderTreeDeleteService.deleteTree(chain.subCategoryId(), adminId);
        flushAndClear();
        assertThat(deliveriesOf(sub)).extracting(row -> row.get("event"))
                .containsExactly("FILE_CREATED", "REVISION_CREATED", "REVISION_DELETED", "FILE_DELETED");

        // Recorded at the version's own folder, which went to the bin with the folder above it: nothing goes.
        trashPurge.purge(revisionEntry, adminId);
        flushAndClear();
        assertThat(deliveriesOf(sub)).hasSize(4);
    }

    // ------------------------------------------------------------------ pieces

    private int subscribe(String name, WebhookScope scope, Integer folderId, Integer classId, Set<WebhookEvent> events) {
        return subscribe(name, scope, folderId, classId, events, "", "", WebhookActors.ALL, "");
    }

    private int subscribe(String name, WebhookScope scope, Integer folderId, Integer classId, Set<WebhookEvent> events,
                          String extensions, String pattern, WebhookActors actors, String excludedKeys) {
        int id = webhookService.create(new WebhookService.Definition(name + TestData.nextSequence(), "http://127.0.0.1:9/hook",
                scope, folderId, classId, events, extensions, pattern, actors, excludedKeys, "NONE", null, null,
                null, null, null), adminId).id();
        flushAndClear();
        ruleCache.refresh();
        jdbc.update("UPDATE webhook SET name = ? WHERE id = ?", name, id);
        ruleCache.refresh();
        return id;
    }

    private List<Map<String, Object>> deliveriesOf(int webhookId) {
        entityManager.flush();
        return jdbc.queryForList("""
                SELECT event, event_id, state, file_name, CAST(payload AS text) AS payload
                FROM webhook_delivery WHERE webhook_id = ? ORDER BY id""", webhookId);
    }

    private FileDetailsDTO upload(int folderId, String fileName, String metadata) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMetadata(metadata);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", TestData.bytesFor(fileName)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PUBLIC);
        flushAndClear();
        return uploaded;
    }

    private int addRevision(int fileId, String fileName, int version, String type, Integer sampleId) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileId);
        String name = fileName.substring(0, fileName.lastIndexOf('.'));
        request.setFileName(name);
        request.setFileNameWithoutExtension(name);
        request.setVersion(version);
        request.setType(type);
        request.setFileDetailsId(sampleId);
        request.setFileDetailsDescription(type + " " + version);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", TestData.bytesFor(fileName)));
        fileService.createNewFileDetails(request, adminId);
        flushAndClear();
        return jdbc.queryForObject("SELECT max(id) FROM file_details WHERE file_info_id = ?", Integer.class, fileId);
    }

    private static List<String> keysOf(JsonNode body) {
        return body.get("fm").get("file").get("objects").valueStream().map(o -> o.get("key").asString()).toList();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private ApiKeyDTO keyDto(String grant) {
        ApiKeyDTO dto = new ApiKeyDTO();
        dto.setTitle("hook-writer" + TestData.nextSequence());
        dto.setFolderGrants(List.of(grant));
        return dto;
    }

    private void actAs(int apiKeyId) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(adminId);
        principal.setUsername(adminName);
        principal.setPassword("");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setApiKeyId(apiKeyId);
        principal.setPermissions(List.of());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
