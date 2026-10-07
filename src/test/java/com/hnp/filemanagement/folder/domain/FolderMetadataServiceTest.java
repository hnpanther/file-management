package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A folder's metadata (roadmap 12.3): set, conditioned, cleared and recorded; refused on the root,
 * {@code Profiles} and another person's home, and without access; and the queue of folders still to
 * be described, newest first, as far as the reader sees.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FolderMetadataServiceTest extends DatabaseSupport {

    @Autowired
    private FolderMetadataService underTest;
    @Autowired
    private UserHomeService userHomeService;
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

    private User admin;
    private int adminId;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
    }

    @Test
    @DisplayName("set, read, replaced and cleared - each change recorded with both documents, the folder's title and who")
    void setAndRecorded() {
        int folder = chain.subCategoryId();
        FolderMetadataService.Described set = underTest.replace(folder, "{\"fullName\":\"علی رضایی\",\"nationalCode\":\"0012345678\"}",
                MetadataPrecondition.NONE, adminId);
        flushAndClear();
        assertThat(set.document()).isPresent();
        assertThat(underTest.of(folder, adminId).tree().get("fullName").stringValue()).isEqualTo("علی رضایی");
        assertThat(underTest.of(folder, adminId).etag()).isEqualTo(set.etag());

        underTest.replace(folder, "{\"fullName\":\"علی رضایی\"}", MetadataPrecondition.NONE, adminId);
        underTest.replace(folder, "{}", MetadataPrecondition.NONE, adminId);
        flushAndClear();
        assertThat(underTest.of(folder, adminId).document()).isEmpty();

        List<Map<String, Object>> changes = jdbc.queryForList("""
                SELECT metadata_before::text AS b, metadata_after::text AS a, folder_title, user_id
                FROM folder_metadata_change WHERE folder_id = ? ORDER BY id""", folder);
        assertThat(changes).hasSize(3);
        assertThat(changes.get(0).get("b")).isNull();
        assertThat(changes.get(1).get("b")).isEqualTo(changes.get(0).get("a"));
        assertThat(changes.get(2).get("a")).isNull();
        assertThat(changes).allSatisfy(c -> {
            assertThat((String) c.get("folder_title")).contains(chain.subCategory().getName());
            assertThat(c.get("user_id")).isEqualTo(adminId);
        });
        assertThat(underTest.changesOf(folder, 0, 10, adminId).getContent()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_history WHERE entity_id = ? AND action_description = 'CHANGE FOLDER METADATA'",
                Integer.class, folder)).isEqualTo(3);

        int before = count();
        underTest.replace(folder, "{}", MetadataPrecondition.NONE, adminId);
        assertThat(count()).as("clearing what is clear records nothing").isEqualTo(before);
    }

    @Test
    @DisplayName("conditioned as a file's: If-None-Match: * only where none; If-Match only on the document read")
    void conditions() {
        int folder = chain.tagId();
        underTest.replace(folder, "{\"a\":1}", new MetadataPrecondition(null, "*"), adminId);
        flushAndClear();
        assertThatThrownBy(() -> underTest.replace(folder, "{\"a\":2}", new MetadataPrecondition(null, "*"), adminId))
                .isInstanceOf(PreconditionFailedException.class);
        assertThatThrownBy(() -> underTest.replace(folder, "{\"a\":2}", new MetadataPrecondition("\"stale\"", null), adminId))
                .isInstanceOf(PreconditionFailedException.class);
        String read = underTest.of(folder, adminId).etag();
        underTest.replace(folder, "{\"a\":2}", new MetadataPrecondition(read, null), adminId);
        flushAndClear();
        assertThat(stored(folder)).isEqualTo("{\"a\": 2}");
    }

    @Test
    @DisplayName("the root and Profiles take none; a home only from its user or an administrator")
    void kinds() {
        int root = jdbc.queryForObject("SELECT id FROM folder WHERE parent_id IS NULL", Integer.class);
        assertThatThrownBy(() -> underTest.replace(root, "{\"a\":1}", MetadataPrecondition.NONE, adminId))
                .isInstanceOf(InvalidDataException.class);
        int profiles = userHomeService.profiles().getId();
        assertThatThrownBy(() -> underTest.replace(profiles, "{\"a\":1}", MetadataPrecondition.NONE, adminId))
                .isInstanceOf(InvalidDataException.class);

        User owner = userRepository.save(TestData.user());
        Folder home = userHomeService.ensureHome(owner.getId(), adminId);
        User other = userRepository.save(TestData.user());
        grant(other, profiles, FolderPermission.WRITE);
        flushAndClear();

        assertThatThrownBy(() -> underTest.replace(home.getId(), "{\"a\":1}", MetadataPrecondition.NONE, other.getId()))
                .as("WRITE over every home is not enough").isInstanceOf(AccessDeniedException.class);
        assertThat(underTest.mayWrite(home.getId(), other.getId())).isFalse();
        underTest.replace(home.getId(), "{\"room\":\"204\"}", MetadataPrecondition.NONE, owner.getId());
        assertThat(underTest.mayWrite(home.getId(), owner.getId())).isTrue();
        underTest.replace(home.getId(), "{\"room\":\"205\"}", MetadataPrecondition.NONE, adminId);
        flushAndClear();
        assertThat(stored(home.getId())).isEqualTo("{\"room\": \"205\"}");
    }

    @Test
    @DisplayName("reading asks READ, writing WRITE")
    void access() {
        int folder = chain.tagId();
        underTest.replace(folder, "{\"a\":1}", MetadataPrecondition.NONE, adminId);
        User reader = userRepository.save(TestData.user());
        flushAndClear();
        assertThatThrownBy(() -> underTest.of(folder, reader.getId())).isInstanceOf(AccessDeniedException.class);
        grant(reader, folder, FolderPermission.READ);
        assertThat(underTest.of(folder, reader.getId()).document()).isPresent();
        assertThatThrownBy(() -> underTest.replace(folder, "{\"a\":2}", MetadataPrecondition.NONE, reader.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(underTest.mayWrite(folder, reader.getId())).isFalse();
        assertThat(stored(folder)).isEqualTo("{\"a\": 1}");
    }

    @Test
    @DisplayName("the queue: a folder's children without metadata, newest first, paged; described, one leaves it; a reader sees only the way to a grant")
    void theQueue() {
        Folder erp = chain.subCategory();
        List<Integer> persons = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Folder person = FolderFixture.tag(folderRepository, erp, admin, "P-" + i + "-" + TestData.nextSequence());
            persons.add(person.getId());
        }
        // Their creation times one minute apart, the last made the newest.
        flushAndClear();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < persons.size(); i++) {
            jdbc.update("UPDATE folder SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(base.plusSeconds(60L * i)), persons.get(i));
        }
        jdbc.update("UPDATE folder SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(base.minusSeconds(3600)), chain.tagId());

        List<Integer> expected = new ArrayList<>(List.of(persons.get(4), persons.get(3), persons.get(2), persons.get(1), persons.get(0), chain.tagId()));
        assertThat(queue(erp.getId(), adminId, 4)).containsExactlyElementsOf(expected);

        underTest.replace(persons.get(3), "{\"fullName\":\"x\"}", MetadataPrecondition.NONE, adminId);
        flushAndClear();
        expected.remove(persons.get(3));
        assertThat(queue(erp.getId(), adminId, 2)).containsExactlyElementsOf(expected);

        User reader = userRepository.save(TestData.user());
        grant(reader, persons.get(1), FolderPermission.READ);
        assertThat(queue(erp.getId(), reader.getId(), 10)).as("only the way to the grant").containsExactly(persons.get(1));
        assertThatThrownBy(() -> underTest.undescribedUnder(chain.categoryId(), 0, 10, userRepository.save(TestData.user()).getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ================================================================ helpers

    /** Every page of the queue, joined. */
    private List<Integer> queue(int folderId, int principalId, int size) {
        List<Integer> ids = new ArrayList<>();
        for (int page = 0; ; page++) {
            var slice = underTest.undescribedUnder(folderId, page, size, principalId);
            slice.getContent().forEach(each -> ids.add(each.id()));
            if (!slice.hasNext()) {
                return ids;
            }
        }
    }

    private String stored(int folderId) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT metadata::text FROM folder WHERE id = ?", String.class, folderId);
    }

    private int count() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT count(*) FROM folder_metadata_change", Integer.class);
    }

    private void grant(User user, int folder, FolderPermission permission) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(loaded.getFolderGrants());
        grants.add(new UserFolderGrant(loaded, folderRepository.findById(folder).orElseThrow(), permission));
        loaded.replaceFolderGrants(grants);
        userRepository.save(loaded);
        flushAndClear();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
