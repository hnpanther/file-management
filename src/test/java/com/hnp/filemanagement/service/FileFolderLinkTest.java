package com.hnp.filemanagement.service;

import com.hnp.filemanagement.config.bootstrap.FolderReadinessReport;
import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A file's folder (roadmap 7.2, step 1): written with every upload, agreeing with the mirror,
 * and read by nothing yet.
 *
 * <p>The column exists so that step 3 can move the readers over one at a time against data that
 * has already been backfilled and dual-written in production. What is asserted here is the data
 * half of that bargain: every route that creates a file links it to the folder mirroring its main
 * tag, the migration's own backfill statement produces the same link for rows that predate the
 * column, and nothing that used to work - the v1 API in particular - has changed shape.
 *
 * <p>Rolled back per test: the fixtures are built through the services so the mirror exists, and
 * MockMvc runs on the calling thread, so the web round trip joins the same transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FileFolderLinkTest extends MySqlSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private FileCategoryService fileCategoryService;
    @Autowired
    private FileSubCategoryService fileSubCategoryService;
    @Autowired
    private MainTagFileService mainTagFileService;
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FolderReadinessReport folderReadinessReport;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private int principalId;
    private int generalTagId;
    private int categoryId;
    private int subCategoryId;
    private int mainTagId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();
        generalTagId = generalTagRepository.save(TestData.generalTag(creator, "gt" + TestData.nextSequence())).getId();

        // Through the services, so the mirror is written the way production writes it.
        FileCategoryDTO category = new FileCategoryDTO();
        category.setCategoryName("Cat" + TestData.nextSequence());
        category.setCategoryNameDescription(category.getCategoryName() + " label");
        category.setDescription("a category");
        category.setGeneralTagId(generalTagId);
        fileCategoryService.createCategory(category, principalId);
        categoryId = fileCategoryRepository.findAll().stream()
                .filter(c -> c.getCategoryName().equals(category.getCategoryName())).findFirst().orElseThrow().getId();

        FileSubCategoryDTO subCategory = new FileSubCategoryDTO();
        subCategory.setSubCategoryName("Sub" + TestData.nextSequence());
        subCategory.setSubCategoryNameDescription(subCategory.getSubCategoryName() + " label");
        subCategory.setDescription("a sub-category");
        subCategory.setFileCategoryId(categoryId);
        fileSubCategoryService.createFileSubCategory(subCategory, principalId);
        subCategoryId = fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getSubCategoryName().equals(subCategory.getSubCategoryName())).findFirst().orElseThrow().getId();

        MainTagFileDTO tag = new MainTagFileDTO();
        tag.setTagName("Tag" + TestData.nextSequence());
        tag.setTagNameDescription(tag.getTagName() + " label");
        tag.setDescription("a tag " + tag.getTagName());
        tag.setFileSubCategoryId(subCategoryId);
        tag.setFileCategoryId(categoryId);
        tag.setType(0);
        mainTagFileService.createMainTagFile(tag, principalId);
        mainTagId = mainTagFileRepository.findAll().stream()
                .filter(t -> t.getTagName().equals(tag.getTagName())).findFirst().orElseThrow().getId();
    }

    // ---------------------------------------------------------------- the link is written

    @Test
    @DisplayName("an upload links the file to the folder that mirrors its main tag")
    void anUploadLinksTheFileToItsTagsFolder() {
        FileDetailsDTO stored = fileService.createNewFile(uploadRequest("report.txt", mainTagId), principalId, 1);
        flushAndClear();

        FileInfo file = fileInfoRepository.findById(stored.getFileInfoId()).orElseThrow();
        Folder mirror = folderRepository.findBySourceTypeAndSourceId(FolderSourceType.MAIN_TAG, mainTagId).orElseThrow();

        assertThat(file.getFolder()).isNotNull();
        assertThat(file.getFolder().getId()).isEqualTo(mirror.getId());
        assertThat(file.getMainTagFile().getId()).as("the old key is still written").isEqualTo(mainTagId);
        assertThat(fileInfoRepository.findRowsWhoseFolderDisagreesWithTheMirror()).isEmpty();
    }

    /**
     * The route integrations use, unchanged: the same three ids in, the same JSON out. The only
     * difference is a column on the row it creates, which the response does not carry.
     */
    @Test
    @DisplayName("the v1 API still takes the taxonomy triple and answers as before, and links the file too")
    void theV1ApiIsUnchangedAndLinksTheFile() throws Exception {
        UserDetailsImpl machine = new UserDetailsImpl();
        machine.setId(principalId);
        machine.setUsername("machine");
        machine.setPassword("irrelevant");
        machine.setEnabled(1);
        machine.setState(0);
        machine.setLoginType(0);
        machine.setPermissions(List.of(PermissionEnum.API_SAVE_NEW_FILE));

        String body = mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", "policy.txt", "text/plain",
                                "policy".getBytes(StandardCharsets.UTF_8)))
                        .param("description", "a policy")
                        .param("fileCategoryId", String.valueOf(categoryId))
                        .param("fileSubCategoryId", String.valueOf(subCategoryId))
                        .param("mainTagFileId", String.valueOf(mainTagId))
                        .with(user(machine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").isNumber())
                .andExpect(jsonPath("$.fileDetailsId").isNumber())
                .andExpect(jsonPath("$.fileName").value("policy.txt"))
                .andExpect(jsonPath("$.fileExtension").value("txt"))
                .andExpect(jsonPath("$.folderId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        flushAndClear();

        int fileId = com.jayway.jsonpath.JsonPath.read(body, "$.fileId");
        FileInfo file = fileInfoRepository.findById(fileId).orElseThrow();
        assertThat(file.getFolder().getSourceType()).isEqualTo(FolderSourceType.MAIN_TAG);
        assertThat(file.getFolder().getSourceId()).isEqualTo(mainTagId);
    }

    /**
     * A tag written straight through a repository has no mirror. An upload into it must not fail,
     * and must not store a null: the folder is created on the spot, ancestry and all, exactly as
     * the mirror already does for taxonomy writes.
     */
    @Test
    @DisplayName("an upload into an unmirrored tag heals the mirror and links the file to it")
    void anUnmirroredTagIsHealedByTheUpload() throws Exception {
        User creator = userRepository.getReferenceById(principalId);
        GeneralTag generalTag = generalTagRepository.getReferenceById(generalTagId);
        FileCategory category = fileCategoryRepository.save(TestData.category(creator, generalTag, "behind" + TestData.nextSequence()));
        FileSubCategory subCategory = fileSubCategoryRepository.save(TestData.subCategory(creator, category, "behind" + TestData.nextSequence()));
        MainTagFile tag = mainTagFileRepository.save(TestData.mainTag(creator, subCategory, "behind" + TestData.nextSequence()));
        Files.createDirectories(Paths.get(baseDir, category.getCategoryName(), subCategory.getSubCategoryName()));

        assertThat(folderRepository.findBySourceTypeAndSourceId(FolderSourceType.MAIN_TAG, tag.getId()))
                .as("nothing mirrored it yet").isEmpty();

        FileDetailsDTO stored = fileService.createNewFile(uploadRequest("orphan.txt", tag.getId()), principalId, 1);
        flushAndClear();

        FileInfo file = fileInfoRepository.findById(stored.getFileInfoId()).orElseThrow();
        assertThat(file.getFolder()).isNotNull();
        assertThat(file.getFolder().getSourceId()).isEqualTo(tag.getId());
        assertThat(file.getFolder().getParent().getSourceId()).as("the ancestry came with it").isEqualTo(subCategory.getId());
        assertThat(folderRepository.findRowsWhoseDerivedColumnsDisagree()).isEmpty();
        assertThat(fileInfoRepository.findRowsWhoseFolderDisagreesWithTheMirror()).isEmpty();
    }

    // ---------------------------------------------------------------- the backfill

    /**
     * The statement in {@code V2.3} itself, not a copy of it: the file is read, the {@code UPDATE}
     * is cut out of it and run against rows this test has deliberately un-linked - which is what a
     * row written before the column existed looks like. A copy would pass while the migration
     * drifted.
     */
    @Test
    @DisplayName("the migration's backfill statement links rows that predate the column")
    void theBackfillStatementInTheMigrationLinksOldRows() throws Exception {
        FileDetailsDTO first = fileService.createNewFile(uploadRequest("old-one.txt", mainTagId), principalId, 1);
        FileDetailsDTO second = fileService.createNewFile(uploadRequest("old-two.txt", mainTagId), principalId, 1);
        flushAndClear();

        jdbcTemplate.update("UPDATE file_info SET folder_id = NULL WHERE id IN (?, ?)",
                first.getFileInfoId(), second.getFileInfoId());
        assertThat(fileInfoRepository.findRowsWhoseFolderDisagreesWithTheMirror())
                .extracting(FileInfo::getId)
                .as("the rows now look like pre-migration data")
                .contains(first.getFileInfoId(), second.getFileInfoId());
        entityManager.clear();

        jdbcTemplate.execute(backfillStatementFromTheMigration());
        entityManager.clear();

        assertThat(fileInfoRepository.findRowsWhoseFolderDisagreesWithTheMirror()).isEmpty();
        Folder mirror = folderRepository.findBySourceTypeAndSourceId(FolderSourceType.MAIN_TAG, mainTagId).orElseThrow();
        assertThat(fileInfoRepository.findById(first.getFileInfoId()).orElseThrow().getFolder().getId()).isEqualTo(mirror.getId());
        assertThat(fileInfoRepository.findById(second.getFileInfoId()).orElseThrow().getFolder().getId()).isEqualTo(mirror.getId());
    }

    // ---------------------------------------------------------------- the step-4 pre-flight

    /**
     * Step 4 puts a unique constraint over (folder, name). The rule is per sub-category today and
     * a tag folder is narrower than a sub-category, so the query is empty on data the services
     * wrote - and the test plants the one shape that would break the constraint (two files of one
     * name, in two sub-categories, one of them re-pointed at the other's folder) to prove the
     * query would report it.
     */
    @Test
    @DisplayName("the per-folder name pre-flight is empty on data the services wrote, and reports a planted collision")
    void thePerFolderNamePreflightReportsACollision() {
        FileDetailsDTO here = fileService.createNewFile(uploadRequest("same-name.txt", mainTagId), principalId, 1);
        int otherTagId = anotherTagInAnotherSubCategory();
        FileDetailsDTO there = fileService.createNewFile(uploadRequest("same-name.txt", otherTagId), principalId, 1);
        flushAndClear();

        assertThat(fileInfoRepository.findFileNamesSharedWithinAFolder())
                .as("two sub-categories, two folders: no collision")
                .isEmpty();

        Folder hereFolder = fileInfoRepository.findById(here.getFileInfoId()).orElseThrow().getFolder();
        jdbcTemplate.update("UPDATE file_info SET folder_id = ? WHERE id = ?", hereFolder.getId(), there.getFileInfoId());
        entityManager.clear();

        assertThat(fileInfoRepository.findFileNamesSharedWithinAFolder())
                .as("the planted collision, as (folder id, name, count)")
                .singleElement()
                .satisfies(row -> {
                    assertThat(row[0]).isEqualTo(hereFolder.getId());
                    assertThat(row[1]).isEqualTo("same-name");
                    assertThat(((Number) row[2]).intValue()).isEqualTo(2);
                });
    }

    /** The start-up figures are these same queries; ready on data the services wrote, not once a row is un-linked. */
    @Test
    @DisplayName("the start-up readiness report is all zeros on data the services wrote, and counts a row that is not")
    void theReadinessReportCountsWhatTheQueriesFind() {
        FileDetailsDTO file = fileService.createNewFile(uploadRequest("counted.txt", mainTagId), principalId, 1);
        flushAndClear();

        assertThat(folderReadinessReport.figures().ready()).isTrue();

        jdbcTemplate.update("UPDATE file_info SET folder_id = NULL WHERE id = ?", file.getFileInfoId());
        entityManager.clear();

        FolderReadinessReport.Figures figures = folderReadinessReport.figures();
        assertThat(figures.ready()).isFalse();
        assertThat(figures.filesWithoutFolder()).isEqualTo(1);
        assertThat(figures.filesWhoseFolderDisagrees()).isEqualTo(1);
        assertThat(figures.namesSharedWithinAFolder()).isZero();
    }

    // ---------------------------------------------------------------- what the key enforces

    /**
     * The taxonomy services already refuse to delete a tag that has files. The foreign key now says
     * the same thing one layer down, so a folder with files in it cannot disappear through any
     * route - including a direct repository delete that bypasses those services.
     */
    @Test
    @DisplayName("a folder that still has files in it cannot be deleted")
    void aFolderWithFilesCannotBeDeleted() {
        fileService.createNewFile(uploadRequest("keeper.txt", mainTagId), principalId, 1);
        flushAndClear();
        Folder mirror = folderRepository.findBySourceTypeAndSourceId(FolderSourceType.MAIN_TAG, mainTagId).orElseThrow();

        // Flushed through the repository so the violation arrives translated, as a caller sees it.
        assertThatThrownBy(() -> folderRepository.deleteAllInBatch(List.of(mirror)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("fk_file_info_folder");
    }

    // ---------------------------------------------------------------- helpers

    private FileInfoDTO uploadRequest(String fileName, int tagId) {
        MainTagFile tag = mainTagFileRepository.findById(tagId).orElseThrow();
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setMainTagFileId(tagId);
        request.setFileSubCategoryId(tag.getFileSubCategory().getId());
        request.setFileCategoryId(tag.getFileSubCategory().getFileCategory().getId());
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    /** A second sub-category under the same category, with one tag in it, through the services. */
    private int anotherTagInAnotherSubCategory() {
        FileSubCategoryDTO subCategory = new FileSubCategoryDTO();
        subCategory.setSubCategoryName("Sub" + TestData.nextSequence());
        subCategory.setSubCategoryNameDescription(subCategory.getSubCategoryName() + " label");
        subCategory.setDescription("another sub-category");
        subCategory.setFileCategoryId(categoryId);
        fileSubCategoryService.createFileSubCategory(subCategory, principalId);
        int otherSubCategoryId = fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getSubCategoryName().equals(subCategory.getSubCategoryName())).findFirst().orElseThrow().getId();

        MainTagFileDTO tag = new MainTagFileDTO();
        tag.setTagName("Tag" + TestData.nextSequence());
        tag.setTagNameDescription(tag.getTagName() + " label");
        tag.setDescription("a tag " + tag.getTagName());
        tag.setFileSubCategoryId(otherSubCategoryId);
        tag.setFileCategoryId(categoryId);
        tag.setType(0);
        mainTagFileService.createMainTagFile(tag, principalId);
        return mainTagFileRepository.findAll().stream()
                .filter(t -> t.getTagName().equals(tag.getTagName())).findFirst().orElseThrow().getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static String backfillStatementFromTheMigration() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V2.3__Add_Folder_To_File_Info.sql"));
        Matcher update = Pattern.compile("(?ms)^UPDATE file_info.*?;").matcher(sql);
        assertThat(update.find()).as("the migration contains the backfill UPDATE").isTrue();
        return update.group().replaceAll(";$", "");
    }
}
