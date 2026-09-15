package com.hnp.filemanagement.service;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Tag;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.TagRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A file's tags (roadmap 7.2 step 2, 7.3): derived from its taxonomy, written on every upload,
 * merged by name within a group, and read by nothing yet.
 *
 * <p>The rule under test is one sentence: <em>a file carries exactly the tags its taxonomy says
 * - its category, its sub-category and its main tag, in the group of its general tag - and two
 * of those that share a name are one tag.</em> Every test here is a case of that sentence, plus
 * the migration's own backfill statements run against files stripped of their tags.
 *
 * <p>Rolled back per test; MockMvc runs on the calling thread and joins the transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FileTagTest extends MySqlSupport {

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
    private TagRepository tagRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
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

    private int principalId;
    private String generalTagName;
    private int categoryId;
    private String categoryName;
    private int subCategoryId;
    private String subCategoryName;
    private int mainTagId;
    private String mainTagName;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();
        var generalTag = generalTagRepository.save(TestData.generalTag(creator, "gt" + TestData.nextSequence()));
        generalTagName = generalTag.getTagName();

        categoryName = "Cat" + TestData.nextSequence();
        categoryId = createCategory(categoryName, generalTag.getId());
        subCategoryName = "Sub" + TestData.nextSequence();
        subCategoryId = createSubCategory(subCategoryName, categoryId);
        mainTagName = "Tag" + TestData.nextSequence();
        mainTagId = createMainTag(mainTagName, categoryId, subCategoryId);
    }

    // ---------------------------------------------------------------- the rule

    @Test
    @DisplayName("an upload tags the file with its category, sub-category and main tag, in its general tag's group")
    void anUploadTagsTheFileWithItsThreeLevels() {
        FileDetailsDTO stored = fileService.createNewFile(uploadRequest("report.txt", mainTagId), principalId, 1);
        flushAndClear();

        FileInfo file = fileInfoRepository.findById(stored.getFileInfoId()).orElseThrow();

        assertThat(names(file.getTags())).containsExactlyInAnyOrder(categoryName, subCategoryName, mainTagName);
        assertThat(file.getTags()).allSatisfy(tag -> assertThat(tag.getGroup().getName()).isEqualTo(generalTagName));
        assertThat(file.getMainTagFile().getId()).as("the old key is still written").isEqualTo(mainTagId);
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheTaxonomy()).isEmpty();
    }

    /** The route integrations use: the same request, the same answer, and the tags written too. */
    @Test
    @DisplayName("the v1 API is unchanged and tags the file as well")
    void theV1ApiIsUnchangedAndTagsTheFile() throws Exception {
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
                .andExpect(jsonPath("$.fileName").value("policy.txt"))
                .andExpect(jsonPath("$.tags").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        flushAndClear();

        int fileId = com.jayway.jsonpath.JsonPath.read(body, "$.fileId");
        assertThat(names(fileInfoRepository.findById(fileId).orElseThrow().getTags()))
                .containsExactlyInAnyOrder(categoryName, subCategoryName, mainTagName);
    }

    // ---------------------------------------------------------------- names merge

    /**
     * Issue 73's shape: a main tag named exactly like the sub-category above it. As places they
     * are two nodes; as labels they are one, and the file carries it once.
     */
    @Test
    @DisplayName("a name shared by two of a file's levels is one tag, carried once, with the higher level's title")
    void aNameSharedByTwoLevelsIsOneTag() {
        String shared = "HSED" + TestData.nextSequence();
        int subCategory = createSubCategory(shared, categoryId);
        int mainTag = createMainTag(shared, categoryId, subCategory);

        FileDetailsDTO stored = fileService.createNewFile(uploadRequest("shared.txt", mainTag), principalId, 1);
        flushAndClear();

        FileInfo file = fileInfoRepository.findById(stored.getFileInfoId()).orElseThrow();
        assertThat(names(file.getTags())).containsExactlyInAnyOrder(categoryName, shared);
        assertThat(file.getTags()).filteredOn(tag -> tag.getName().equals(shared))
                .singleElement()
                .satisfies(tag -> assertThat(tag.getTitle())
                        .as("the sub-category claimed the name first")
                        .isEqualTo(shared + " label (sub-category)"));
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheTaxonomy()).isEmpty();
    }

    @Test
    @DisplayName("the same main-tag name in two branches is one tag, shared by the files of both")
    void twoBranchesWithTheSameNameShareTheTag() {
        String shared = "Twin" + TestData.nextSequence();
        int otherSubCategory = createSubCategory("Sub" + TestData.nextSequence(), categoryId);
        int tagInFirstBranch = createMainTag(shared, categoryId, subCategoryId);
        int tagInSecondBranch = createMainTag(shared, categoryId, otherSubCategory);

        FileDetailsDTO first = fileService.createNewFile(uploadRequest("a.txt", tagInFirstBranch), principalId, 1);
        FileDetailsDTO second = fileService.createNewFile(uploadRequest("b.txt", tagInSecondBranch), principalId, 1);
        flushAndClear();

        Tag ofFirst = tagNamed(fileInfoRepository.findById(first.getFileInfoId()).orElseThrow(), shared);
        Tag ofSecond = tagNamed(fileInfoRepository.findById(second.getFileInfoId()).orElseThrow(), shared);
        assertThat(ofFirst.getId()).isEqualTo(ofSecond.getId());
        assertThat(tagRepository.findAll()).filteredOn(tag -> tag.getName().equals(shared)).hasSize(1);
    }

    // ---------------------------------------------------------------- the inputs change

    /**
     * The one level whose name can change. The files under it follow; the tag with the old name
     * stays, because a label nothing carries is not a fault and another branch may still use it.
     */
    @Test
    @DisplayName("renaming a main tag re-derives the tags of every file under it")
    void renamingAMainTagRetagsItsFiles() {
        FileDetailsDTO stored = fileService.createNewFile(uploadRequest("renamed.txt", mainTagId), principalId, 1);
        String newName = "Renamed" + TestData.nextSequence();

        MainTagFile tag = mainTagFileRepository.findById(mainTagId).orElseThrow();
        MainTagFileDTO request = new MainTagFileDTO();
        request.setId(mainTagId);
        request.setTagName(newName);
        request.setTagNameDescription(tag.getTagNameDescription());
        request.setDescription(tag.getDescription());
        request.setFileSubCategoryId(subCategoryId);
        request.setFileCategoryId(categoryId);
        request.setType(0);
        mainTagFileService.updateMainTagFile(request, principalId);
        flushAndClear();

        FileInfo file = fileInfoRepository.findById(stored.getFileInfoId()).orElseThrow();
        assertThat(names(file.getTags())).containsExactlyInAnyOrder(categoryName, subCategoryName, newName);
        assertThat(tagRepository.findAll()).extracting(Tag::getName).as("the old label is left in place").contains(mainTagName);
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheTaxonomy()).isEmpty();
    }

    // ---------------------------------------------------------------- the backfill

    /**
     * The three INSERT statements of {@code V2.4} themselves, cut out of the file: run after this
     * test has removed the files' tags, every tag of the group and the group itself, which is what
     * a database from before the migration looks like for this general tag. The statements skip
     * what exists, so the rest of the database - other tests' groups - is untouched.
     */
    @Test
    @DisplayName("the migration's backfill statements produce the same tags the upload writes")
    void theBackfillStatementsInTheMigrationTagOldRows() throws Exception {
        FileDetailsDTO stored = fileService.createNewFile(uploadRequest("old.txt", mainTagId), principalId, 1);
        flushAndClear();
        int groupId = tagGroupRepository.findByName(generalTagName).orElseThrow().getId();

        jdbcTemplate.update("DELETE ft FROM file_tag ft JOIN tag t ON t.id = ft.tag_id WHERE t.group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM tag WHERE group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM tag_group WHERE id = ?", groupId);
        entityManager.clear();
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheTaxonomy())
                .as("the rows now look like pre-migration data")
                .contains(stored.getFileInfoId());

        for (String statement : backfillStatementsFromTheMigration()) {
            jdbcTemplate.execute(statement);
        }
        entityManager.clear();

        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheTaxonomy()).isEmpty();
        FileInfo file = fileInfoRepository.findById(stored.getFileInfoId()).orElseThrow();
        assertThat(names(file.getTags())).containsExactlyInAnyOrder(categoryName, subCategoryName, mainTagName);
        assertThat(file.getTags()).allSatisfy(tag -> assertThat(tag.getGroup().getName()).isEqualTo(generalTagName));

        // Re-runnable: a second pass changes nothing and violates nothing.
        for (String statement : backfillStatementsFromTheMigration()) {
            jdbcTemplate.execute(statement);
        }
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheTaxonomy()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private int createCategory(String name, int generalTagId) {
        FileCategoryDTO category = new FileCategoryDTO();
        category.setCategoryName(name);
        category.setCategoryNameDescription(name + " label (category)");
        category.setDescription("a category " + name);
        category.setGeneralTagId(generalTagId);
        fileCategoryService.createCategory(category, principalId);
        return fileCategoryRepository.findAll().stream()
                .filter(c -> c.getCategoryName().equals(name)).findFirst().orElseThrow().getId();
    }

    private int createSubCategory(String name, int categoryId) {
        FileSubCategoryDTO subCategory = new FileSubCategoryDTO();
        subCategory.setSubCategoryName(name);
        subCategory.setSubCategoryNameDescription(name + " label (sub-category)");
        subCategory.setDescription("a sub-category " + name);
        subCategory.setFileCategoryId(categoryId);
        fileSubCategoryService.createFileSubCategory(subCategory, principalId);
        return fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getSubCategoryName().equals(name) && sc.getFileCategory().getId().equals(categoryId))
                .findFirst().orElseThrow().getId();
    }

    private int createMainTag(String name, int categoryId, int subCategoryId) {
        MainTagFileDTO tag = new MainTagFileDTO();
        tag.setTagName(name);
        tag.setTagNameDescription(name + " label (main tag)");
        tag.setDescription("a tag " + name + " " + TestData.nextSequence());
        tag.setFileSubCategoryId(subCategoryId);
        tag.setFileCategoryId(categoryId);
        tag.setType(0);
        mainTagFileService.createMainTagFile(tag, principalId);
        return mainTagFileRepository.findAll().stream()
                .filter(t -> t.getTagName().equals(name) && t.getFileSubCategory().getId().equals(subCategoryId))
                .findFirst().orElseThrow().getId();
    }

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

    private static Set<String> names(Set<Tag> tags) {
        return tags.stream().map(Tag::getName).collect(Collectors.toSet());
    }

    private static Tag tagNamed(FileInfo file, String name) {
        return file.getTags().stream().filter(tag -> tag.getName().equals(name)).findFirst().orElseThrow();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** The INSERT statements of the migration, in order, without their trailing semicolons. */
    private static List<String> backfillStatementsFromTheMigration() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V2.4__Add_Tags.sql"));
        // Comment lines out first, so an INSERT quoted in the commentary cannot be mistaken for one.
        String code = sql.lines().filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n"));
        Matcher insert = Pattern.compile("(?ms)^INSERT INTO .*?;").matcher(code);
        java.util.ArrayList<String> statements = new java.util.ArrayList<>();
        while (insert.find()) {
            statements.add(insert.group().replaceAll(";$", ""));
        }
        assertThat(statements).as("the migration holds the three backfill statements").hasSize(3);
        return statements;
    }
}
