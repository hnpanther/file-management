package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The explorer's search for folders (issue 123): the folders a reader may walk into found in the
 * statement, not a page of everyone's filtered after it - a reader's own folder among a hundred of the
 * same word, all someone else's and nearer the top, is listed; the folder typed by number first, the one
 * named exactly next; a scope still confines it; each hit with its trail and counts. And measured among
 * 20,000 personal folders.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class ExplorerFolderSearchTest extends DatabaseSupport {

    @Autowired
    private FolderContentService underTest;
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
    private Folder top;
    private String tag;

    @BeforeEach
    void setUp() {
        admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        top = FolderFixture.chain(folderRepository, tagGroupRepository, admin).category();
        tag = "e" + TestData.nextSequence();
    }

    @Test
    @DisplayName("a reader's own folder among a hundred of the same word, all someone else's and nearer the top, is listed - and nobody else's")
    void accessInTheStatement() {
        Folder others = FolderFixture.tag(folderRepository, top, admin, "دیگران" + tag);
        for (int i = 0; i < 100; i++) {
            FolderFixture.tag(folderRepository, top, admin, "پرونده" + tag + "-" + i);
        }
        Folder deeper = FolderFixture.tag(folderRepository, others, admin, "بخش" + tag);
        Folder mine = FolderFixture.tag(folderRepository, deeper, admin, "پرونده" + tag + "-من");
        Folder inside = FolderFixture.tag(folderRepository, mine, admin, "پرونده" + tag + "-داخل");
        User reader = reader(mine);

        assertThat(folderIds("پرونده" + tag, null, reader))
                .as("the reader's folder and the one inside it; none of the hundred above")
                .containsExactly(mine.getId(), inside.getId());
        assertThat(folderIds("پرونده" + tag, null, admin)).as("an administrator sees the first twenty of all")
                .hasSize(FolderSearchDTO.MAX_FOLDER_HITS);
        assertThat(folderIds("بخش" + tag, null, reader)).as("a folder on the way to the reader's")
                .containsExactly(deeper.getId());
        assertThat(folderIds("دیگران" + tag, null, reader)).containsExactly(others.getId());
        assertThat(folderIds("پرونده" + tag + "-1", null, reader)).as("someone else's, named exactly").isEmpty();

        User stranger = userRepository.save(TestData.user());
        flush();
        assertThat(folderIds("پرونده" + tag, null, stranger)).as("no grant at all").isEmpty();
        assertThat(folderIds(String.valueOf(mine.getId()), null, stranger)).as("nor a folder by its number").isEmpty();
        assertThat(folderIds(String.valueOf(mine.getId()), null, reader)).first().isEqualTo(mine.getId());
    }

    @Test
    @DisplayName("the folder typed by number first, then the one named exactly - before a shallower one that only begins so")
    void exactFirst() {
        Folder longer = FolderFixture.tag(folderRepository, top, admin, "قرارداد" + tag + "قدیمی");
        Folder middle = FolderFixture.tag(folderRepository, top, admin, "واحد" + tag);
        Folder exact = FolderFixture.tag(folderRepository, middle, admin, "قرارداد" + tag);
        flush();

        assertThat(folderIds("قرارداد" + tag, null, admin)).containsExactly(exact.getId(), longer.getId());
        assertThat(folderIds(String.valueOf(middle.getId()), null, admin)).first().isEqualTo(middle.getId());
    }

    @Test
    @DisplayName("a scope confines the folders a reader finds; each hit keeps its trail, its counts and its tag group")
    void scopeTrailAndCounts() {
        Folder first = FolderFixture.tag(folderRepository, top, admin, "واحد" + tag + "-الف");
        Folder second = FolderFixture.tag(folderRepository, top, admin, "واحد" + tag + "-ب");
        Folder inFirst = FolderFixture.tag(folderRepository, first, admin, "سند" + tag);
        Folder inSecond = FolderFixture.tag(folderRepository, second, admin, "سند" + tag);
        FolderFixture.tag(folderRepository, inFirst, admin, "زیر" + tag);
        User reader = reader(first, second);

        assertThat(folderIds("سند" + tag, null, reader)).containsExactlyInAnyOrder(inFirst.getId(), inSecond.getId());
        FolderSearchDTO scoped = underTest.search("سند" + tag, first.getId(), 0, 25, reader.getId());
        assertThat(scoped.folders()).singleElement().satisfies(hit -> {
            assertThat(hit.folder().id()).isEqualTo(inFirst.getId());
            assertThat(hit.folder().folderCount()).isEqualTo(1);
            assertThat(hit.breadcrumb()).extracting(FolderContentDTO.FolderRef::id).endsWith(top.getId(), first.getId());
        });
        assertThat(underTest.search(top.getDisplayName(), null, 0, 25, admin.getId()).folders())
                .as("a top-level folder still carries its tag group's title")
                .filteredOn(hit -> hit.folder().id() == top.getId())
                .singleElement().satisfies(hit -> assertThat(hit.folder().note()).isNotBlank());
    }

    @Test
    @DisplayName("among 20,000 personal folders: a reader of three finds theirs, an administrator the one named, both within bounds")
    void amongTwentyThousand() {
        Folder profiles = FolderFixture.tag(folderRepository, top, admin, "پروفایل‌ها" + tag);
        flush();
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path,
                                    depth, kind, enabled, state, created_at)
                SELECT n.id, ?, 'P' || ? || '-' || n.g, upper('P' || ? || '-' || n.g), 'شخص ' || ? || ' ' || n.g,
                       upper('شخص ' || ? || ' ' || n.g), ? || n.id || '/', ? || 'P' || ? || '-' || n.g || '/', ?, 'FOLDER', 1, 0, now()
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, 20000) g) n""",
                profiles.getId(), tag, tag, tag, tag, profiles.getPath(), profiles.getKeyPath(), tag, profiles.getDepth() + 1);
        jdbc.execute("ANALYZE folder");
        List<Integer> granted = jdbc.queryForList("SELECT id FROM folder WHERE parent_id = ? AND name IN (?, ?, ?) ORDER BY id",
                Integer.class, profiles.getId(), "P" + tag + "-17000", "P" + tag + "-18000", "P" + tag + "-19999");
        User reader = reader(folderRepository.findAllById(granted).toArray(Folder[]::new));

        long started = System.nanoTime();
        List<Integer> readers = folderIds("شخص " + tag, null, reader);
        long readerMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(readers).containsExactlyInAnyOrderElementsOf(granted);

        started = System.nanoTime();
        List<Integer> named = folderIds("شخص " + tag + " 12345", null, admin);
        long namedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(named).first().isEqualTo(jdbc.queryForObject("SELECT id FROM folder WHERE parent_id = ? AND name = ?",
                Integer.class, profiles.getId(), "P" + tag + "-12345"));

        started = System.nanoTime();
        assertThat(folderIds("شخص " + tag, null, admin)).hasSize(FolderSearchDTO.MAX_FOLDER_HITS);
        long broadMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[explorer] 20,000 folders: a reader of three %d ms, one named %d ms, a word on all %d ms%n",
                readerMs, namedMs, broadMs);
        assertThat(Math.max(readerMs, Math.max(namedMs, broadMs))).isLessThan(2_000);
    }

    private List<Integer> folderIds(String query, Integer scope, User who) {
        return underTest.search(query, scope, 0, 25, who.getId()).folders().stream().map(hit -> hit.folder().id()).toList();
    }

    private User reader(Folder... granted) {
        User reader = userRepository.save(TestData.user());
        List<UserFolderGrant> grants = new ArrayList<>();
        for (Folder folder : granted) {
            grants.add(new UserFolderGrant(reader, folder, FolderPermission.READ));
        }
        reader.replaceFolderGrants(grants);
        userRepository.save(reader);
        flush();
        return reader;
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
