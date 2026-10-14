package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
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
 * The search's folder chooser (2.16.0): the folders a reader may walk into, found in the statement and
 * not filtered after it - a reader's own folder among a hundred of the same word, all someone else's,
 * is offered; the folder named exactly first; two siblings of one label told apart by their names. And
 * measured among 20,000 personal folders.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class FolderChooserTest extends DatabaseSupport {

    @Autowired
    private ContentSearchService underTest;
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
        tag = "t" + TestData.nextSequence();
    }

    @Test
    @DisplayName("a reader's own folder among a hundred of the same word, all someone else's and nearer the top, is offered")
    void accessInTheStatement() {
        Folder others = FolderFixture.tag(folderRepository, top, admin, "دیگران" + tag);
        for (int i = 0; i < 100; i++) {
            FolderFixture.tag(folderRepository, top, admin, "پرونده" + tag + "-" + i);
        }
        Folder deeper = FolderFixture.tag(folderRepository, others, admin, "بخش" + tag);
        Folder mine = FolderFixture.tag(folderRepository, deeper, admin, "پرونده" + tag + "-من");
        User reader = reader(mine);

        List<ContentSearchService.FolderChoice> offered = underTest.folderChoices("پرونده" + tag, reader.getId());
        assertThat(offered).extracting(ContentSearchService.FolderChoice::id).containsExactly(mine.getId());
        assertThat(underTest.folderChoices("پرونده" + tag, admin.getId())).hasSize(ContentSearchService.FOLDER_CHOICES);
        assertThat(underTest.folderChoices("بخش" + tag, reader.getId())).as("a folder on the way to the reader's")
                .extracting(ContentSearchService.FolderChoice::id).containsExactly(deeper.getId());
        assertThat(underTest.folderChoices("دیگران" + tag, reader.getId())).extracting(ContentSearchService.FolderChoice::id)
                .containsExactly(others.getId());
        User stranger = userRepository.save(TestData.user());
        flush();
        assertThat(underTest.folderChoices("پرونده" + tag, stranger.getId())).isEmpty();
    }

    @Test
    @DisplayName("the folder named exactly first - before a shallower one that only begins so; a number typed, that folder first")
    void exactFirst() {
        Folder longer = FolderFixture.tag(folderRepository, top, admin, "قرارداد" + tag + "قدیمی");
        Folder middle = FolderFixture.tag(folderRepository, top, admin, "واحد" + tag);
        Folder exact = FolderFixture.tag(folderRepository, middle, admin, "قرارداد" + tag);
        exact.setDisplayName("قرارداد" + tag);
        folderRepository.save(exact);
        flush();

        assertThat(underTest.folderChoices("قرارداد" + tag, admin.getId())).extracting(ContentSearchService.FolderChoice::id)
                .containsExactly(exact.getId(), longer.getId());
        assertThat(underTest.folderChoices(String.valueOf(middle.getId()), admin.getId())).extracting(ContentSearchService.FolderChoice::id)
                .first().isEqualTo(middle.getId());
    }

    @Test
    @DisplayName("two siblings of one label - two people of one name - each offered with its name after its trail; the chosen one too")
    void oneLabelTwoFolders() {
        Folder first = FolderFixture.tag(folderRepository, top, admin, "amohammadi" + tag);
        Folder second = FolderFixture.tag(folderRepository, top, admin, "amohammadi2" + tag);
        Folder alone = FolderFixture.tag(folderRepository, top, admin, "rezaei" + tag);
        for (Folder folder : List.of(first, second)) {
            folder.setDisplayName("علی محمدی " + tag);
            folderRepository.save(folder);
        }
        alone.setDisplayName("رضا رضایی " + tag);
        folderRepository.save(alone);
        flush();

        List<ContentSearchService.FolderChoice> offered = underTest.folderChoices("علی محمدی " + tag, admin.getId());
        assertThat(offered).extracting(ContentSearchService.FolderChoice::trail).hasSize(2).doesNotHaveDuplicates()
                .allSatisfy(label -> assertThat(label).contains("علی محمدی " + tag))
                .anySatisfy(label -> assertThat(label).endsWith("(amohammadi" + tag + ")"))
                .anySatisfy(label -> assertThat(label).endsWith("(amohammadi2" + tag + ")"));
        assertThat(underTest.folderChoice(second.getId(), admin.getId())).hasValueSatisfying(choice ->
                assertThat(choice.trail()).endsWith("(amohammadi2" + tag + ")"));
        assertThat(underTest.folderChoices("رضا رضایی " + tag, admin.getId())).singleElement().satisfies(choice ->
                assertThat(choice.trail()).endsWith("رضا رضایی " + tag).doesNotContain("(rezaei"));
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
        List<ContentSearchService.FolderChoice> readers = underTest.folderChoices("شخص " + tag, reader.getId());
        long readerMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(readers).extracting(ContentSearchService.FolderChoice::id).containsExactlyInAnyOrderElementsOf(granted);

        started = System.nanoTime();
        List<ContentSearchService.FolderChoice> admins = underTest.folderChoices("شخص " + tag + " 12345", admin.getId());
        long adminMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(admins).extracting(ContentSearchService.FolderChoice::trail).first().asString().endsWith("شخص " + tag + " 12345");
        started = System.nanoTime();
        assertThat(underTest.folderChoices("شخص " + tag, admin.getId())).hasSize(ContentSearchService.FOLDER_CHOICES);
        long broadMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[chooser] 20,000 folders: a reader of three %d ms, one named %d ms, a word on all %d ms%n",
                readerMs, adminMs, broadMs);
        assertThat(Math.max(readerMs, Math.max(adminMs, broadMs))).isLessThan(2_000);
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
