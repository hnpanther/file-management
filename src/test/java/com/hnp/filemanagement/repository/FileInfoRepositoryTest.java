package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FileInfoRepository} against a real MySQL, at the persistence layer only.
 *
 * <p>These are the tests the service layer cannot give: whether a fetch plan actually resolved the
 * associations, whether a bulk update reached the database, whether a cascade removed what it
 * should, and whether the schema enforces the rules the services check in Java.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FileInfoRepositoryTest extends DatabaseSupport {

    @Autowired
    private FileInfoRepository underTest;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private EntityManager entityManager;

    private User creator;
    private FolderFixture.Chain chain;
    private int fileInfoId;

    @BeforeEach
    void setUp() {
        creator = userRepository.save(TestData.user());
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, creator);

        FileInfo fileInfo = TestData.fileInfo(creator, chain.tag(), "report" + TestData.nextSequence());
        TestData.fileDetails(creator, fileInfo, 1, "txt");
        TestData.fileDetails(creator, fileInfo, 2, "txt");
        fileInfoId = underTest.save(fileInfo).getId();

        flushAndClear();
    }

    // ---------------------------------------------------------------- fetch plans

    @Test
    @DisplayName("the file lookup fetches its revisions and its folder in one query")
    void resolvesTheFolder() {
        FileInfo fileInfo = underTest.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();

        // What the converter walks has to be initialised, or rendering the page would issue a
        // query per row - the N+1 that the lazy mapping makes visible instead of hiding. The
        // folders above are loaded for the whole page at once from the path (FolderService).
        assertThat(Hibernate.isInitialized(fileInfo.getFileDetailsList())).isTrue();
        assertThat(Hibernate.isInitialized(fileInfo.getFolder())).isTrue();
        assertThat(fileInfo.getFolder().getPath()).endsWith("/" + chain.tagId() + "/");
    }

    @Test
    @DisplayName("createdBy stays lazy - an audit column is not worth a join on every row")
    void leavesTheAuditUserLazy() {
        FileInfo fileInfo = underTest.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();

        assertThat(Hibernate.isInitialized(fileInfo.getCreatedBy())).isFalse();
    }

    @Test
    @DisplayName("a file with no versions is still found - the fetch is a LEFT join")
    void findsAFileWithNoVersions() {
        FileInfo empty = underTest.save(TestData.fileInfo(creator, chain.tag(), "empty" + TestData.nextSequence()));
        flushAndClear();

        assertThat(underTest.findByIdAndFetchFileDetails(empty.getId())).isPresent();
    }

    @Test
    @DisplayName("the search page resolves the chain and filters on the term")
    void searchesAndFetches() {
        String fileName = underTest.findById(fileInfoId).orElseThrow().getFileName();
        flushAndClear();

        // The term as the service passes it: folded, as the stored key is (SearchKey, 1.8.0).
        var page = underTest.search(com.hnp.filemanagement.util.SearchKey.forSearch(fileName), PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(Hibernate.isInitialized(page.getContent().getFirst().getFolder())).isTrue();
    }

    /**
     * "Everything" is measured against the table, not assumed to be the one row this test made. The
     * container is shared by the whole run, and the web tests that exercise the v2 API commit real
     * {@code file_info} rows — so a fixed count passes or fails depending on which class ran first.
     */
    @Test
    @DisplayName("an empty search term matches everything; a null one matches nothing")
    void anEmptyTermMatchesEverything() {
        var everything = underTest.search("", PageRequest.of(0, Integer.MAX_VALUE));

        assertThat(everything.getTotalElements()).isEqualTo(underTest.count());
        assertThat(everything.getContent()).extracting(FileInfo::getId).contains(fileInfoId);

        // Null is the value PostgreSQL cannot type in this query (issue 87); here it finds nothing,
        // so a caller that forgets SearchTerms.blankToEmpty is caught on MySQL as well.
        assertThat(underTest.search(null, PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    // ---------------------------------------------------------------- lastVersion

    @Test
    @DisplayName("the recompute sets lastVersion to the highest version that exists")
    void recomputesLastVersion() {
        FileInfo fileInfo = underTest.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();
        FileDetails newest = fileInfo.getFileDetailsList().stream()
                .filter(fd -> fd.getVersion() == 2).findFirst().orElseThrow();

        fileInfo.removeFileDetails(newest);

        assertThat(underTest.recalculateLastVersion(fileInfoId)).isEqualTo(1);

        assertThat(underTest.findById(fileInfoId).orElseThrow().getLastVersion()).isEqualTo(1);
        assertThat(fileDetailsRepository.findMaxVersion(fileInfoId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a file with no versions left gets lastVersion 0, not null")
    void recomputesToZeroWhenNothingRemains() {
        FileInfo empty = underTest.save(TestData.fileInfo(creator, chain.tag(), "empty" + TestData.nextSequence()));
        flushAndClear();

        underTest.recalculateLastVersion(empty.getId());

        assertThat(underTest.findById(empty.getId()).orElseThrow().getLastVersion()).isZero();
    }

    @Test
    @DisplayName("recomputing a file that does not exist changes no rows")
    void recomputingAMissingFileChangesNothing() {
        assertThat(underTest.recalculateLastVersion(0)).isZero();
    }

    // ---------------------------------------------------------------- cascade and orphans

    @Test
    @DisplayName("deleting a file deletes every version with it")
    void deletingAFileDeletesItsVersions() {
        underTest.deleteById(fileInfoId);
        flushAndClear();

        assertThat(underTest.findById(fileInfoId)).isEmpty();
        assertThat(fileDetailsRepository.findMaxVersion(fileInfoId)).isNull();
    }

    @Test
    @DisplayName("removing a version from the collection is what deletes it")
    void orphanRemovalDeletesADetachedVersion() {
        FileInfo fileInfo = underTest.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();
        FileDetails newest = fileInfo.getFileDetailsList().stream()
                .filter(fd -> fd.getVersion() == 2).findFirst().orElseThrow();
        int newestId = newest.getId();

        fileInfo.removeFileDetails(newest);
        flushAndClear();

        assertThat(fileDetailsRepository.findById(newestId)).isEmpty();
        assertThat(underTest.findByIdAndFetchFileDetails(fileInfoId).orElseThrow()
                .getFileDetailsList()).hasSize(1);
    }

    // ---------------------------------------------------------------- schema constraints

    @Test
    @DisplayName("two files cannot share a name inside one folder")
    void theSchemaRefusesADuplicateFileNameInAFolder() {
        String taken = underTest.findById(fileInfoId).orElseThrow().getFileName();
        flushAndClear();

        FileInfo duplicate = TestData.fileInfo(creator, chain.tag(), taken);

        // The service checks this too, so that the caller gets a 409 rather than a 500 - but the
        // constraint is what makes it hold when two requests check at the same moment. The insert
        // happens inside save(), because an IDENTITY key can only be assigned by the insert.
        assertThatThrownBy(() -> underTest.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("one version cannot hold the same format twice")
    void theSchemaRefusesADuplicateFormatOfAVersion() {
        FileInfo fileInfo = underTest.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();
        FileDetails duplicate = TestData.fileDetails(creator, fileInfo, 1, "txt");

        // Through the repository rather than the EntityManager, so that Spring translates the
        // driver's exception into the DataAccessException the application actually sees.
        assertThatThrownBy(() -> fileDetailsRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a version may exist in several formats")
    void allowsSeveralFormatsOfOneVersion() {
        FileInfo fileInfo = underTest.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();
        TestData.fileDetails(creator, fileInfo, 1, "pdf");

        flushAndClear();

        assertThat(fileDetailsRepository.countByFileInfoIdAndVersion(fileInfoId, 1)).isEqualTo(2);
    }

    @Test
    @DisplayName("files are counted per folder, and the same name under a sibling folder is another file")
    void countsFilesPerFolder() {
        assertThat(underTest.countByFolderId(chain.tagId())).isEqualTo(1);
        assertThat(underTest.countByFolderId(0)).isZero();

        String taken = underTest.findById(fileInfoId).orElseThrow().getFileName();
        var sibling = FolderFixture.tag(folderRepository, chain.subCategory(), creator, "Sibling" + TestData.nextSequence());
        underTest.saveAndFlush(TestData.fileInfo(creator, sibling, taken));

        assertThat(underTest.countByFolderId(sibling.getId())).isEqualTo(1);
        assertThat(underTest.findByFolderIdAndFileName(chain.tagId(), taken)).isPresent();
        assertThat(underTest.findByFolderIdAndFileName(sibling.getId(), taken)).isPresent();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
