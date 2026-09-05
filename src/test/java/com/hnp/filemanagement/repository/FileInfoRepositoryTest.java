package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
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
class FileInfoRepositoryTest extends MySqlSupport {

    @Autowired
    private FileInfoRepository underTest;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private User creator;
    private MainTagFile mainTag;
    private FileSubCategory subCategory;
    private int fileInfoId;

    @BeforeEach
    void setUp() {
        creator = userRepository.save(TestData.user());
        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(creator, "tag" + TestData.nextSequence()));
        FileCategory category = fileCategoryRepository.save(
                TestData.category(creator, generalTag, "documents" + TestData.nextSequence()));
        subCategory = fileSubCategoryRepository.save(
                TestData.subCategory(creator, category, "invoices" + TestData.nextSequence()));
        mainTag = mainTagFileRepository.save(TestData.mainTag(creator, subCategory, "tag" + TestData.nextSequence()));

        FileInfo fileInfo = TestData.fileInfo(creator, mainTag, "report" + TestData.nextSequence());
        TestData.fileDetails(creator, fileInfo, 1, "txt");
        TestData.fileDetails(creator, fileInfo, 2, "txt");
        fileInfoId = underTest.save(fileInfo).getId();

        flushAndClear();
    }

    // ---------------------------------------------------------------- fetch plans

    @Test
    @DisplayName("the file lookup resolves the whole taxonomy chain in one query")
    void resolvesTheTaxonomyChain() {
        FileInfo fileInfo = underTest.findByIdAndFetchFileDetails(fileInfoId).orElseThrow();

        // Everything the converter walks has to be initialised, or rendering the page would issue
        // a query per row - the N+1 that the lazy mapping makes visible instead of hiding.
        assertThat(Hibernate.isInitialized(fileInfo.getFileDetailsList())).isTrue();
        assertThat(Hibernate.isInitialized(fileInfo.getMainTagFile())).isTrue();
        assertThat(Hibernate.isInitialized(fileInfo.getMainTagFile().getFileSubCategory())).isTrue();
        assertThat(Hibernate.isInitialized(
                fileInfo.getMainTagFile().getFileSubCategory().getFileCategory())).isTrue();
        assertThat(Hibernate.isInitialized(
                fileInfo.getMainTagFile().getFileSubCategory().getFileCategory().getGeneralTag())).isTrue();
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
        FileInfo empty = underTest.save(TestData.fileInfo(creator, mainTag, "empty" + TestData.nextSequence()));
        flushAndClear();

        assertThat(underTest.findByIdAndFetchFileDetails(empty.getId())).isPresent();
    }

    @Test
    @DisplayName("the search page resolves the chain and filters on the term")
    void searchesAndFetches() {
        String fileName = underTest.findById(fileInfoId).orElseThrow().getFileName();
        flushAndClear();

        var page = underTest.search(fileName, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(Hibernate.isInitialized(page.getContent().getFirst().getMainTagFile())).isTrue();
    }

    @Test
    @DisplayName("a null search term matches everything")
    void aNullTermMatchesEverything() {
        assertThat(underTest.search(null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
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
        FileInfo empty = underTest.save(TestData.fileInfo(creator, mainTag, "empty" + TestData.nextSequence()));
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
    @DisplayName("two files cannot share a name inside one sub-category")
    void theSchemaRefusesADuplicateFileNameInASubCategory() {
        String taken = underTest.findById(fileInfoId).orElseThrow().getFileName();
        flushAndClear();

        FileInfo duplicate = TestData.fileInfo(creator, mainTag, taken);

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
    @DisplayName("files are counted per main tag")
    void countsFilesPerTag() {
        assertThat(underTest.countFileWithTagId(mainTag.getId())).isEqualTo(1);
        assertThat(underTest.countFileWithTagId(0)).isZero();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
