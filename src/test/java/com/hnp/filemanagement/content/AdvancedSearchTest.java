package com.hnp.filemanagement.content;

import com.hnp.filemanagement.content.ContentSearchService.SearchRequest;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderMetadataService;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.metadata.MetadataPrecondition;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The advanced search (roadmap 12.8): every part alone and together - the words, a folder and those
 * below it, the name, the file's metadata and a folder's above it, kinds, dates, versions, the order -
 * and, as for every search, only what the reader may open: a folder chosen outside the reader's grants
 * is no results, never an error that says it is there; a described folder the reader cannot read is no
 * oracle; the chooser offers only folders the reader may walk into.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class AdvancedSearchTest extends DatabaseSupport {

    @Autowired
    private ContentSearchService underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderMetadataService folderMetadataService;
    @Autowired
    private FileContentRepository repository;
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
    @Autowired
    private NamedParameterJdbcTemplate named;

    private int adminId;
    private Folder folderA;
    private Folder folderB;
    private Folder deepInA;
    /** A word and a name part no other test writes. */
    private String run;
    private String tag;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        Folder root = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        folderA = FolderFixture.tag(folderRepository, root, admin, "پرونده‌الف" + TestData.nextSequence());
        folderB = FolderFixture.tag(folderRepository, root, admin, "پرونده‌ب" + TestData.nextSequence());
        deepInA = FolderFixture.tag(folderRepository, folderA, admin, "قراردادها" + TestData.nextSequence());
        run = "پیشرفته" + Long.toString(TestData.nextSequence() + 1000, 36).replaceAll("[0-9]", "");
        tag = "tag" + TestData.nextSequence();
    }

    // ================================================================ each part

    @Test
    @DisplayName("a folder alone: every file in it and below it, newest first - none beside it; the trail of each")
    void aFolder() {
        int top = upload("top", folderA, null);
        int deep = upload("deep", deepInA, null);
        int beside = upload("beside", folderB, null);

        List<ContentSearchService.Result> results = search(new SearchRequest(null, null, folderA.getId(), null, null, List.of(),
                null, null, false, null)).items();
        assertThat(results).extracting(r -> r.hit().fileDetailsId()).containsExactly(deep, top);
        assertThat(results.getFirst().trail()).endsWith(folderA.getDisplayName(), deepInA.getDisplayName());
        assertThat(results.getFirst().pages()).isEmpty();

        int rootId = folderRepository.findRoots().getFirst().getId();
        assertThat(ids(new SearchRequest(null, null, rootId, null, null, List.of(), null, null, false, null)))
                .as("the root, as any folder: everything below it").contains(deep, top, beside);
        User reader = userRepository.save(TestData.user());
        grant(reader, folderA.getId());
        assertThat(underTest.search(new SearchRequest(null, null, rootId, null, null, List.of(), null, null, false, null), 0, 50,
                reader.getId()).items()).extracting(r -> r.hit().fileDetailsId()).contains(deep, top).doesNotContain(beside);
    }

    @Test
    @DisplayName("the words and a folder together: a word in both folders found only in the one chosen")
    void wordsInAFolder() {
        int inA = upload("a", folderA, null);
        int inB = upload("b", folderB, null);
        read(inA, run + " مخزن بازرسی");
        read(inB, run + " مخزن بازرسی");

        assertThat(ids(new SearchRequest(run + " مخزن", null, null, null, null, List.of(), null, null, false, null)))
                .containsExactlyInAnyOrder(inA, inB);
        assertThat(ids(new SearchRequest(run + " مخزن", null, folderA.getId(), null, null, List.of(), null, null, false, null)))
                .containsExactly(inA);
        var found = search(new SearchRequest(run + " مخزن", null, folderA.getId(), null, null, List.of(), null, null, false, null));
        assertThat(found.items().getFirst().pages()).hasSize(1);
    }

    @Test
    @DisplayName("a fragment of the name, folded as names are; with the words, both required")
    void aName() {
        int report = upload("گزارش‌سالانه-" + tag, folderA, null);
        int letter = upload("نامه-" + tag, folderA, null);
        read(report, run + " بودجه");
        read(letter, run + " بودجه");

        assertThat(ids(new SearchRequest(null, "سالانه-" + tag, null, null, null, List.of(), null, null, false, null)))
                .containsExactly(report);
        assertThat(ids(new SearchRequest(null, "گزارش سالانه-" + tag.toUpperCase(), null, null, null, List.of(), null, null, false, null)))
                .as("a plain space for the half-space, any case - as the file list folds them").containsExactly(report);
        assertThat(ids(new SearchRequest(run + " بودجه", "نامه-" + tag, null, null, null, List.of(), null, null, false, null)))
                .containsExactly(letter);
        assertThat(ids(new SearchRequest(null, "%_" + tag, null, null, null, List.of(), null, null, false, null)))
                .as("a LIKE wildcard typed is a character, not a wildcard").isEmpty();
    }

    @Test
    @DisplayName("the file's metadata: an exact value, a number typed as text, a nested key typed dotted - and what it holds shown")
    void theFilesMetadata() {
        int contract = upload("c", folderA, "{\"run\":\"" + tag + "\",\"contractNo\":\"C-5678\",\"amount\":1500,"
                + "\"party\":{\"name\":\"علی\"},\"tags\":[\"signed\"]}");
        int other = upload("o", folderA, "{\"run\":\"" + tag + "\",\"contractNo\":\"C-9999\",\"amount\":\"1500\"}");

        assertThat(byMetadata("{\"run\":\"" + tag + "\",\"contractNo\":\"C-5678\"}")).containsExactly(contract);
        assertThat(byMetadata("{\"run\":\"" + tag + "\",\"amount\":\"1500\"}")).as("the number and the string")
                .containsExactlyInAnyOrder(contract, other);
        assertThat(byMetadata("{\"run\":\"" + tag + "\",\"amount\":1500}")).containsExactlyInAnyOrder(contract, other);
        assertThat(byMetadata("{\"run\":\"" + tag + "\",\"party.name\":\"علی\"}")).containsExactly(contract);
        assertThat(byMetadata("{\"run\":\"" + tag + "\",\"tags\":[\"signed\"]}")).containsExactly(contract);
        assertThat(byMetadata("{\"run\":\"" + tag + "\",\"contractNo\":\"C-56\"}")).as("a value matches whole").isEmpty();

        var result = search(new SearchRequest(null, null, null, "{\"run\":\"" + tag + "\",\"party.name\":\"علی\"}", null,
                List.of(), null, null, false, null)).items().getFirst();
        assertThat(result.metadata()).extracting(ContentSearchService.MetadataValue::key, ContentSearchService.MetadataValue::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("run", tag), org.assertj.core.groups.Tuple.tuple("party.name", "علی"));
        assertThat(result.hit().metadata()).as("the document travels only when asked").isNotNull();
        assertThat(search(new SearchRequest(null, null, folderA.getId(), null, null, List.of(), null, null, false, null))
                .items()).allSatisfy(r -> assertThat(r.hit().metadata()).isNull());
    }

    @Test
    @DisplayName("a folder's metadata above the file: everything below it; not to a reader who cannot read the folder itself")
    void aFoldersMetadata() {
        int top = upload("t", folderA, null);
        int deep = upload("d", deepInA, null);
        upload("b", folderB, null);
        folderMetadataService.replace(folderA.getId(), "{\"run\":\"" + tag + "\",\"nationalCode\":\"0012345678\"}",
                MetadataPrecondition.NONE, adminId);
        flushAndClear();
        String probe = "{\"run\":\"" + tag + "\",\"nationalCode\":\"0012345678\"}";
        SearchRequest request = new SearchRequest(null, null, null, null, probe, List.of(), null, null, false, null);

        assertThat(ids(request)).containsExactly(deep, top);
        User reader = userRepository.save(TestData.user());
        grant(reader, deepInA.getId());
        assertThat(underTest.search(request, 0, 50, reader.getId()).items()).as("folder A itself is not the reader's").isEmpty();
        grant(reader, folderA.getId());
        assertThat(underTest.search(request, 0, 50, reader.getId()).items()).extracting(r -> r.hit().fileDetailsId())
                .containsExactly(deep, top);
    }

    @Test
    @DisplayName("kinds and dates: the kinds named, the days between, the last day included")
    void kindsAndDates() {
        int text = upload("k", folderA, null);
        int old = upload("old", folderA, null);
        entityManager.flush();
        jdbc.update("UPDATE file_details SET created_at = '2024-01-15 10:00:00+00' WHERE id = ?", old);
        jdbc.update("UPDATE file_details SET file_extension = 'PDF' WHERE id = ?", text);

        assertThat(ids(new SearchRequest(null, null, folderA.getId(), null, null, List.of("pdf"), null, null, false, null)))
                .as("a kind compared without case").containsExactly(text);
        assertThat(ids(new SearchRequest(null, null, folderA.getId(), null, null, List.of(".TXT, docx"), null, null, false, null)))
                .containsExactly(old);
        assertThat(ids(new SearchRequest(null, null, folderA.getId(), null, null, List.of(), "2024-01-01", "2024-01-15", false, null)))
                .as("the last day included").containsExactly(old);
        assertThat(ids(new SearchRequest(null, null, folderA.getId(), null, null, List.of(), "2024-01-16", null, false, null)))
                .containsExactly(text);
    }

    @Test
    @DisplayName("versions: the latest by default, every one when asked - the metadata of each revision its own")
    void versions() {
        int v1 = upload("plan", folderA, "{\"run\":\"" + tag + "\",\"stage\":\"draft\"}");
        int v2 = newVersion(v1, "{\"run\":\"" + tag + "\",\"stage\":\"final\"}");

        assertThat(byMetadata("{\"run\":\"" + tag + "\",\"stage\":\"final\"}")).containsExactly(v2);
        assertThat(byMetadata("{\"run\":\"" + tag + "\",\"stage\":\"draft\"}")).isEmpty();
        assertThat(ids(new SearchRequest(null, null, null, "{\"run\":\"" + tag + "\",\"stage\":\"draft\"}", null, List.of(), null,
                null, true, null))).containsExactly(v1);
    }

    @Test
    @DisplayName("the order: the best match, the newest or the oldest first; a page at a time")
    void order() {
        List<Integer> uploaded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int id = upload("o" + i, folderA, null);
            read(id, run + " ترتیب" + " ترتیب".repeat(i));
            uploaded.add(id);
        }
        List<Integer> newest = ids(new SearchRequest(run + " ترتیب", null, null, null, null, List.of(), null, null, false, "newest"));
        assertThat(newest).containsExactlyElementsOf(uploaded.reversed());
        assertThat(ids(new SearchRequest(run + " ترتیب", null, null, null, null, List.of(), null, null, false, "oldest")))
                .containsExactlyElementsOf(uploaded);
        assertThat(ids(new SearchRequest(run + " ترتیب", null, null, null, null, List.of(), null, null, false, null)).getFirst())
                .as("the page holding the word most, first").isEqualTo(uploaded.getLast());

        List<Integer> seen = new ArrayList<>();
        for (int page = 0; ; page++) {
            var slice = underTest.search(new SearchRequest(null, null, folderA.getId(), null, null, List.of(), null, null, false,
                    "oldest"), page, 2, adminId);
            slice.items().forEach(r -> seen.add(r.hit().fileDetailsId()));
            if (!slice.hasNext()) {
                break;
            }
        }
        assertThat(seen).containsExactlyElementsOf(uploaded);
    }

    @Test
    @DisplayName("every part together: each one left out in turn lets in the file it alone kept out")
    void everythingTogether() {
        folderMetadataService.replace(folderA.getId(), "{\"run\":\"" + tag + "\",\"owner\":\"P-1\"}", MetadataPrecondition.NONE, adminId);
        folderMetadataService.replace(folderB.getId(), "{\"run\":\"" + tag + "\",\"owner\":\"P-2\"}", MetadataPrecondition.NONE, adminId);
        String document = "{\"run\":\"" + tag + "\",\"status\":\"signed\"}";
        int wanted = upload("اجاره-" + tag, deepInA, document);
        int otherWords = upload("اجاره-" + tag, deepInA, document);
        int otherName = upload("نامه-" + tag, deepInA, document);
        int otherStatus = upload("اجاره-" + tag, deepInA, "{\"run\":\"" + tag + "\",\"status\":\"draft\"}");
        int otherFolder = upload("اجاره-" + tag, folderB, document);
        int otherDay = upload("اجاره-" + tag, deepInA, document);
        for (int id : List.of(wanted, otherName, otherStatus, otherFolder, otherDay)) {
            read(id, run + " قرارداد اجاره");
        }
        read(otherWords, run + " نامه اداری");
        jdbc.update("UPDATE file_details SET created_at = '2024-01-15 10:00:00+00' WHERE id = ?", otherDay);
        flushAndClear();

        String q = run + " اجاره";
        String name = "اجاره-" + tag;
        String owner = "{\"run\":\"" + tag + "\",\"owner\":\"P-1\"}";
        String from = "2025-01-01";
        assertThat(ids(new SearchRequest(q, name, folderA.getId(), document, owner, List.of("txt"), from, null, false, "newest")))
                .containsExactly(wanted);
        assertThat(ids(new SearchRequest(null, name, folderA.getId(), document, owner, List.of("txt"), from, null, false, "newest")))
                .as("no words").containsExactly(otherWords, wanted);
        assertThat(ids(new SearchRequest(q, null, folderA.getId(), document, owner, List.of("txt"), from, null, false, "newest")))
                .as("no name").containsExactly(otherName, wanted);
        assertThat(ids(new SearchRequest(q, name, folderA.getId(), null, owner, List.of("txt"), from, null, false, "newest")))
                .as("no file metadata").containsExactly(otherStatus, wanted);
        assertThat(ids(new SearchRequest(q, name, null, document, null, List.of("txt"), from, null, false, "newest")))
                .as("no folder, no folder metadata").containsExactly(otherFolder, wanted);
        assertThat(ids(new SearchRequest(q, name, folderA.getId(), document, owner, List.of("txt"), null, null, false, "newest")))
                .as("no day").containsExactly(otherDay, wanted);
        assertThat(ids(new SearchRequest(q, name, folderA.getId(), document, owner, List.of("pdf"), from, null, false, "newest")))
                .as("another kind").isEmpty();
    }

    // ================================================================ access

    @Test
    @DisplayName("a folder chosen outside the reader's grants is no results - and the page shows no name for it")
    void aFolderOutsideTheGrants() {
        int inA = upload("a", folderA, null);
        int inB = upload("b", folderB, null);
        read(inA, run + " محرمانه");
        read(inB, run + " محرمانه");
        User reader = userRepository.save(TestData.user());
        grant(reader, folderA.getId());

        assertThat(underTest.search(new SearchRequest(run, null, folderB.getId(), null, null, List.of(), null, null, false, null),
                0, 10, reader.getId()).items()).isEmpty();
        assertThat(underTest.search(new SearchRequest(null, null, folderB.getId(), null, null, List.of(), null, null, false, null),
                0, 10, reader.getId()).items()).isEmpty();
        assertThat(underTest.folderChoice(folderB.getId(), reader.getId())).isEmpty();
        assertThat(underTest.folderChoice(folderA.getId(), reader.getId())).hasValueSatisfying(choice ->
                assertThat(choice.trail()).endsWith(folderA.getDisplayName()));
        assertThat(underTest.search(new SearchRequest(null, null, Integer.MAX_VALUE, null, null, List.of(), null, null, false, null),
                0, 10, reader.getId()).items()).as("a folder that does not exist: nothing, not an error").isEmpty();
    }

    @Test
    @DisplayName("the chooser offers only folders the reader may walk into, each with its trail")
    void theChooser() {
        User reader = userRepository.save(TestData.user());
        grant(reader, deepInA.getId());
        List<ContentSearchService.FolderChoice> offered = underTest.folderChoices("پرونده", reader.getId());
        assertThat(offered).extracting(ContentSearchService.FolderChoice::id).contains(folderA.getId()).doesNotContain(folderB.getId());
        assertThat(underTest.folderChoices("قراردادها", reader.getId())).extracting(ContentSearchService.FolderChoice::trail)
                .anySatisfy(trail -> assertThat(trail).contains(folderA.getDisplayName() + " / " + deepInA.getDisplayName()));
        assertThat(underTest.folderChoices("  ", reader.getId())).isEmpty();
    }

    // ================================================================ refusals

    @Test
    @DisplayName("refused, with the reason: nothing asked, a kind that is not one, a date that is not one, days reversed, too many keys")
    void refusals() {
        assertThatThrownBy(() -> search(new SearchRequest(" ", " ", null, " ", null, List.of(), null, null, false, null)))
                .isInstanceOf(InvalidDataException.class).hasFieldOrPropertyWithValue("messageCode", java.util.Optional.of("contentSearch.empty"));
        assertThatThrownBy(() -> search(new SearchRequest(null, null, folderA.getId(), null, null, List.of("p d f"), null, null, false, null)))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> search(new SearchRequest(null, null, folderA.getId(), null, null, List.of(), "1403/07/01", null, false, null)))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> search(new SearchRequest(null, null, folderA.getId(), null, null, List.of(), "1403-07-01", null, false, null)))
                .as("a Jalali date typed by habit is refused, not read as the year 1403").isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> search(new SearchRequest(null, null, folderA.getId(), null, null, List.of(), null, "+12026-01-01", false, null)))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> search(new SearchRequest(null, null, folderA.getId(), null, null, List.of(), "2026-02-01", "2026-01-01", false, null)))
                .isInstanceOf(InvalidDataException.class);
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 11; i++) {
            many.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"v\"");
        }
        assertThatThrownBy(() -> search(new SearchRequest(null, null, null, many.append("}").toString(), null, List.of(), null, null,
                false, null))).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> search(new SearchRequest(null, null, null, "[1]", null, List.of(), null, null, false, null)))
                .as("the metadata checked as an upload's is").isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("each path planned on its index: the files alone through the metadata's GIN index and the folder path's")
    void planned() {
        FolderReadScope restricted = FolderReadScope.ofUser(adminId, false);
        SearchCriteria byMetadata = new SearchCriteria(null, null, null, MetadataConditions.of(
                new com.hnp.filemanagement.shared.metadata.MetadataDocument("{\"contractNo\":\"C-5678\"}")), List.of(), List.of(),
                List.of(), null, null, false, SearchCriteria.Sort.RELEVANCE);
        jdbc.execute("SET LOCAL enable_seqscan = off");
        jdbc.execute("SET LOCAL enable_indexscan = off");
        jdbc.execute("SET LOCAL enable_indexonlyscan = off");
        String plan = String.join("\n", named.queryForList("EXPLAIN " + PostgresContentSearch.searchSql(byMetadata, restricted,
                PostgresContentSearch.Path.FILES_ONLY), PostgresContentSearch.parameters(byMetadata, restricted)
                .addValue("limit", 21).addValue("offset", 0L), String.class));
        assertThat(plan).contains("ix_file_details_metadata");

        SearchCriteria inAFolder = new SearchCriteria(null, null, folderA.getPath(), List.of(), List.of(), List.of(), List.of(), null,
                null, false, SearchCriteria.Sort.NEWEST);
        String folderPlan = String.join("\n", named.queryForList("EXPLAIN " + PostgresContentSearch.searchSql(inAFolder, restricted,
                PostgresContentSearch.Path.FILES_ONLY), PostgresContentSearch.parameters(inAFolder, restricted)
                .addValue("limit", 21).addValue("offset", 0L), String.class));
        assertThat(folderPlan).contains("ix_folder_path");
    }

    // ================================================================ helpers

    private ContentSearchService.Results search(SearchRequest request) {
        return underTest.search(request, 0, 50, adminId);
    }

    private List<Integer> ids(SearchRequest request) {
        return search(request).items().stream().map(r -> r.hit().fileDetailsId()).toList();
    }

    private List<Integer> byMetadata(String document) {
        return ids(new SearchRequest(null, null, null, document, null, List.of(), null, null, false, null));
    }

    private void read(int fileDetailsId, String text) {
        entityManager.flush();
        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = now() + interval '1 hour' WHERE file_details_id = ?",
                fileDetailsId);
        assertThat(repository.finish(fileDetailsId, new FileContentRepository.Outcome("DONE", "TEXT", "text/plain", null, false,
                        null, null, text.length()),
                List.of(new FileContentRepository.PageRow(1, 0, "PAGE", null, "TEXT", null, text, ContentFolding.fold(text))),
                Instant.now())).isTrue();
    }

    private int upload(String name, Folder folder, String metadata) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(name);
        request.setFileNameDescription(name);
        request.setFolderId(folder.getId());
        request.setMetadata(metadata);
        String unique = name + "-" + TestData.nextSequence() + ".txt";
        request.setMultipartFile(new MockMultipartFile("file", unique, "text/plain", unique.getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PRIVATE);
        entityManager.flush();
        return uploaded.getId();
    }

    private int newVersion(int fileDetailsId, String metadata) {
        var row = jdbc.queryForMap("SELECT d.file_info_id, d.file_name FROM file_details d WHERE d.id = ?", fileDetailsId);
        int fileInfoId = (Integer) row.get("file_info_id");
        String fileName = (String) row.get("file_name");
        String title = fileName.substring(0, fileName.lastIndexOf('.'));
        FileUploadDTO next = new FileUploadDTO();
        next.setFileId(fileInfoId);
        next.setFileName(title);
        next.setFileNameWithoutExtension(title);
        next.setVersion(2);
        next.setType("version");
        next.setFileDetailsDescription("v2");
        next.setMetadata(metadata);
        next.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", "v2".getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFileDetails(next, adminId);
        entityManager.flush();
        return jdbc.queryForObject("SELECT id FROM file_details WHERE file_info_id = ? AND version = 2", Integer.class, fileInfoId);
    }

    private void grant(User user, int folder) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(loaded.getFolderGrants());
        grants.add(new UserFolderGrant(loaded, folderRepository.findById(folder).orElseThrow(), FolderPermission.READ));
        loaded.replaceFolderGrants(grants);
        userRepository.save(loaded);
        flushAndClear();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
