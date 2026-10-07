package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.file.domain.MetadataSearchService;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Search by metadata (roadmap 12.2 step 3, 12.3 step 4): containment on the current document, files
 * below a described folder at any depth and nowhere else, only what the reader may read, paged - and
 * planned on the GIN indexes, not by reading the tables.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class MetadataSearchTest extends DatabaseSupport {

    @Autowired
    private MetadataSearchService underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderMetadataService folderMetadataService;
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

    private User admin;
    private int adminId;
    private Folder erp;
    private Folder personA;
    private Folder personB;
    private Folder contractsOfA;
    private String tag;

    @BeforeEach
    void setUp() {
        admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        erp = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        personA = FolderFixture.tag(folderRepository, erp, admin, "P-A" + TestData.nextSequence());
        personB = FolderFixture.tag(folderRepository, erp, admin, "P-B" + TestData.nextSequence());
        contractsOfA = FolderFixture.tag(folderRepository, personA, admin, "contracts" + TestData.nextSequence());
        // A value no other test writes, so the searches see this test's rows only.
        tag = "run-" + TestData.nextSequence();
    }

    @Test
    @DisplayName("containment on the current document: a key, a nested part, an array member, a number, Persian")
    void containment() {
        FileDetailsDTO deal = upload("deal.txt", personA, "{\"run\":\"" + tag + "\",\"contractNo\":\"C-5678\","
                + "\"party\":{\"code\":\"P-1234\",\"name\":\"علی\"},\"tags\":[\"urgent\",\"signed\"],\"amount\":1500}");
        FileDetailsDTO other = upload("other.txt", personB, "{\"run\":\"" + tag + "\",\"contractNo\":\"C-9999\"}");
        upload("bare.txt", personB, null);

        assertThat(files("{\"run\":\"" + tag + "\"}")).containsExactly(other.getFileInfoId(), deal.getFileInfoId());
        assertThat(files("{\"run\":\"" + tag + "\",\"contractNo\":\"C-5678\"}")).containsExactly(deal.getFileInfoId());
        assertThat(files("{\"run\":\"" + tag + "\",\"party\":{\"name\":\"علی\"}}")).containsExactly(deal.getFileInfoId());
        assertThat(files("{\"run\":\"" + tag + "\",\"tags\":[\"signed\"]}")).containsExactly(deal.getFileInfoId());
        assertThat(files("{\"run\":\"" + tag + "\",\"amount\":1500}")).containsExactly(deal.getFileInfoId());
        assertThat(files("{\"run\":\"" + tag + "\",\"amount\":\"1500\"}")).as("a string is not a number").isEmpty();
        assertThat(files("{\"run\":\"" + tag + "\",\"contractNo\":\"c-5678\"}")).as("values compare exactly").isEmpty();
    }

    @Test
    @DisplayName("a file is found by its current document only - an older version's does not count")
    void currentOnly() {
        FileDetailsDTO file = upload("report.txt", personA, "{\"run\":\"" + tag + "\",\"stage\":\"draft\"}");
        FileUploadDTO next = new FileUploadDTO();
        next.setFileId(file.getFileInfoId());
        next.setFileName("report");
        next.setFileNameWithoutExtension("report");
        next.setVersion(2);
        next.setType("version");
        next.setFileDetailsDescription("v2");
        next.setMetadata("{\"run\":\"" + tag + "\",\"stage\":\"signed\"}");
        next.setMultipartFile(new MockMultipartFile("file", "report.txt", "text/plain", "v2".getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFileDetails(next, adminId);
        flushAndClear();

        assertThat(files("{\"run\":\"" + tag + "\",\"stage\":\"draft\"}")).isEmpty();
        var hit = underTest.files("{\"run\":\"" + tag + "\",\"stage\":\"signed\"}", null, 0, 10, adminId).items().getFirst();
        assertThat(hit.version()).isEqualTo(2);
        assertThat(hit.metadata()).contains("signed");
    }

    @Test
    @DisplayName("by a folder's document: every file below it at any depth, none beside it; with a file's too, both must hold")
    void byTheFolders() {
        FileDetailsDTO top = upload("id-card.txt", personA, "{\"kind\":\"id\"}");
        FileDetailsDTO deep = upload("contract.txt", contractsOfA, "{\"kind\":\"contract\"}");
        FileDetailsDTO elsewhere = upload("b.txt", personB, "{\"kind\":\"contract\"}");
        folderMetadataService.replace(personA.getId(), "{\"run\":\"" + tag + "\",\"nationalCode\":\"0012345678\"}",
                MetadataPrecondition.NONE, adminId);
        folderMetadataService.replace(personB.getId(), "{\"run\":\"" + tag + "\",\"nationalCode\":\"0099999999\"}",
                MetadataPrecondition.NONE, adminId);
        flushAndClear();

        String a = "{\"run\":\"" + tag + "\",\"nationalCode\":\"0012345678\"}";
        assertThat(ids(underTest.files(null, a, 0, 10, adminId))).containsExactly(deep.getFileInfoId(), top.getFileInfoId());
        assertThat(ids(underTest.files("{\"kind\":\"contract\"}", a, 0, 10, adminId))).containsExactly(deep.getFileInfoId());
        assertThat(ids(underTest.files(null, "{\"run\":\"" + tag + "\"}", 0, 10, adminId)))
                .containsExactly(elsewhere.getFileInfoId(), deep.getFileInfoId(), top.getFileInfoId());

        assertThat(underTest.folders("{\"run\":\"" + tag + "\"}", 0, 10, adminId).items())
                .extracting(MetadataSearchRepository.FolderHit::folderId).containsExactly(personB.getId(), personA.getId());
        assertThat(underTest.folders(a, 0, 10, adminId).items()).singleElement()
                .satisfies(hit -> assertThat(hit.metadata()).contains("0012345678"));
    }

    @Test
    @DisplayName("only what the reader may read; a page at a time; asking for nothing is refused")
    void accessAndPages() {
        List<Integer> inA = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            inA.addFirst(upload("a" + i + ".txt", personA, "{\"run\":\"" + tag + "\"}").getFileInfoId());
        }
        int inB = upload("b.txt", personB, "{\"run\":\"" + tag + "\"}").getFileInfoId();
        folderMetadataService.replace(personA.getId(), "{\"run\":\"" + tag + "\"}", MetadataPrecondition.NONE, adminId);
        folderMetadataService.replace(personB.getId(), "{\"run\":\"" + tag + "\"}", MetadataPrecondition.NONE, adminId);

        User reader = userRepository.save(TestData.user());
        String doc = "{\"run\":\"" + tag + "\"}";
        flushAndClear();
        assertThat(underTest.files(doc, null, 0, 10, reader.getId()).items()).as("granted nothing").isEmpty();
        assertThat(underTest.folders(doc, 0, 10, reader.getId()).items()).isEmpty();

        grant(reader, personA.getId());
        List<Integer> seen = new ArrayList<>();
        for (int page = 0; ; page++) {
            var slice = underTest.files(doc, null, page, 2, reader.getId());
            assertThat(slice.items()).hasSizeLessThanOrEqualTo(2);
            slice.items().forEach(hit -> seen.add(hit.fileId()));
            if (!slice.hasNext()) {
                break;
            }
        }
        assertThat(seen).containsExactlyElementsOf(inA).doesNotContain(inB);
        assertThat(underTest.folders(doc, 0, 10, reader.getId()).items()).extracting(MetadataSearchRepository.FolderHit::folderId)
                .containsExactly(personA.getId());

        assertThatThrownBy(() -> underTest.files(null, "{}", 0, 10, adminId)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.folders(" ", 0, 10, adminId)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.files("[1]", null, 0, 10, adminId)).isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a folder's document is no oracle: a reader of the files below it but not of the folder learns nothing of it")
    void aFolderReadOnlyFromBelowIsNotSearchable() {
        FileDetailsDTO deep = upload("contract.txt", contractsOfA, "{\"kind\":\"contract\"}");
        folderMetadataService.replace(personA.getId(), "{\"run\":\"" + tag + "\",\"nationalCode\":\"0012345678\"}",
                MetadataPrecondition.NONE, adminId);
        User reader = userRepository.save(TestData.user());
        flushAndClear();
        grant(reader, contractsOfA.getId());
        String probe = "{\"run\":\"" + tag + "\",\"nationalCode\":\"0012345678\"}";
        assertThat(underTest.files(null, probe, 0, 10, reader.getId()).items()).as("P-A itself is not the reader's").isEmpty();
        assertThat(underTest.folders(probe, 0, 10, reader.getId()).items()).isEmpty();

        grant(reader, personA.getId());
        assertThat(ids(underTest.files(null, probe, 0, 10, reader.getId()))).containsExactly(deep.getFileInfoId());
    }

    @Test
    @DisplayName("each search is planned on its indexes - the GIN ones and the folder path's - never by reading a table whole")
    void plannedOnTheIndexes() {
        FolderReadScope restricted = FolderReadScope.ofUser(adminId, false);
        MapSqlParameterSource parameters = MetadataSearchRepository.scoped(restricted)
                .addValue("document", "{\"a\":1}").addValue("folderDocument", "{\"b\":2}")
                .addValue("limit", 51).addValue("offset", 0L);
        // The queue of folders without metadata (FolderRepository.findUndescribedChildren, as it is
        // written in SQL): read off the partial index in its order - a page with no sort, however
        // many children the folder has. Planned on the shape it serves - a folder of thousands of
        // undescribed children, the ERP's - since on a handful the planner rightly sorts them.
        Folder wide = FolderFixture.tag(folderRepository, erp, admin, "wide" + TestData.nextSequence());
        entityManager.flush();
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path,
                                    depth, kind, enabled, state, created_at)
                SELECT n.id, ?, 'P-' || n.g, 'P-' || n.g, 'P-' || n.g, 'P-' || n.g, ? || n.id || '/', ? || 'P-' || n.g || '/',
                       ?, 'FOLDER', 1, 0, now() - n.g * interval '1 minute'
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, 2000) g) n
                """, wide.getId(), wide.getPath(), wide.getKeyPath(), wide.getDepth() + 1);
        jdbc.execute("ANALYZE folder");
        jdbc.execute("SET LOCAL enable_seqscan = off");
        String queue = String.join("\n", jdbc.queryForList("""
                EXPLAIN SELECT f.id FROM folder f
                WHERE f.parent_id = ? AND f.metadata IS NULL AND (TRUE OR f.id IN (-1))
                ORDER BY f.created_at DESC, f.id DESC LIMIT 51""", String.class, wide.getId()));
        assertThat(queue).contains("ix_folder_undescribed").doesNotContain("Sort");

        // As SearchIndexTest plans: only scans with an index condition left, so a plan on a near-empty
        // table cannot read one whole by a full index scan - the question is whether the index can
        // serve the search, which on a table of millions of rows is the plan the planner takes.
        jdbc.execute("SET LOCAL enable_seqscan = off");
        jdbc.execute("SET LOCAL enable_indexscan = off");
        jdbc.execute("SET LOCAL enable_indexonlyscan = off");

        assertThat(plan(MetadataSearchRepository.filesSql(true, false, restricted), parameters)).contains("ix_file_details_metadata");
        assertThat(plan(MetadataSearchRepository.filesSql(false, true, restricted), parameters))
                .contains("ix_folder_metadata", "ix_folder_path");
        // Both asked: either document's index may lead - the more selective - and the other is checked
        // through its own index or the folder path's.
        assertThat(plan(MetadataSearchRepository.filesSql(true, true, FolderReadScope.everything()), parameters))
                .containsAnyOf("ix_file_details_metadata", "ix_folder_metadata").contains("ix_folder_path");
        assertThat(plan(MetadataSearchRepository.foldersSql(restricted), parameters)).contains("ix_folder_metadata");
    }

    // ================================================================ helpers

    private String plan(String sql, MapSqlParameterSource parameters) {
        return String.join("\n", named.queryForList("EXPLAIN " + sql, parameters, String.class));
    }

    private List<Integer> files(String document) {
        return ids(underTest.files(document, null, 0, 50, adminId));
    }

    private static List<Integer> ids(MetadataSearchService.Slice<MetadataSearchRepository.FileHit> slice) {
        return slice.items().stream().map(MetadataSearchRepository.FileHit::fileId).toList();
    }

    private FileDetailsDTO upload(String fileName, Folder folder, String metadata) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folder.getId());
        request.setMetadata(metadata);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PRIVATE);
        entityManager.flush();
        return uploaded;
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
