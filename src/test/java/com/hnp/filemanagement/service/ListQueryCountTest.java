package com.hnp.filemanagement.service;

import com.hnp.filemanagement.config.bootstrap.DataInitializer;
import com.hnp.filemanagement.dto.ApiKeyDTO;
import com.hnp.filemanagement.dto.UserDTO;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.Tag;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.TagRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list pages cost a fixed number of statements, whatever the number of rows: every association
 * is lazy and {@code open-in-view} is off, so a conversion that follows one per row is a query per
 * row, and nothing else would notice. Each of these used to grow with the list.
 */
@ServiceIntegrationTest
class ListQueryCountTest extends MySqlSupport {

    private static final int ROWS = 4;

    @Autowired
    private UserService userService;
    @Autowired
    private TagGroupService tagGroupService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private DataInitializer dataInitializer;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private EntityManager entityManager;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        dataInitializer.initialize();
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void tearDown() {
        statistics.setStatisticsEnabled(false);
    }

    @Test
    @DisplayName("the user list is the page and its count - no roles, no permissions per row")
    void theUserList() {
        Role user = roleRepository.findByRoleNameIgnoreCase("USER").orElseThrow();
        Role admin = roleRepository.findByRoleNameIgnoreCase("ADMIN").orElseThrow();
        for (int i = 0; i < ROWS; i++) {
            User account = TestData.user();
            account.getRoles().add(user);
            account.getRoles().add(admin);
            userRepository.save(account);
        }

        // A full page, so that Spring Data runs the count as well: a page it can see is the last
        // one it counts from the rows, and the number would depend on what else is in the table.
        Page<UserDTO> page = statements(2, () -> userService.getUserPage("", ROWS - 1, 0));

        assertThat(page.getContent()).hasSize(ROWS - 1);
    }

    @Test
    @DisplayName("the tag groups list is three queries, and each row counts its own folders and tags")
    void theTagGroupList() {
        User creator = userRepository.save(TestData.user());
        TagGroup counted = null;
        for (int i = 0; i < ROWS; i++) {
            counted = tagGroupRepository.save(TestData.tagGroup(creator, "qc" + TestData.nextSequence()));
        }
        FolderFixture.category(folderRepository, creator, "Qc" + TestData.nextSequence(), counted);
        tagRepository.save(tag(counted, "first"));
        tagRepository.save(tag(counted, "second"));
        int countedId = counted.getId();

        List<TagGroupService.TagGroupRow> rows = statements(3, () -> tagGroupService.rows());

        TagGroupService.TagGroupRow row = rows.stream().filter(r -> r.id() == countedId).findFirst().orElseThrow();
        assertThat(row.folders()).isEqualTo(1);
        assertThat(row.tags()).isEqualTo(2);
        assertThat(row.deletable()).isFalse();
        assertThat(rows).filteredOn(r -> r.id() != countedId && r.name().startsWith("qc"))
                .hasSize(ROWS - 1)
                .allMatch(TagGroupService.TagGroupRow::deletable);
    }

    @Test
    @DisplayName("the API key list is one query, the creators included")
    void theApiKeyList() {
        for (int i = 0; i < ROWS; i++) {
            int creator = userRepository.save(TestData.user()).getId();
            ApiKeyDTO request = new ApiKeyDTO();
            request.setTitle("key " + i);
            apiKeyService.create(request, creator);
        }

        List<ApiKeyDTO> keys = statements(1, () -> apiKeyService.getAll());

        assertThat(keys).hasSizeGreaterThanOrEqualTo(ROWS).allMatch(key -> key.getCreatedBy() != null);
    }

    /** Runs {@code call} on an empty persistence context and asserts how many statements it prepared. */
    private <T> T statements(int expected, Supplier<T> call) {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        T result = call.get();
        assertThat(statistics.getPrepareStatementCount()).as("statements prepared").isEqualTo(expected);
        return result;
    }

    private static Tag tag(TagGroup group, String name) {
        Tag tag = new Tag();
        tag.setGroup(group);
        tag.setName(name);
        tag.setTitle(name);
        tag.setEnabled(1);
        return tag;
    }
}
